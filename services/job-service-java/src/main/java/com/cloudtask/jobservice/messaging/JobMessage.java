package com.cloudtask.jobservice.messaging;

import java.util.UUID;

public record JobMessage(UUID jobId, String type, String payload, UUID userId) {
}
