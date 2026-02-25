package com.cloudtask.jobservice.controller;

import com.cloudtask.jobservice.dto.CreateJobRequest;
import com.cloudtask.jobservice.dto.JobResponse;
import com.cloudtask.jobservice.messaging.JobPublisher;
import com.cloudtask.jobservice.model.Job;
import com.cloudtask.jobservice.model.User;
import com.cloudtask.jobservice.repository.JobRepository;
import com.cloudtask.jobservice.service.IdempotencyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/jobs")
public class JobController {

    private final JobRepository jobRepository;
    private final JobPublisher jobPublisher;
    private final ObjectMapper objectMapper;
    private final JobMetrics jobMetrics;
    private final IdempotencyService idempotencyService;

    public JobController(JobRepository jobRepository, JobPublisher jobPublisher,
                         ObjectMapper objectMapper, JobMetrics jobMetrics,
                         IdempotencyService idempotencyService) {
        this.jobRepository = jobRepository;
        this.jobPublisher = jobPublisher;
        this.objectMapper = objectMapper;
        this.jobMetrics = jobMetrics;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobResponse createJob(@RequestBody CreateJobRequest request,
                                 @AuthenticationPrincipal User user,
                                 HttpServletRequest httpRequest) {
        Optional<String> idempotencyKey = idempotencyService.extractKey(httpRequest);
        if (idempotencyKey.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or missing Idempotency-Key header");
        }

        String idempotencyValue = idempotencyService.get(user.getId().toString(), idempotencyKey.get());
        if (idempotencyValue == null) {
            Boolean reserved = idempotencyService.reserve(user.getId().toString(), idempotencyKey.get());
            if (reserved) {
                return jobMetrics.jobCreationTimer(request.type()).record(() -> {
                    String payloadString = serializePayload(request.payload());
                    var job = new Job(request.type(), payloadString, user);
                    job = jobRepository.save(job);
                    jobPublisher.publishJobCreated(job);
                    jobMetrics.jobsCreatedCounter(request.type()).increment();
                    try {
                        idempotencyService.store(user.getId().toString(), idempotencyKey.get(), objectMapper.writeValueAsString(JobResponse.from(job)));
                    } catch (JsonProcessingException e) {
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to cache response");
                    }
                    return JobResponse.from(job);
                });
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is being processed");
        } else if ("PROCESSING".equals(idempotencyValue)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is being processed");
        } else {
            try {
                return objectMapper.readValue(idempotencyValue, JobResponse.class);
            } catch (JsonProcessingException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to parse cached response");
            }
        }
    }

    private String serializePayload(Object payload) {
        if (payload == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payload");
        }
    }

    @GetMapping("/{id}")
    public JobResponse getJob(@PathVariable UUID id,
                              @AuthenticationPrincipal User user) {
        var job = jobRepository.findByIdAndUser_Id(id, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
        return JobResponse.from(job);
    }

    @GetMapping
    public List<JobResponse> listJobs(@AuthenticationPrincipal User user) {
        return jobRepository.findByUser_IdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(JobResponse::from)
                .toList();
    }
}
