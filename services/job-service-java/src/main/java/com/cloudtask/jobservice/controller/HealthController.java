package com.cloudtask.jobservice.controller;

import com.cloudtask.jobservice.security.JwtUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/health")
public class HealthController {

    private final JwtUtil jwtUtil;

    public HealthController(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @GetMapping
    public Map<String, String> health() {
        String token = jwtUtil.generate("health-check", Map.of(
                "role", "SYSTEM",
                "service", "job-service"
        ));
        return Map.of(
                "status", "RUNNING",
                "token", token
        );
    }
}
