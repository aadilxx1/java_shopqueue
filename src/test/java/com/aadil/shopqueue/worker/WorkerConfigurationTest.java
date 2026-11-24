package com.aadil.shopqueue.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aadil.shopqueue.client.JobQueueClient;
import com.aadil.shopqueue.client.QueuedJob;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Wiring only: when the worker bean exists and how it's configured. No database needed. */
class WorkerConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(WorkerConfiguration.class)
            .withBean(JobQueueClient.class, () -> mock(JobQueueClient.class))
            .withBean(JobHandler.class, () -> new JobHandler() {
                @Override
                public String type() {
                    return "CHARGE_PAYMENT";
                }

                @Override
                public void handle(QueuedJob job) {
                }
            });

    @Test
    void workerIsDisabledByDefault() {
        context.run(ctx -> assertThat(ctx).doesNotHaveBean(JobWorker.class));
    }

    @Test
    void enabledWorkerStartsWithGeneratedId() {
        context.withPropertyValues("shopqueue.worker.enabled=true").run(ctx -> {
            JobWorker worker = ctx.getBean(JobWorker.class);
            assertThat(worker.isRunning()).isTrue();
            assertThat(worker.workerId()).matches("worker-[0-9a-f]{8}");
        });
    }

    @Test
    void configuredIdIsUsed() {
        context.withPropertyValues("shopqueue.worker.enabled=true", "shopqueue.worker.id=warehouse-box-1")
                .run(ctx -> assertThat(ctx.getBean(JobWorker.class).workerId()).isEqualTo("warehouse-box-1"));
    }
}
