package com.aadil.shopqueue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ApplicationSmokeTest extends PostgresIntegrationTest {

    private final JdbcTemplate jdbc;

    @Autowired
    ApplicationSmokeTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void contextLoadsAgainstRealPostgres() {
        String version = jdbc.queryForObject("select version()", String.class);
        assertThat(version).startsWith("PostgreSQL 17");
    }
}
