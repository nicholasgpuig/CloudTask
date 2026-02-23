package com.cloudtask.jobservice.messaging;

import com.cloudtask.jobservice.model.Job;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class JobPublisher {

    private final RabbitTemplate rabbitTemplate;

    public JobPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishJobCreated(Job job) {
        var message = new JobMessage(job.getId(), job.getType(), job.getPayload(), job.getUserId());
        rabbitTemplate.convertAndSend(RabbitConfig.JOBS_CREATED_QUEUE, message);
    }
}
