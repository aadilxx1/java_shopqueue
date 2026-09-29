package com.aadil.shopqueue.job;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aadil.shopqueue.PostgresIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** HTTP contract: status codes, JSON shape, validation. Queue semantics live in JobServiceTest. */
@AutoConfigureMockMvc
class JobControllerTest extends PostgresIntegrationTest {

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;

    @Autowired
    JobControllerTest(MockMvc mvc, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void cleanTable() {
        jdbc.update("TRUNCATE jobs");
    }

    @Test
    void enqueueReturns201WithLocation() throws Exception {
        postJson("/api/jobs", """
                {"type": "CHARGE_PAYMENT", "payload": {"orderId": 42}}
                """)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/jobs/")))
                .andExpect(jsonPath("$.type").value("CHARGE_PAYMENT"))
                .andExpect(jsonPath("$.payload.orderId").value(42))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void enqueueWithRepeatedIdempotencyKeyReturns200AndSameJob() throws Exception {
        String body = """
                {"type": "CHARGE_PAYMENT", "idempotencyKey": "order-42-charge"}
                """;
        String firstId = idOf(postJson("/api/jobs", body).andExpect(status().isCreated()));

        postJson("/api/jobs", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId));
    }

    @Test
    void enqueueWithoutTypeIsBadRequest() throws Exception {
        postJson("/api/jobs", "{}").andExpect(status().isBadRequest());
    }

    @Test
    void getUnknownJobIs404ProblemDetail() throws Exception {
        mvc.perform(get("/api/jobs/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void claimCompleteLifecycleOverHttp() throws Exception {
        String id = idOf(postJson("/api/jobs", """
                {"type": "CHARGE_PAYMENT"}
                """));

        postJson("/api/jobs/claim", """
                {"workerId": "worker-1", "types": ["CHARGE_PAYMENT"], "limit": 5}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].status").value("RUNNING"))
                .andExpect(jsonPath("$[0].lockedBy").value("worker-1"));

        postJson("/api/jobs/" + id + "/heartbeat", """
                {"workerId": "worker-1"}
                """).andExpect(status().isNoContent());

        postJson("/api/jobs/" + id + "/complete", """
                {"workerId": "worker-1"}
                """).andExpect(status().isNoContent());

        mvc.perform(get("/api/jobs/{id}", id))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void completeByWrongWorkerIs409() throws Exception {
        String id = idOf(postJson("/api/jobs", """
                {"type": "CHARGE_PAYMENT"}
                """));
        postJson("/api/jobs/claim", """
                {"workerId": "worker-1", "types": ["CHARGE_PAYMENT"]}
                """);

        postJson("/api/jobs/" + id + "/complete", """
                {"workerId": "worker-2"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void failOverHttpRecordsError() throws Exception {
        String id = idOf(postJson("/api/jobs", """
                {"type": "CHARGE_PAYMENT"}
                """));
        postJson("/api/jobs/claim", """
                {"workerId": "worker-1", "types": ["CHARGE_PAYMENT"]}
                """);

        postJson("/api/jobs/" + id + "/fail", """
                {"workerId": "worker-1", "error": "gateway timeout"}
                """).andExpect(status().isNoContent());

        mvc.perform(get("/api/jobs/{id}", id))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.lastError").value("gateway timeout"));
    }

    @Test
    void claimWithoutTypesIsBadRequest() throws Exception {
        postJson("/api/jobs/claim", """
                {"workerId": "worker-1", "types": []}
                """).andExpect(status().isBadRequest());
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String idOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
