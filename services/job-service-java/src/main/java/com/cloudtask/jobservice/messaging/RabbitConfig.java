package com.cloudtask.jobservice.messaging;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String JOBS_CREATED_QUEUE = "jobs.created";
    public static final String JOBS_STARTED_QUEUE = "jobs.started";
    public static final String JOBS_COMPLETED_QUEUE = "jobs.completed";

    @Bean
    public Queue jobsCreatedQueue() {
        return new Queue(JOBS_CREATED_QUEUE, true);
    }

    @Bean
    public Queue jobsStartedQueue() {
        return new Queue(JOBS_STARTED_QUEUE, true);
    }

    @Bean
    public Queue jobsCompletedQueue() {
        return new Queue(JOBS_COMPLETED_QUEUE, true);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
