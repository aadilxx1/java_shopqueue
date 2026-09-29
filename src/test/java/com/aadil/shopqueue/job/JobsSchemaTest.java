package com.aadil.shopqueue.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aadil.shopqueue.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies the V1 migration's defaults, constraints and indexes against real Postgres. */
class JobsSchemaTest extends PostgresIntegrationTest {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    @Autowired
    JobsSchemaTest(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @BeforeEach
    void cleanTable() {
        jdbc.update("TRUNCATE jobs");
    }

    @Test
    void minimalInsertGetsSensibleDefaults() {
        UUID id = jdbc.queryForObject(
                "INSERT INTO jobs (type) VALUES ('CHARGE_PAYMENT') RETURNING id", UUID.class);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM jobs WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(row.get("max_attempts")).isEqualTo(5);
        assertThat(row.get("payload").toString()).isEqualTo("{}");
        assertThat(row.get("locked_by")).isNull();
        assertThat(row.get("run_at")).isNotNull();
    }

    @Test
    void rejectsUnknownStatus() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO jobs (type, status) VALUES ('CHARGE_PAYMENT', 'DONE')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("jobs_status_valid");
    }

    @Test
    void rejectsBlankType() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO jobs (type) VALUES ('  ')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("jobs_type_not_blank");
    }

    @Test
    void rejectsAttemptsBeyondMax() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO jobs (type, attempts, max_attempts) VALUES ('CHARGE_PAYMENT', 4, 3)"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("jobs_attempts_in_range");
    }

    @Test
    void runningJobMustHoldALock() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO jobs (type, status) VALUES ('CHARGE_PAYMENT', 'RUNNING')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("jobs_lock_matches_status");
    }

    @Test
    void nonRunningJobMustNotHoldALock() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO jobs (type, status, locked_by, locked_at)
                VALUES ('CHARGE_PAYMENT', 'SUCCEEDED', 'worker-1', now())
                """))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("jobs_lock_matches_status");
    }

    @Test
    void acceptsRunningJobWithLock() {
        int inserted = jdbc.update("""
                INSERT INTO jobs (type, status, locked_by, locked_at, heartbeat_at)
                VALUES ('CHARGE_PAYMENT', 'RUNNING', 'worker-1', now(), now())
                """);
        assertThat(inserted).isEqualTo(1);
    }

    @Test
    void idempotencyKeyIsUniqueButOptional() {
        jdbc.update("INSERT INTO jobs (type, idempotency_key) VALUES ('CHARGE_PAYMENT', 'order-42-charge')");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO jobs (type, idempotency_key) VALUES ('CHARGE_PAYMENT', 'order-42-charge')"))
                .isInstanceOf(DuplicateKeyException.class);

        // Jobs without a key never collide with each other.
        jdbc.update("INSERT INTO jobs (type) VALUES ('SEND_ORDER_EMAIL')");
        jdbc.update("INSERT INTO jobs (type) VALUES ('SEND_ORDER_EMAIL')");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs", Long.class)).isEqualTo(3);
    }

    @Test
    void updateBumpsUpdatedAt() {
        UUID id = jdbc.queryForObject("""
                INSERT INTO jobs (type, created_at, updated_at)
                VALUES ('CHARGE_PAYMENT', now() - interval '1 hour', now() - interval '1 hour')
                RETURNING id
                """, UUID.class);

        jdbc.update("UPDATE jobs SET last_error = 'gateway timeout' WHERE id = ?", id);

        Map<String, Object> row = jdbc.queryForMap("SELECT created_at, updated_at FROM jobs WHERE id = ?", id);
        assertThat((Timestamp) row.get("updated_at")).isAfter((Timestamp) row.get("created_at"));
    }

    @Test
    void claimQueryCanUseClaimableIndex() {
        assertThat(explainWithoutSeqScan("""
                SELECT id FROM jobs
                WHERE status = 'PENDING' AND run_at <= now()
                ORDER BY run_at
                LIMIT 10
                FOR UPDATE SKIP LOCKED
                """)).anyMatch(line -> line.contains("jobs_claimable_idx"));
    }

    @Test
    void reaperQueryCanUseHeartbeatIndex() {
        assertThat(explainWithoutSeqScan("""
                SELECT id FROM jobs
                WHERE status = 'RUNNING' AND heartbeat_at < now() - interval '30 seconds'
                """)).anyMatch(line -> line.contains("jobs_running_heartbeat_idx"));
    }

    /**
     * An empty test table would always get a seq scan, so disable it for this
     * transaction only; the planner then proves whether the index matches the query.
     */
    private List<String> explainWithoutSeqScan(String sql) {
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            return jdbc.queryForList("EXPLAIN " + sql, String.class);
        });
    }
}
