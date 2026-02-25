package com.cloudtask.jobservice.messaging;

import com.cloudtask.jobservice.model.Job;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class JobPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final MeterRegistry meterRegistry;

    public JobPublisher(RabbitTemplate rabbitTemplate, MeterRegistry meterRegistry) {
        this.rabbitTemplate = rabbitTemplate;
        this.meterRegistry = meterRegistry;
    }

    public void publishJobCreated(Job job) {
        var message = new JobMessage(job.getId(), job.getType(), job.getPayload(), job.getUserId());
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.JOBS_CREATED_QUEUE, message);
            meterRegistry.counter("rabbitmq.publish.total", "outcome", "success").increment();
        } catch (Exception e) {
            meterRegistry.counter("rabbitmq.publish.total", "outcome", "failure").increment();
            throw e;
        }
    }
}
