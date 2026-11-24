package com.aadil.shopqueue.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.aadil.shopqueue.PostgresIntegrationTest;
import com.aadil.shopqueue.client.HttpJobQueueClient;
import com.aadil.shopqueue.client.JobQueueClient;
import com.aadil.shopqueue.client.NewJob;
import com.aadil.shopqueue.client.QueuedJob;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/** Runs a real worker against the real API and database, with short intervals so tests are fast. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JobWorkerTest extends PostgresIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final JdbcTemplate jdbc;
    private final JobQueueClient client;
    private JobWorker worker;

    @Autowired
    JobWorkerTest(JdbcTemplate jdbc, RestClient.Builder builder, @LocalServerPort int port) {
        this.jdbc = jdbc;
        this.client = new HttpJobQueueClient(builder.baseUrl("http://localhost:" + port).build());
    }

    @BeforeEach
    void cleanTable() {
        jdbc.update("TRUNCATE jobs");
    }

    @AfterEach
    void stopWorker() {
        if (worker != null) {
            worker.stop();
        }
    }

    @Test
    void successfulHandlerCompletesJob() {
        List<UUID> handled = new java.util.concurrent.CopyOnWriteArrayList<>();
        UUID id = enqueue("CHARGE_PAYMENT", 5);

        startWorker(1, handler("CHARGE_PAYMENT", job -> handled.add(job.id())));

        awaitStatus(id, QueuedJob.Status.SUCCEEDED);
        assertThat(handled).containsExactly(id);
    }

    @Test
    void throwingHandlerFailsJobWithError() {
        UUID id = enqueue("CHARGE_PAYMENT", 1);

        startWorker(1, handler("CHARGE_PAYMENT", job -> {
            throw new IllegalStateException("card declined");
        }));

        awaitStatus(id, QueuedJob.Status.FAILED);
        assertThat(client.get(id).orElseThrow().lastError())
                .contains("IllegalStateException: card declined");
    }

    @Test
    void failedJobIsRetriedUntilItSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        UUID id = enqueue("CHARGE_PAYMENT", 3);

        startWorker(1, handler("CHARGE_PAYMENT", job -> {
            if (calls.incrementAndGet() < 3) {
                throw new RuntimeException("gateway timeout");
            }
        }));

        awaitStatus(id, QueuedJob.Status.SUCCEEDED);
        assertThat(client.get(id).orElseThrow().attempts()).isEqualTo(3);
    }

    @Test
    void ignoresJobTypesWithoutHandler() {
        UUID email = enqueue("SEND_ORDER_EMAIL", 5);
        UUID payment = enqueue("CHARGE_PAYMENT", 5);

        startWorker(1, handler("CHARGE_PAYMENT", job -> { }));

        awaitStatus(payment, QueuedJob.Status.SUCCEEDED);
        assertThat(client.get(email).orElseThrow().status()).isEqualTo(QueuedJob.Status.PENDING);
    }

    @Test
    void neverRunsMoreJobsThanConcurrency() {
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<UUID> ids = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> enqueue("GENERATE_INVOICE_PDF", 5)).toList();

        startWorker(2, handler("GENERATE_INVOICE_PDF", job -> {
            peak.accumulateAndGet(current.incrementAndGet(), Math::max);
            Thread.sleep(100);
            current.decrementAndGet();
        }));

        ids.forEach(id -> awaitStatus(id, QueuedJob.Status.SUCCEEDED));
        assertThat(peak.get()).isEqualTo(2);
    }

    @Test
    void heartbeatsWhileLongJobRuns() {
        CountDownLatch release = new CountDownLatch(1);
        UUID id = enqueue("GENERATE_INVOICE_PDF", 5);

        startWorker(1, handler("GENERATE_INVOICE_PDF", job -> release.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)));

        awaitStatus(id, QueuedJob.Status.RUNNING);
        Instant firstHeartbeat = client.get(id).orElseThrow().heartbeatAt();
        await().atMost(WAIT).until(() -> client.get(id).orElseThrow().heartbeatAt().isAfter(firstHeartbeat));

        release.countDown();
        awaitStatus(id, QueuedJob.Status.SUCCEEDED);
    }

    @Test
    void lostLockInterruptsHandlerAndDropsResult() {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        UUID id = enqueue("GENERATE_INVOICE_PDF", 5);

        startWorker(1, handler("GENERATE_INVOICE_PDF", job -> {
            started.countDown();
            try {
                Thread.sleep(WAIT.toMillis());
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw e;
            }
        }));
        await().atMost(WAIT).until(() -> started.getCount() == 0);

        // Simulate the reaper handing the job to another worker.
        jdbc.update("UPDATE jobs SET locked_by = 'worker-thief' WHERE id = ?", id);

        await().atMost(WAIT).untilTrue(interrupted);
        QueuedJob job = client.get(id).orElseThrow();
        assertThat(job.status()).isEqualTo(QueuedJob.Status.RUNNING);
        assertThat(job.lockedBy()).isEqualTo("worker-thief");
        assertThat(job.lastError()).as("old worker must not report a failure").isNull();
    }

    @Test
    void stopWaitsForRunningJobToFinish() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        UUID id = enqueue("GENERATE_INVOICE_PDF", 5);

        startWorker(1, handler("GENERATE_INVOICE_PDF", job -> {
            started.countDown();
            Thread.sleep(300);
        }));
        assertThat(started.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        worker.stop();

        assertThat(client.get(id).orElseThrow().status()).isEqualTo(QueuedJob.Status.SUCCEEDED);
        assertThat(worker.isRunning()).isFalse();
    }

    @Test
    void rejectsTwoHandlersForSameType() {
        assertThatThrownBy(() -> new JobWorker(client,
                List.of(handler("CHARGE_PAYMENT", job -> { }), handler("CHARGE_PAYMENT", job -> { })),
                settings(1), "worker-test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHARGE_PAYMENT");
    }

    // --- helpers ---------------------------------------------------------

    @FunctionalInterface
    interface Body {
        void run(QueuedJob job) throws Exception;
    }

    private static JobHandler handler(String type, Body body) {
        return new JobHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public void handle(QueuedJob job) throws Exception {
                body.run(job);
            }
        };
    }

    private static WorkerProperties settings(int concurrency) {
        return new WorkerProperties(true, null, concurrency,
                Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofSeconds(5));
    }

    private void startWorker(int concurrency, JobHandler... handlers) {
        worker = new JobWorker(client, List.of(handlers), settings(concurrency), "worker-test");
        worker.start();
    }

    private UUID enqueue(String type, int maxAttempts) {
        return client.enqueue(NewJob.of(type, null).withMaxAttempts(maxAttempts)).id();
    }

    private void awaitStatus(UUID id, QueuedJob.Status status) {
        await().atMost(WAIT).until(() -> client.get(id).orElseThrow().status() == status);
    }
}
