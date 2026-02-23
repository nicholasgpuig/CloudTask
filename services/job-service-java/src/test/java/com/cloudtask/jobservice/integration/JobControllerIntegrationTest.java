package com.cloudtask.jobservice.integration;

import com.cloudtask.jobservice.dto.AuthResponse;
import com.cloudtask.jobservice.dto.CreateJobRequest;
import com.cloudtask.jobservice.dto.RegisterRequest;
import com.cloudtask.jobservice.messaging.JobPublisher;
import com.cloudtask.jobservice.model.Job;
import com.cloudtask.jobservice.repository.JobRepository;
import com.cloudtask.jobservice.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
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

        // Create first user and get token
        userToken = registerAndGetToken("user1@example.com", "password123");

        // Create second user and get token
        otherUserToken = registerAndGetToken("user2@example.com", "password123");
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
        var request = new CreateJobRequest("sleep", "{\"seconds\":5}");

        mockMvc.perform(post("/jobs")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.type").value("sleep"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // Verify job was published to RabbitMQ
        verify(jobPublisher).publishJobCreated(any(Job.class));
    }

    @Test
    void createJob_noToken_returns403() throws Exception {
        var request = new CreateJobRequest("sleep", "{\"seconds\":5}");

        mockMvc.perform(post("/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createJob_invalidToken_returns401() throws Exception {
        var request = new CreateJobRequest("sleep", "{\"seconds\":5}");

        mockMvc.perform(post("/jobs")
                        .header("Authorization", "Bearer invalid.token.here")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getJob_ownJob_success() throws Exception {
        // Create a job first
        var createRequest = new CreateJobRequest("sleep", "{\"seconds\":5}");
        MvcResult createResult = mockMvc.perform(post("/jobs")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        String jobId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asText();

        // Fetch the job
        mockMvc.perform(get("/jobs/" + jobId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId))
                .andExpect(jsonPath("$.type").value("sleep"));
    }

    @Test
    void getJob_otherUsersJob_returns404() throws Exception {
        // User1 creates a job
        var createRequest = new CreateJobRequest("sleep", "{\"seconds\":5}");
        MvcResult createResult = mockMvc.perform(post("/jobs")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        String jobId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asText();

        // User2 tries to fetch User1's job - should get 404 (not 403, to prevent enumeration)
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
        // User1 creates 2 jobs
        var request = new CreateJobRequest("sleep", "{\"seconds\":5}");
        mockMvc.perform(post("/jobs")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/jobs")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // User2 creates 1 job
        mockMvc.perform(post("/jobs")
                        .header("Authorization", "Bearer " + otherUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // User1 should only see their 2 jobs
        mockMvc.perform(get("/jobs")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // User2 should only see their 1 job
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
