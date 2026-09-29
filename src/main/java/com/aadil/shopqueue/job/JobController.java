package com.aadil.shopqueue.job;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService service;

    public JobController(JobService service) {
        this.service = service;
    }

    /** 201 for a new job, 200 when the idempotency key matched an existing one. */
    @PostMapping
    public ResponseEntity<JobResponse> enqueue(@Valid @RequestBody JobRequests.Enqueue request) {
        JobService.EnqueueResult result = service.enqueue(request);
        if (result.created()) {
            return ResponseEntity.created(URI.create("/api/jobs/" + result.job().id())).body(result.job());
        }
        return ResponseEntity.ok(result.job());
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/claim")
    public List<JobResponse> claim(@Valid @RequestBody JobRequests.Claim request) {
        return service.claim(request);
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<Void> complete(@PathVariable UUID id,
                                         @Valid @RequestBody JobRequests.WorkerAction request) {
        service.complete(id, request.workerId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/fail")
    public ResponseEntity<Void> fail(@PathVariable UUID id, @Valid @RequestBody JobRequests.Fail request) {
        service.fail(id, request.workerId(), request.error());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/heartbeat")
    public ResponseEntity<Void> heartbeat(@PathVariable UUID id,
                                          @Valid @RequestBody JobRequests.WorkerAction request) {
        service.heartbeat(id, request.workerId());
        return ResponseEntity.noContent().build();
    }
}
