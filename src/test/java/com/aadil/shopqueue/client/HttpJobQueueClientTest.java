package com.aadil.shopqueue.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aadil.shopqueue.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/** Round-trips through a real HTTP server, so this also checks the client matches the API's JSON. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpJobQueueClientTest extends PostgresIntegrationTest {

    private static final List<String> PAYMENT = List.of("CHARGE_PAYMENT");

    private final JdbcTemplate jdbc;
    private final JobQueueClient client;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    HttpJobQueueClientTest(JdbcTemplate jdbc, RestClient.Builder builder, @LocalServerPort int port) {
        this.jdbc = jdbc;
        this.client = new HttpJobQueueClient(builder.baseUrl("http://localhost:" + port).build());
    }

    @BeforeEach
    void cleanTable() {
        jdbc.update("TRUNCATE jobs");
    }

    @Test
    void enqueueAndGetRoundTrip() throws Exception {
        var payload = json.readTree("{\"orderId\": 42, \"amountCents\": 1999}");

        QueuedJob job = client.enqueue(NewJob.of("CHARGE_PAYMENT", payload).withMaxAttempts(3));

        assertThat(job.id()).isNotNull();
        assertThat(job.status()).isEqualTo(QueuedJob.Status.PENDING);
        assertThat(job.maxAttempts()).isEqualTo(3);
        assertThat(job.createdAt()).isNotNull();
        assertThat(client.get(job.id())).hasValueSatisfying(fetched -> {
            assertThat(fetched.payload()).isEqualTo(payload);
            assertThat(fetched.type()).isEqualTo("CHARGE_PAYMENT");
        });
    }

    @Test
    void enqueueWithSameIdempotencyKeyReturnsOriginal() {
        NewJob job = NewJob.of("CHARGE_PAYMENT", null).withIdempotencyKey("order-42-charge");

        UUID first = client.enqueue(job).id();
        UUID second = client.enqueue(job).id();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void delayedJobIsNotClaimableYet() {
        client.enqueue(NewJob.of("CHARGE_PAYMENT", null).withDelay(Duration.ofHours(1)));

        assertThat(client.claim("worker-1", PAYMENT, 10)).isEmpty();
    }

    @Test
    void getUnknownJobIsEmpty() {
        assertThat(client.get(UUID.randomUUID())).isEmpty();
    }

    @Test
    void workerLifecycle() {
        UUID id = client.enqueue(NewJob.of("CHARGE_PAYMENT", null)).id();

        List<QueuedJob> claimed = client.claim("worker-1", PAYMENT, 5);
        assertThat(claimed).extracting(QueuedJob::id).containsExactly(id);
        assertThat(claimed.getFirst().status()).isEqualTo(QueuedJob.Status.RUNNING);
        assertThat(claimed.getFirst().lockedBy()).isEqualTo("worker-1");

        client.heartbeat(id, "worker-1");
        client.complete(id, "worker-1");

        assertThat(client.get(id)).map(QueuedJob::status).hasValue(QueuedJob.Status.SUCCEEDED);
    }

    @Test
    void failRecordsErrorAndRequeues() {
        UUID id = client.enqueue(NewJob.of("CHARGE_PAYMENT", null)).id();
        client.claim("worker-1", PAYMENT, 1);

        client.fail(id, "worker-1", "gateway timeout");

        assertThat(client.get(id)).hasValueSatisfying(job -> {
            assertThat(job.status()).isEqualTo(QueuedJob.Status.PENDING);
            assertThat(job.lastError()).isEqualTo("gateway timeout");
        });
    }

    @Test
    void actingOnSomeoneElsesJobThrowsLockLost() {
        UUID id = client.enqueue(NewJob.of("CHARGE_PAYMENT", null)).id();
        client.claim("worker-1", PAYMENT, 1);

        assertThatThrownBy(() -> client.complete(id, "worker-2")).isInstanceOf(LockLostException.class);
        assertThatThrownBy(() -> client.fail(id, "worker-2", "x")).isInstanceOf(LockLostException.class);
        assertThatThrownBy(() -> client.heartbeat(id, "worker-2")).isInstanceOf(LockLostException.class);
    }

    @Test
    void actingOnUnknownJobThrowsUnknownJob() {
        assertThatThrownBy(() -> client.complete(UUID.randomUUID(), "worker-1"))
                .isInstanceOf(UnknownJobException.class);
    }

    @Test
    void invalidRequestThrowsWithStatus() {
        assertThatThrownBy(() -> client.enqueue(NewJob.of(" ", null)))
                .isInstanceOfSatisfying(JobQueueClientException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(400));
    }
}
