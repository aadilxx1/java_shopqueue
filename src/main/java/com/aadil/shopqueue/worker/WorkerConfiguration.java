package com.aadil.shopqueue.worker;

import com.aadil.shopqueue.client.JobQueueClient;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WorkerProperties.class)
class WorkerConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "shopqueue.worker", name = "enabled", havingValue = "true")
    JobWorker jobWorker(JobQueueClient client, List<JobHandler> handlers, WorkerProperties properties) {
        String workerId = properties.id() == null || properties.id().isBlank()
                ? "worker-" + UUID.randomUUID().toString().substring(0, 8)
                : properties.id();
        return new JobWorker(client, handlers, properties, workerId);
    }
}
