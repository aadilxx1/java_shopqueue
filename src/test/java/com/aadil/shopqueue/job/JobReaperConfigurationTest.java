package com.aadil.shopqueue.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Wiring and error handling only. No database needed. */
class JobReaperConfigurationTest {

    private final JobService service = mock(JobService.class);

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(JobReaper.class)
            .withBean(JobService.class, () -> service);

    @Test
    void reaperIsOnByDefaultWithDefaultTimeout() {
        context.run(ctx -> {
            assertThat(ctx).hasSingleBean(JobReaper.class);
            assertThat(ctx.getBean(ReaperProperties.class).timeout()).isEqualTo(Duration.ofSeconds(60));
        });
    }

    @Test
    void reaperCanBeDisabled() {
        context.withPropertyValues("shopqueue.reaper.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(JobReaper.class));
    }

    @Test
    void configuredTimeoutIsPassedToService() {
        context.withPropertyValues("shopqueue.reaper.timeout=90s", "shopqueue.reaper.interval=1h").run(ctx -> {
            ctx.getBean(JobReaper.class).reap();
            verify(service).reapStale(Duration.ofSeconds(90));
        });
    }

    @Test
    void failedRunDoesNotPropagate() {
        when(service.reapStale(any())).thenThrow(new RuntimeException("database down"));
        context.withPropertyValues("shopqueue.reaper.interval=1h").run(ctx ->
                assertThatCode(() -> ctx.getBean(JobReaper.class).reap()).doesNotThrowAnyException());
    }
}
