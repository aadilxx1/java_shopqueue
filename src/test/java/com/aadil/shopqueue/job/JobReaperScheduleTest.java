package com.aadil.shopqueue.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aadil.shopqueue.PostgresIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** The reaper actually runs on its schedule when enabled (on by default in the app). */
@TestPropertySource(properties = {
        "shopqueue.reaper.enabled=true",
        "shopqueue.reaper.interval=200ms",
        "shopqueue.reaper.timeout=1s"})
class JobReaperScheduleTest extends PostgresIntegrationTest {

    private final JobService service;
    private final JdbcTemplate jdbc;
    private final ApplicationContext context;

    @Autowired
    JobReaperScheduleTest(JobService service, JdbcTemplate jdbc, ApplicationContext context) {
        this.service = service;
        this.jdbc = jdbc;
        this.context = context;
    }

    @BeforeEach
    void cleanTable() {
        jdbc.update("TRUNCATE jobs");
    }

    @Test
    void scheduledReaperReclaimsJobFromCrashedWorker() {
        assertThat(context.getBeansOfType(JobReaper.class)).hasSize(1);
        UUID id = service.enqueue(new JobRequests.Enqueue("CHARGE_PAYMENT", null, null, null, null)).job().id();
        service.claim(new JobRequests.Claim("crashed-worker", List.of("CHARGE_PAYMENT"), 1));

        // The "crashed" worker never heartbeats, so once the 1s timeout passes the reaper takes it back.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(service.get(id).status()).isEqualTo(JobStatus.PENDING));
        assertThat(service.get(id).lastError()).contains("crashed-worker");
    }
}
