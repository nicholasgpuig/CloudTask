package com.cloudtask.jobservice.controller;

import com.cloudtask.jobservice.dto.CreateJobRequest;
import com.cloudtask.jobservice.dto.JobResponse;
import com.cloudtask.jobservice.messaging.JobPublisher;
import com.cloudtask.jobservice.model.Job;
import com.cloudtask.jobservice.model.User;
import com.cloudtask.jobservice.repository.JobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/jobs")
public class JobController {

    private final JobRepository jobRepository;
    private final JobPublisher jobPublisher;

    public JobController(JobRepository jobRepository, JobPublisher jobPublisher) {
        this.jobRepository = jobRepository;
        this.jobPublisher = jobPublisher;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobResponse createJob(@RequestBody CreateJobRequest request,
                                 @AuthenticationPrincipal User user) {
        var job = new Job(request.type(), request.payload(), user);
        job = jobRepository.save(job);
        jobPublisher.publishJobCreated(job);
        return JobResponse.from(job);
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