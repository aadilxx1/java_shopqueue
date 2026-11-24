package com.aadil.shopqueue;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests. Starts one real PostgreSQL container shared
 * by all subclasses (singleton pattern), so SKIP LOCKED and friends behave exactly
 * as in production.
 *
 * <p>The scheduled reaper is off so it can't reclaim jobs that tests deliberately
 * make stale; tests that need it running override the property.
 */
@SpringBootTest
@TestPropertySource(properties = "shopqueue.reaper.enabled=false")
public abstract class PostgresIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }
}
