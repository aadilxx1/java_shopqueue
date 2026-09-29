package com.aadil.shopqueue;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests. Starts one real PostgreSQL container shared
 * by all subclasses (singleton pattern), so SKIP LOCKED and friends behave exactly
 * as in production.
 */
@SpringBootTest
public abstract class PostgresIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }
}
