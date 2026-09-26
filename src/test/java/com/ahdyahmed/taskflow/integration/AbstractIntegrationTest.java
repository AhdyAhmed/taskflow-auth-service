package com.ahdyahmed.taskflow.integration;

import com.ahdyahmed.taskflow.domain.entity.User;
import com.ahdyahmed.taskflow.domain.enums.Role;
import com.ahdyahmed.taskflow.dto.request.LoginRequest;
import com.ahdyahmed.taskflow.repository.ProjectRepository;
import com.ahdyahmed.taskflow.repository.TaskRepository;
import com.ahdyahmed.taskflow.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Day 15. Base class for every {@code @SpringBootTest} + {@code MockMvc}
 * security integration test — the real filter chain (rate limiter, JWT
 * filter, {@code SecurityConfig}'s entry point/access-denied handler), a
 * real {@code DispatcherServlet}, and a real Postgres behind it, all
 * exercised together, which is the whole point of Day 15 versus Day 14's
 * mocked-everything unit tests.
 * <p>
 * One container, started once and shared across every test class that
 * extends this (Testcontainers reuses a {@code static} container across
 * the whole JVM for a given class hierarchy) — starting a fresh Postgres
 * per test method would make this phase take minutes instead of seconds
 * for no correctness benefit, since {@code create-drop} already gives
 * each *class's* tests a clean schema.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected ProjectRepository projectRepository;

    @Autowired
    protected TaskRepository taskRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    /**
     * Persists an already-verified, never-locked user directly through
     * the repository rather than through {@code POST /auth/register} +
     * {@code GET /auth/verify}: those two endpoints are Day 12's own
     * tested surface, and re-walking them here for every fixture would
     * make each RBAC/ownership test pay for (and depend on) the email
     * verification flow succeeding, which isn't what these tests are
     * about. Every email is suffixed with a random UUID so fixtures
     * across different test methods never collide on Postgres's unique
     * constraint, since the container's schema is shared for the whole
     * test class.
     */
    protected User createUser(String emailPrefix, String rawPassword, Role role) {
        User user = User.builder()
                .email(emailPrefix + "-" + UUID.randomUUID() + "@taskflow.test")
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(role)
                .enabled(true)
                .build();
        return userRepository.save(user);
    }

    /**
     * Logs in through the real {@code POST /auth/login} endpoint — going
     * through the actual controller/service/{@code AuthenticationManager}
     * stack, not a hand-built token — and returns just the access token,
     * which is what every RBAC/ownership test needs to put on its
     * {@code Authorization} header.
     */
    protected String loginAndGetAccessToken(String email, String rawPassword) throws Exception {
        LoginRequest request = new LoginRequest();
        request.setEmail(email);
        request.setPassword(rawPassword);

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }

    protected String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}
