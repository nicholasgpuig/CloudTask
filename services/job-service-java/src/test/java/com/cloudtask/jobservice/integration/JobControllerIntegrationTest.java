package com.cloudtask.jobservice.integration;

import com.cloudtask.jobservice.dto.AuthResponse;
import com.cloudtask.jobservice.dto.RegisterRequest;
import com.cloudtask.jobservice.messaging.JobPublisher;
import com.cloudtask.jobservice.model.Job;
import com.cloudtask.jobservice.repository.JobRepository;
import com.cloudtask.jobservice.repository.UserRepository;
import com.cloudtask.jobservice.service.IdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class JobControllerIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JobRepository jobRepository;

    @MockitoBean
    private JobPublisher jobPublisher;

    @MockitoBean
    private RedisConnectionFactory redisConnectionFactory;

    @MockitoBean
    private IdempotencyService idempotencyService;

    private String userToken;
    private String otherUserToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        jobRepository.deleteAll();
        userRepository.deleteAll();

        userToken = registerAndGetToken("user1@example.com", "password123");
        otherUserToken = registerAndGetToken("user2@example.com", "password123");

        // Default happy-path behavior: valid key, no cached value, reserve succeeds
        given(idempotencyService.extractKey(any())).willReturn(Optional.of(UUID.randomUUID().toString()));
        given(idempotencyService.get(any(), any())).willReturn(null);
        given(idempotencyService.reserve(any(), any())).willReturn(true);
    }

    private String registerAndGetToken(String email, String password) throws Exception {
        var request = new RegisterRequest(email, password);
        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                AuthResponse.class
        );
        return response.accessToken();
    }

    @Test
    void createJob_authenticated_success() throws Exception {
        mockMvc.perform(post("/jobs")
                        .header("Authorization", "Bearer " + userToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "sleep", "payload": {"seconds": 5}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.type").value("sleep"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(jobPublisher).publishJobCreated(any(Job.class));
    }

    @Test
    void createJob_noToken_returns403() throws Exception {
        mockMvc.perform(post("/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "sleep", "payload": {"seconds": 5}}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void createJob_invalidToken_returns401() throws Exception {
        mockMvc.perform(post("/jobs")
                        .header("Authorization", "Bearer invalid.token.here")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "sleep", "payload": {"seconds": 5}}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createJob_missingIdempotencyKey_returns400() throws Exception {
        given(idempotencyService.extractKey(any())).willReturn(Optional.empty());

        mockMvc.perform(post("/jobs")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "sleep", "payload": {"seconds": 5}}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createJob_processingKey_returns409() throws Exception {
        given(idempotencyService.get(any(), any())).willReturn("PROCESSING");

        mockMvc.perform(post("/jobs")
                        .header("Authorization", "Bearer " + userToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "sleep", "payload": {"seconds": 5}}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void createJob_reserveFails_returns409() throws Exception {
        given(idempotencyService.reserve(any(), any())).willReturn(false);

        mockMvc.perform(post("/jobs")
                        .header("Authorization", "Bearer " + userToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "sleep", "payload": {"seconds": 5}}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void createJob_cachedResponse_returnsCachedJob() throws Exception {
        String jobId = UUID.randomUUID().toString();
        String cachedJson = """
                {"id":"%s","type":"sleep","payload":"{\\"seconds\\":5}","status":"PENDING","createdAt":"2024-01-01T00:00:00Z","updatedAt":"2024-01-01T00:00:00Z"}
                """.formatted(jobId);
        given(idempotencyService.get(any(), any())).willReturn(cachedJson);

        mockMvc.perform(post("/jobs")
                        .header("Authorization", "Bearer " + userToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "sleep", "payload": {"seconds": 5}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(jobId))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getJob_ownJob_success() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/jobs")
                        .header("Authorization", "Bearer " + userToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "sleep", "payload": {"seconds": 5}}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String jobId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(get("/jobs/" + jobId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId))
                .andExpect(jsonPath("$.type").value("sleep"));
    }

    @Test
    void getJob_otherUsersJob_returns404() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/jobs")
                        .header("Authorization", "Bearer " + userToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "sleep", "payload": {"seconds": 5}}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String jobId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(get("/jobs/" + jobId)
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void getJob_nonExistent_returns404() throws Exception {
        mockMvc.perform(get("/jobs/00000000-0000-0000-0000-000000000000")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void listJobs_returnsOnlyOwnJobs() throws Exception {
        String requestJson = """
                {"type": "sleep", "payload": {"seconds": 5}}
                """;

        mockMvc.perform(post("/jobs")
                        .header("Authorization", "Bearer " + userToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/jobs")
                        .header("Authorization", "Bearer " + userToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/jobs")
                        .header("Authorization", "Bearer " + otherUserToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/jobs")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        mockMvc.perform(get("/jobs")
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void listJobs_noJobs_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/jobs")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
