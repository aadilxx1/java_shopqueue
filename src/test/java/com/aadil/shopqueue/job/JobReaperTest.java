package com.aadil.shopqueue.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aadil.shopqueue.PostgresIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reaper logic, called directly so timing is deterministic (the schedule is off here). */
class JobReaperTest extends PostgresIntegrationTest {

    private static final List<String> PAYMENT = List.of("CHARGE_PAYMENT");
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final JobService service;
    private final JdbcTemplate jdbc;

    @Autowired
    JobReaperTest(JobService service, JdbcTemplate jdbc) {
        this.service = service;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void cleanTable() {
        jdbc.update("TRUNCATE jobs");
    }

    @Test
    void staleJobGoesBackToPendingAndCanBeReclaimed() {
        UUID id = enqueueAndClaim("worker-1", null);
        makeStale(id);

        assertThat(service.reapStale(TIMEOUT)).containsExactly(id);

        JobResponse job = service.get(id);
        assertThat(job.status()).isEqualTo(JobStatus.PENDING);
        assertThat(job.lockedBy()).isNull();
        assertThat(job.lockedAt()).isNull();
        assertThat(job.heartbeatAt()).isNull();
        assertThat(job.lastError()).startsWith("Reclaimed: worker worker-1 stopped heartbeating");

        List<JobResponse> reclaimed = claim("worker-2");
        assertThat(reclaimed).extracting(JobResponse::id).containsExactly(id);
        assertThat(reclaimed.getFirst().attempts()).as("the crashed run counts as an attempt").isEqualTo(2);
    }

    @Test
    void oldWorkerIsFencedOutAfterReap() {
        UUID id = enqueueAndClaim("worker-1", null);
        makeStale(id);
        service.reapStale(TIMEOUT);
        claim("worker-2");

        assertThatThrownBy(() -> service.heartbeat(id, "worker-1")).isInstanceOf(JobLockLostException.class);
        assertThatThrownBy(() -> service.complete(id, "worker-1")).isInstanceOf(JobLockLostException.class);

        service.complete(id, "worker-2");
        assertThat(service.get(id).status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void staleJobOnLastAttemptFailsForGood() {
        UUID id = enqueueAndClaim("worker-1", 1);
        makeStale(id);

        assertThat(service.reapStale(TIMEOUT)).containsExactly(id);

        JobResponse job = service.get(id);
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.lockedBy()).isNull();
        assertThat(job.lastError()).contains("worker-1");
        assertThat(claim("worker-2")).isEmpty();
    }

    @Test
    void jobWithRecentHeartbeatIsLeftAlone() {
        UUID id = enqueueAndClaim("worker-1", null);
        jdbc.update("UPDATE jobs SET heartbeat_at = now() - interval '30 seconds' WHERE id = ?", id);

        assertThat(service.reapStale(TIMEOUT)).isEmpty();

        JobResponse job = service.get(id);
        assertThat(job.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.lockedBy()).isEqualTo("worker-1");
    }

    @Test
    void onlyRunningJobsAreReaped() {
        UUID done = enqueueAndClaim("worker-1", null);
        service.complete(done, "worker-1");
        UUID pending = service.enqueue(new JobRequests.Enqueue("CHARGE_PAYMENT", null, null, 1, 3600L)).job().id();

        assertThat(service.reapStale(TIMEOUT)).isEmpty();
        assertThat(service.get(pending).status()).isEqualTo(JobStatus.PENDING);
        assertThat(service.get(done).status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void reapsEveryStaleJobAcrossWorkers() {
        UUID a = enqueueAndClaim("worker-1", null);
        UUID b = enqueueAndClaim("worker-2", null);
        UUID fresh = enqueueAndClaim("worker-3", null);
        makeStale(a);
        makeStale(b);

        assertThat(service.reapStale(TIMEOUT)).containsExactlyInAnyOrder(a, b);
        assertThat(service.get(fresh).status()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    void nonPositiveTimeoutIsRejected() {
        assertThatThrownBy(() -> service.reapStale(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reapStale(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- helpers ---------------------------------------------------------

    private UUID enqueueAndClaim(String workerId, Integer maxAttempts) {
        UUID id = service.enqueue(new JobRequests.Enqueue("CHARGE_PAYMENT", null, null, maxAttempts, null))
                .job().id();
        assertThat(claim(workerId)).extracting(JobResponse::id).contains(id);
        return id;
    }

    private List<JobResponse> claim(String workerId) {
        return service.claim(new JobRequests.Claim(workerId, PAYMENT, 1));
    }

    private void makeStale(UUID id) {
        jdbc.update("UPDATE jobs SET heartbeat_at = now() - interval '5 minutes' WHERE id = ?", id);
    }
}
