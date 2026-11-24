package com.aadil.shopqueue.client;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

/** {@link JobQueueClient} over the REST API in job/ ({@code /api/jobs}). */
public class HttpJobQueueClient implements JobQueueClient {

    private static final ParameterizedTypeReference<List<QueuedJob>> JOB_LIST = new ParameterizedTypeReference<>() {
    };

    private final RestClient http;

    /** @param http a RestClient whose base URL points at the queue */
    public HttpJobQueueClient(RestClient http) {
        this.http = http;
    }

    private record ClaimBody(String workerId, Collection<String> types, int limit) {
    }

    private record WorkerBody(String workerId) {
    }

    private record FailBody(String workerId, String error) {
    }

    @Override
    public QueuedJob enqueue(NewJob job) {
        return http.post().uri("/api/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(job)
                .retrieve()
                .onStatus(HttpStatusCode::isError, HttpJobQueueClient::throwFor)
                .body(QueuedJob.class);
    }

    @Override
    public Optional<QueuedJob> get(UUID jobId) {
        try {
            return Optional.ofNullable(http.get().uri("/api/jobs/{id}", jobId)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, HttpJobQueueClient::throwFor)
                    .body(QueuedJob.class));
        } catch (UnknownJobException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<QueuedJob> claim(String workerId, Collection<String> types, int limit) {
        List<QueuedJob> jobs = http.post().uri("/api/jobs/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ClaimBody(workerId, types, limit))
                .retrieve()
                .onStatus(HttpStatusCode::isError, HttpJobQueueClient::throwFor)
                .body(JOB_LIST);
        return jobs == null ? List.of() : jobs;
    }

    @Override
    public void complete(UUID jobId, String workerId) {
        postNoContent("/api/jobs/{id}/complete", jobId, new WorkerBody(workerId));
    }

    @Override
    public void fail(UUID jobId, String workerId, String error) {
        postNoContent("/api/jobs/{id}/fail", jobId, new FailBody(workerId, error));
    }

    @Override
    public void heartbeat(UUID jobId, String workerId) {
        postNoContent("/api/jobs/{id}/heartbeat", jobId, new WorkerBody(workerId));
    }

    private void postNoContent(String uri, UUID jobId, Object body) {
        http.post().uri(uri, jobId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, HttpJobQueueClient::throwFor)
                .toBodilessEntity();
    }

    private static void throwFor(HttpRequest request, ClientHttpResponse response) throws java.io.IOException {
        int status = response.getStatusCode().value();
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        String message = request.getMethod() + " " + request.getURI() + " -> " + status + ": " + body;
        throw switch (status) {
            case 404 -> new UnknownJobException(message);
            case 409 -> new LockLostException(message);
            default -> new JobQueueClientException(status, message);
        };
    }
}
