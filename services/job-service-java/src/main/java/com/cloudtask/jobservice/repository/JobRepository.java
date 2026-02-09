package com.cloudtask.jobservice.repository;

import com.cloudtask.jobservice.model.Job;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {
}
