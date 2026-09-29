package com.aadil.shopqueue.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aadil.shopqueue.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class JobServiceTest extends PostgresIntegrationTest {

    private static final List<String> PAYMENT = List.of("CHARGE_PAYMENT");

    private final JobService service;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    JobServiceTest(JobService service, JdbcTemplate jdbc) {
        this.service = service;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void cleanTable() {
        jdbc.update("TRUNCATE jobs");
    }

    // --- enqueue ---------------------------------------------------------

    @Test
    void enqueueStoresPayloadAndDefaults() throws Exception {
        var payload = json.readTree("{\"orderId\": 42, \"amountCents\": 1999}");

        var result = service.enqueue(new JobRequests.Enqueue("CHARGE_PAYMENT", payload, null, null, null));

        assertThat(result.created()).isTrue();
        JobResponse job = service.get(result.job().id());
        assertThat(job.type()).isEqualTo("CHARGE_PAYMENT");
        assertThat(job.payload()).isEqualTo(payload);
        assertThat(job.status()).isEqualTo(JobStatus.PENDING);
        assertThat(job.attempts()).isZero();
        assertThat(job.maxAttempts()).isEqualTo(JobService.DEFAULT_MAX_ATTEMPTS);
    }

    @Test
    void enqueueWithSameIdempotencyKeyReturnsOriginalJob() {
        var first = service.enqueue(new JobRequests.Enqueue("CHARGE_PAYMENT", null, "order-42-charge", null, null));
        var second = service.enqueue(new JobRequests.Enqueue("CHARGE_PAYMENT", null, "order-42-charge", null, null));

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.job().id()).isEqualTo(first.job().id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs", Long.class)).isEqualTo(1);
    }

    @Test
    void getUnknownJobThrowsNotFound() {
        assertThatThrownBy(() -> service.get(UUID.randomUUID())).isInstanceOf(JobNotFoundException.class);
    }

    // --- claim -----------------------------------------------------------

    @Test
    void claimLocksJobForWorkerAndCountsAttempt() {
        UUID id = enqueue("CHARGE_PAYMENT");

        List<JobResponse> claimed = claim("worker-1", PAYMENT, 10);

        assertThat(claimed).extracting(JobResponse::id).containsExactly(id);
        JobResponse job = claimed.getFirst();
        assertThat(job.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.lockedBy()).isEqualTo("worker-1");
        assertThat(job.lockedAt()).isNotNull();
        assertThat(job.heartbeatAt()).isNotNull();
        assertThat(job.attempts()).isEqualTo(1);

        assertThat(claim("worker-2", PAYMENT, 10)).as("already claimed").isEmpty();
    }

    @Test
    void claimOnlyReturnsRequestedTypes() {
        enqueue("SEND_ORDER_EMAIL");
        UUID payment = enqueue("CHARGE_PAYMENT");

        assertThat(claim("worker-1", PAYMENT, 10)).extracting(JobResponse::id).containsExactly(payment);
    }

    @Test
    void claimSkipsJobsScheduledInTheFuture() {
        service.enqueue(new JobRequests.Enqueue("CHARGE_PAYMENT", null, null, null, 3600L));

        assertThat(claim("worker-1", PAYMENT, 10)).isEmpty();
    }

    @Test
    void claimRespectsLimitAndReturnsOldestFirst() {
        UUID oldest = enqueue("CHARGE_PAYMENT");
        UUID middle = enqueue("CHARGE_PAYMENT");
        enqueue("CHARGE_PAYMENT");
        // Make ordering explicit rather than relying on now() resolution.
        jdbc.update("UPDATE jobs SET run_at = now() - interval '2 minutes' WHERE id = ?", oldest);
        jdbc.update("UPDATE jobs SET run_at = now() - interval '1 minute' WHERE id = ?", middle);

        assertThat(claim("worker-1", PAYMENT, 2)).extracting(JobResponse::id).containsExactly(oldest, middle);
    }

    @Test
    void concurrentClaimersNeverGetTheSameJob() throws Exception {
        int jobCount = 200;
        int workers = 8;
        for (int i = 0; i < jobCount; i++) {
            enqueue("CHARGE_PAYMENT");
        }

        List<UUID> claimedIds = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                String workerId = "worker-" + w;
                futures.add(pool.submit(() -> {
                    start.await();
                    List<JobResponse> batch;
                    do {
                        batch = claim(workerId, PAYMENT, 5);
                        batch.forEach(job -> claimedIds.add(job.id()));
                    } while (!batch.isEmpty());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }

        Set<UUID> unique = new HashSet<>(claimedIds);
        assertThat(claimedIds).as("no job claimed twice").hasSameSizeAs(unique);
        assertThat(unique).as("every job claimed").hasSize(jobCount);
    }

    // --- complete / fail / heartbeat (fencing) ---------------------------

    @Test
    void completeByOwnerSucceedsAndReleasesLock() {
        UUID id = enqueueAndClaim("worker-1");

        service.complete(id, "worker-1");

        JobResponse job = service.get(id);
        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.lockedBy()).isNull();
        assertThat(job.heartbeatAt()).isNull();
    }

    @Test
    void completeByOtherWorkerIsRejected() {
        UUID id = enqueueAndClaim("worker-1");

        assertThatThrownBy(() -> service.complete(id, "worker-2")).isInstanceOf(JobLockLostException.class);
        assertThat(service.get(id).status()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    void completeTwiceIsRejected() {
        UUID id = enqueueAndClaim("worker-1");
        service.complete(id, "worker-1");

        assertThatThrownBy(() -> service.complete(id, "worker-1")).isInstanceOf(JobLockLostException.class);
    }

    @Test
    void completeUnknownJobThrowsNotFound() {
        assertThatThrownBy(() -> service.complete(UUID.randomUUID(), "worker-1"))
                .isInstanceOf(JobNotFoundException.class);
    }

    @Test
    void failWithAttemptsLeftGoesBackToPending() {
        UUID id = enqueueAndClaim("worker-1");

        service.fail(id, "worker-1", "payment gateway timeout");

        JobResponse job = service.get(id);
        assertThat(job.status()).isEqualTo(JobStatus.PENDING);
        assertThat(job.lastError()).isEqualTo("payment gateway timeout");
        assertThat(job.lockedBy()).isNull();
        assertThat(claim("worker-2", PAYMENT, 10)).extracting(JobResponse::id).containsExactly(id);
    }

    @Test
    void failOnLastAttemptIsTerminal() {
        UUID id = service.enqueue(new JobRequests.Enqueue("CHARGE_PAYMENT", null, null, 2, null)).job().id();

        claim("worker-1", PAYMENT, 1);
        service.fail(id, "worker-1", "timeout #1");
        claim("worker-1", PAYMENT, 1);
        service.fail(id, "worker-1", "card declined");

        JobResponse job = service.get(id);
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.attempts()).isEqualTo(2);
        assertThat(job.lastError()).isEqualTo("card declined");
        assertThat(claim("worker-1", PAYMENT, 10)).isEmpty();
    }

    @Test
    void failByOtherWorkerIsRejected() {
        UUID id = enqueueAndClaim("worker-1");

        assertThatThrownBy(() -> service.fail(id, "worker-2", "boom")).isInstanceOf(JobLockLostException.class);
    }

    @Test
    void failTruncatesHugeErrors() {
        UUID id = enqueueAndClaim("worker-1");

        service.fail(id, "worker-1", "x".repeat(10_000));

        assertThat(service.get(id).lastError()).hasSize(4000);
    }

    @Test
    void heartbeatByOwnerMovesHeartbeatForward() {
        UUID id = enqueueAndClaim("worker-1");
        jdbc.update("UPDATE jobs SET heartbeat_at = now() - interval '1 minute' WHERE id = ?", id);
        var before = service.get(id).heartbeatAt();

        service.heartbeat(id, "worker-1");

        assertThat(service.get(id).heartbeatAt()).isAfter(before);
    }

    @Test
    void heartbeatByOtherWorkerIsRejected() {
        UUID id = enqueueAndClaim("worker-1");

        assertThatThrownBy(() -> service.heartbeat(id, "worker-2")).isInstanceOf(JobLockLostException.class);
    }

    // --- helpers ---------------------------------------------------------

    private UUID enqueue(String type) {
        return service.enqueue(new JobRequests.Enqueue(type, null, null, null, null)).job().id();
    }

    private UUID enqueueAndClaim(String workerId) {
        UUID id = enqueue("CHARGE_PAYMENT");
        assertThat(claim(workerId, PAYMENT, 1)).extracting(JobResponse::id).containsExactly(id);
        return id;
    }

    private List<JobResponse> claim(String workerId, List<String> types, int limit) {
        return service.claim(new JobRequests.Claim(workerId, types, limit));
    }
}
