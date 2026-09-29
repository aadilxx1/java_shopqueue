package com.aadil.shopqueue.job;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request bodies for {@link JobController}. */
public final class JobRequests {

    private JobRequests() {
    }

    /**
     * @param payload        any JSON object; defaults to {@code {}}
     * @param idempotencyKey optional; enqueueing the same key twice returns the first job
     * @param maxAttempts    optional; defaults to {@link JobService#DEFAULT_MAX_ATTEMPTS}
     * @param delaySeconds   optional; job becomes claimable this many seconds from now
     */
    public record Enqueue(
            @NotBlank @Size(max = 100) String type,
            JsonNode payload,
            @Size(max = 200) String idempotencyKey,
            @Min(1) @Max(100) Integer maxAttempts,
            @Min(0) Long delaySeconds) {
    }

    public record Claim(
            @NotBlank @Size(max = 200) String workerId,
            @NotEmpty List<@NotBlank String> types,
            @Min(1) @Max(100) Integer limit) {
    }

    /** Body for complete and heartbeat: proves which worker is calling. */
    public record WorkerAction(@NotBlank @Size(max = 200) String workerId) {
    }

    public record Fail(
            @NotBlank @Size(max = 200) String workerId,
            String error) {
    }
}
