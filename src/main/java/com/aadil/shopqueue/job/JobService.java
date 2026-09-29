package com.aadil.shopqueue.job;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobService {

    static final int DEFAULT_MAX_ATTEMPTS = 5;
    static final int DEFAULT_CLAIM_LIMIT = 10;

    private final JobRepository repository;

    public JobService(JobRepository repository) {
        this.repository = repository;
    }

    public record EnqueueResult(JobResponse job, boolean created) {
    }

    @Transactional
    public EnqueueResult enqueue(JobRequests.Enqueue request) {
        String payload = request.payload() == null ? "{}" : request.payload().toString();
        int maxAttempts = request.maxAttempts() == null ? DEFAULT_MAX_ATTEMPTS : request.maxAttempts();
        long delaySeconds = request.delaySeconds() == null ? 0 : request.delaySeconds();

        Optional<UUID> newId = repository.insertUnlessDuplicate(
                request.type(), payload, request.idempotencyKey(), maxAttempts, delaySeconds);

        if (newId.isPresent()) {
            return new EnqueueResult(get(newId.get()), true);
        }
        // Only an idempotency-key conflict can suppress the insert.
        Job existing = repository.findByIdempotencyKey(request.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException(
                        "Insert suppressed but no job with key " + request.idempotencyKey()));
        return new EnqueueResult(JobResponse.from(existing), false);
    }

    @Transactional(readOnly = true)
    public JobResponse get(UUID id) {
        return repository.findById(id)
                .map(JobResponse::from)
                .orElseThrow(() -> new JobNotFoundException(id));
    }

    @Transactional
    public List<JobResponse> claim(JobRequests.Claim request) {
        int limit = request.limit() == null ? DEFAULT_CLAIM_LIMIT : request.limit();
        return repository.claim(request.workerId(), request.types(), limit).stream()
                .sorted(Comparator.comparing(Job::getRunAt))
                .map(JobResponse::from)
                .toList();
    }

    @Transactional
    public void complete(UUID id, String workerId) {
        requireFencedUpdate(repository.complete(id, workerId), id, workerId);
    }

    @Transactional
    public void fail(UUID id, String workerId, String error) {
        requireFencedUpdate(repository.fail(id, workerId, error), id, workerId);
    }

    @Transactional
    public void heartbeat(UUID id, String workerId) {
        requireFencedUpdate(repository.heartbeat(id, workerId), id, workerId);
    }

    /** 0 rows means either the job doesn't exist or this worker no longer holds it. */
    private void requireFencedUpdate(int updatedRows, UUID id, String workerId) {
        if (updatedRows == 1) {
            return;
        }
        if (!repository.existsById(id)) {
            throw new JobNotFoundException(id);
        }
        throw new JobLockLostException(id, workerId);
    }
}
