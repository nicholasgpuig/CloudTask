package com.cloudtask.jobservice.controller;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class JobMetrics {

    private final MeterRegistry registry;

    public JobMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Counter jobsCreatedCounter(String type) {
        return Counter.builder("jobs.created.total")
                .tag("type", type)
                .description("Total jobs created by type")
                .register(registry);
    }

    public Timer jobCreationTimer(String type) {
        return Timer.builder("jobs.created.seconds")
                .tag("type", type)
                .description("Job creation latency by type")
                .publishPercentileHistogram(true)
                .register(registry);
    }
}
