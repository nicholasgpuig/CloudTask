package com.cloudtask.jobservice.controller;

import com.cloudtask.jobservice.dto.AuthResponse;
import com.cloudtask.jobservice.dto.LoginRequest;
import com.cloudtask.jobservice.dto.RegisterRequest;
import com.cloudtask.jobservice.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest request) {
        return authService.login(request);
    }
}
