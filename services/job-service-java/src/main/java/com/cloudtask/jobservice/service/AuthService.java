package com.cloudtask.jobservice.service;

import com.cloudtask.jobservice.dto.AuthResponse;
import com.cloudtask.jobservice.dto.LoginRequest;
import com.cloudtask.jobservice.dto.RegisterRequest;
import com.cloudtask.jobservice.model.User;
import com.cloudtask.jobservice.repository.UserRepository;
import com.cloudtask.jobservice.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Value("${jwt.expiration-ms:3600000}")
    private long expirationMs;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }

        var user = new User(request.email(), passwordEncoder.encode(request.password()));
        user = userRepository.save(user);

        return generateTokenResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        var user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        return generateTokenResponse(user);
    }

    private AuthResponse generateTokenResponse(User user) {
        String token = jwtUtil.generate(
                user.getId().toString(),
                Map.of("email", user.getEmail())
        );
        return AuthResponse.of(token, expirationMs);
    }
}
