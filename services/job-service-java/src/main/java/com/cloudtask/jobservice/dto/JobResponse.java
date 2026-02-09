package com.cloudtask.jobservice.dto;

import com.cloudtask.jobservice.model.Job;
import com.cloudtask.jobservice.model.JobStatus;

import java.time.Instant;
import java.util.UUID;

public record JobResponse(UUID id, String type, String payload, JobStatus status,
                          Instant createdAt, Instant updatedAt) {

    public static JobResponse from(Job job) {
        return new JobResponse(
                job.getId(),
                job.getType(),
                job.getPayload(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
