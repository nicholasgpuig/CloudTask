package com.cloudtask.jobservice.repository;

import com.cloudtask.jobservice.model.Job;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    List<Job> findByUser_IdOrderByCreatedAtDesc(UUID userId);

    Optional<Job> findByIdAndUser_Id(UUID id, UUID userId);
}
