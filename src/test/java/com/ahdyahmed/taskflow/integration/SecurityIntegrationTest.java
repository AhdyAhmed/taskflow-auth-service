package com.ahdyahmed.taskflow.integration;

import com.ahdyahmed.taskflow.config.JwtProperties;
import com.ahdyahmed.taskflow.domain.entity.Project;
import com.ahdyahmed.taskflow.domain.entity.Task;
import com.ahdyahmed.taskflow.domain.entity.User;
import com.ahdyahmed.taskflow.domain.enums.Role;
import com.ahdyahmed.taskflow.domain.enums.TaskStatus;
import com.ahdyahmed.taskflow.dto.request.LoginRequest;
import com.ahdyahmed.taskflow.dto.request.ProjectCreateRequest;
import com.ahdyahmed.taskflow.dto.request.ProjectUpdateRequest;
import com.ahdyahmed.taskflow.dto.request.TaskCreateRequest;
import com.ahdyahmed.taskflow.dto.request.TaskUpdateRequest;
import com.ahdyahmed.taskflow.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Day 15 — "the highlight" per the roadmap. Every test here goes through
 * the real {@code SecurityFilterChain} (rate limiter → JWT filter →
 * {@code DispatcherServlet}), a real controller/service stack, and a
 * real Postgres container; nothing is mocked. Day 14's unit tests proved
 * each class's own branching logic in isolation — this class proves the
 * pieces actually add up to the right HTTP status code and JSON body
 * when wired together for real, which is a genuinely different failure
 * mode (e.g. a bean name typo in a {@code @PreAuthorize} SpEL expression
 * compiles fine and is invisible to any mocked unit test, but breaks
 * every request through it).
 * <p>
 * Fixtures are built fresh per test method (fresh random-suffixed users,
 * via {@link AbstractIntegrationTest#createUser}) rather than shared
 * across the class, so no test's pass/fail depends on method execution
 * order — deliberate, given JUnit doesn't guarantee an order without
 * extra configuration this project has no other reason to add.
 * <p>
 * <b>Rate-limit budget, worth knowing before adding more tests here:</b>
 * {@code RateLimitingFilter} is a real singleton bean in this shared
 * {@code ApplicationContext}, not mocked out — every {@code POST
 * /auth/login} across every test in this class draws from the same
 * (IP, path) bucket, {@code app.security.rate-limit.capacity = 20} per
 * 60-second window. This class currently makes ~16 login calls in
 * total, comfortably under that, but a future test adding several more
 * logins could start tripping 429s that look like unrelated failures.
 * If that happens, the fix is a test-profile override raising the
 * capacity in {@code application-test.yml}, not disabling the filter —
 * the whole point of this phase is exercising it for real.
 */
class SecurityIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Sup3rSecretPassword";

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JwtProperties jwtProperties;

    // ---- Unauthenticated access -------------------------------------

    @Nested
    class UnauthenticatedAccess {

        @Test
        void readingAProject_withNoAuthorizationHeader_returns401() throws Exception {
            User manager = createUser("manager", PASSWORD, Role.MANAGER);
            Project project = projectRepository.save(
                    Project.builder().name("No Token Project").owner(manager).build());

            mockMvc.perform(get("/api/projects/{id}", project.getId()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.message").value("Authentication is required"));
        }

        @Test
        void creatingAProject_withNoAuthorizationHeader_returns401() throws Exception {
            ProjectCreateRequest request = new ProjectCreateRequest();
            request.setName("Should Never Exist");

            mockMvc.perform(post("/api/projects")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ---- Role-based access control -----------------------------------

    @Nested
    class RoleBasedAccess {

        @Test
        void userRole_cannotCreateProject_returns403() throws Exception {
            User user = createUser("user", PASSWORD, Role.USER);
            String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);

            ProjectCreateRequest request = new ProjectCreateRequest();
            request.setName("A Project A USER Should Not Be Able To Make");

            // Correct token, valid session, wrong role entirely —
            // @PreAuthorize("hasRole('MANAGER')") on ProjectService.create
            // rejects this before the method body (and therefore the DB)
            // is ever touched.
            mockMvc.perform(post("/api/projects")
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Access is denied"));
        }

        @Test
        void userRole_cannotCreateTask_evenAsProjectMember_returns403() throws Exception {
            User manager = createUser("manager", PASSWORD, Role.MANAGER);
            User member = createUser("member", PASSWORD, Role.USER);

            Project project = projectRepository.save(
                    Project.builder().name("Members Only").owner(manager).build());
            project.getMembers().add(member);
            projectRepository.save(project);

            String memberToken = loginAndGetAccessToken(member.getEmail(), PASSWORD);

            TaskCreateRequest request = new TaskCreateRequest();
            request.setTitle("A task a plain member should not be able to create");
            request.setProjectId(project.getId());

            // Being a genuine member of the project isn't enough on its
            // own — TaskService.create requires hasRole('MANAGER') *and*
            // membership, so a USER-role member still gets 403 regardless
            // of how legitimately they belong to the project.
            mockMvc.perform(post("/api/tasks")
                            .header("Authorization", bearer(memberToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }
    }

    // ---- Ownership / membership rules ---------------------------------

    @Nested
    class OwnershipRules {

        @Test
        void managerWhoIsNotOwner_cannotUpdateProject_returns403() throws Exception {
            User owner = createUser("owner", PASSWORD, Role.MANAGER);
            User otherManager = createUser("other-manager", PASSWORD, Role.MANAGER);

            Project project = projectRepository.save(
                    Project.builder().name("Owner's Project").owner(owner).build());

            String otherManagerToken = loginAndGetAccessToken(otherManager.getEmail(), PASSWORD);

            ProjectUpdateRequest request = new ProjectUpdateRequest();
            request.setName("Renamed By Someone Who Shouldn't Be Able To");

            // Correct role (MANAGER), valid token, just not *this*
            // project's owner — @projectSecurity.isOwner is what's
            // actually gating this, not the role check.
            mockMvc.perform(put("/api/projects/{id}", project.getId())
                            .header("Authorization", bearer(otherManagerToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void projectOwner_canUpdateProject_returns200() throws Exception {
            User owner = createUser("owner", PASSWORD, Role.MANAGER);
            Project project = projectRepository.save(
                    Project.builder().name("Original Name").owner(owner).build());

            String ownerToken = loginAndGetAccessToken(owner.getEmail(), PASSWORD);

            ProjectUpdateRequest request = new ProjectUpdateRequest();
            request.setName("Renamed By Its Actual Owner");
            request.setDescription("Updated by the owner, who is allowed to.");

            mockMvc.perform(put("/api/projects/{id}", project.getId())
                            .header("Authorization", bearer(ownerToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Renamed By Its Actual Owner"));
        }

        @Test
        void nonMember_cannotReadProject_returns403() throws Exception {
            User owner = createUser("owner", PASSWORD, Role.MANAGER);
            User outsider = createUser("outsider", PASSWORD, Role.USER);
            Project project = projectRepository.save(
                    Project.builder().name("Private Project").owner(owner).build());

            String outsiderToken = loginAndGetAccessToken(outsider.getEmail(), PASSWORD);

            // Not a role problem (any authenticated USER can read
            // *projects they belong to*) — this 403 is purely about not
            // being a member of this specific project.
            mockMvc.perform(get("/api/projects/{id}", project.getId())
                            .header("Authorization", bearer(outsiderToken)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void taskAssignee_whoIsNeitherOwnerNorCreator_canStillUpdateTheirOwnTask_returns200() throws Exception {
            User owner = createUser("owner", PASSWORD, Role.MANAGER);
            User assignee = createUser("assignee", PASSWORD, Role.USER);

            Project project = projectRepository.save(
                    Project.builder().name("Assignment Project").owner(owner).build());
            project.getMembers().add(assignee);
            projectRepository.save(project);

            Task task = taskRepository.save(Task.builder()
                    .title("Do the thing")
                    .status(TaskStatus.TODO)
                    .project(project)
                    .createdBy(owner)
                    .assignee(assignee)
                    .build());

            String assigneeToken = loginAndGetAccessToken(assignee.getEmail(), PASSWORD);

            TaskUpdateRequest request = new TaskUpdateRequest();
            request.setTitle("Do the thing (in progress)");
            request.setStatus(TaskStatus.IN_PROGRESS);

            // @taskSecurity.isOwnerOrAssignee: the assignee owns neither
            // the task's project nor the task record itself, but being
            // the assignee is independently sufficient to update it.
            mockMvc.perform(put("/api/tasks/{id}", task.getId())
                            .header("Authorization", bearer(assigneeToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        }

        @Test
        void taskAssignee_cannotDeleteTheTask_onlyProjectOwnerOrAdminCan_returns403() throws Exception {
            User owner = createUser("owner", PASSWORD, Role.MANAGER);
            User assignee = createUser("assignee", PASSWORD, Role.USER);

            Project project = projectRepository.save(
                    Project.builder().name("Delete Test Project").owner(owner).build());
            project.getMembers().add(assignee);
            projectRepository.save(project);

            Task task = taskRepository.save(Task.builder()
                    .title("Do not let the assignee delete this")
                    .status(TaskStatus.TODO)
                    .project(project)
                    .createdBy(owner)
                    .assignee(assignee)
                    .build());

            String assigneeToken = loginAndGetAccessToken(assignee.getEmail(), PASSWORD);

            // Deliberately narrower than update: TaskSecurity#isProjectOwner
            // gates delete, not isOwnerOrAssignee — being the assignee is
            // enough to update your own task, not enough to delete it.
            mockMvc.perform(delete("/api/tasks/{id}", task.getId())
                            .header("Authorization", bearer(assigneeToken)))
                    .andExpect(status().isForbidden());
        }
    }

    // ---- Account lockout ----------------------------------------------

    @Nested
    class AccountLockout {

        @Test
        void accountLocksAfterFiveFailedAttempts_andThenRejectsTheCorrectPasswordToo() throws Exception {
            User user = createUser("lockout-target", PASSWORD, Role.USER);

            LoginRequest wrongPassword = new LoginRequest();
            wrongPassword.setEmail(user.getEmail());
            wrongPassword.setPassword("TheWrongPassword1");

            // app.security.lockout.max-failed-attempts is 5 (application.yml)
            // — the 5th wrong attempt is the one that crosses the
            // threshold and actually locks the account.
            for (int attempt = 1; attempt <= 5; attempt++) {
                mockMvc.perform(post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(wrongPassword)))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.message").value("Invalid email or password"));
            }

            LoginRequest correctPassword = new LoginRequest();
            correctPassword.setEmail(user.getEmail());
            correctPassword.setPassword(PASSWORD);

            // The account is now locked. The whole point of Day 10's
            // design (AppUserPrincipal#isAccountNonLocked runs in
            // PreAuthenticationChecks, before the password is compared)
            // is that even the *correct* password is rejected here — and
            // with the exact same generic message as a wrong password,
            // never revealing that the account is specifically locked.
            mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(correctPassword)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Invalid email or password"));
        }
    }

    // ---- Token validation -----------------------------------------------

    @Nested
    class TokenValidation {

        @Test
        void tamperedAccessToken_isRejected_returns401() throws Exception {
            User user = createUser("tamper-target", PASSWORD, Role.USER);
            String token = loginAndGetAccessToken(user.getEmail(), PASSWORD);

            char lastChar = token.charAt(token.length() - 1);
            char replacement = lastChar == 'A' ? 'B' : 'A';
            String tampered = token.substring(0, token.length() - 1) + replacement;

            // JwtAuthenticationFilter's isValid() check fails signature
            // verification, so the request proceeds unauthenticated —
            // anyRequest().authenticated() then rejects it with 401, the
            // same as if no header had been sent at all.
            mockMvc.perform(get("/api/projects")
                            .header("Authorization", bearer(tampered)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void expiredAccessToken_isRejected_returns401() throws Exception {
            User user = createUser("expiry-target", PASSWORD, Role.USER);

            // Same secret as the real app (so the signature itself would
            // otherwise be valid), but a 1ms expiration — isolates
            // "expired" from "tampered"/"wrong key", which are already
            // covered by other tests here and in Day 14's JwtServiceTest.
            JwtService shortLivedJwtService = new JwtService(
                    new JwtProperties(jwtProperties.secret(), 1L, 1L));
            String expiredToken = shortLivedJwtService.generateAccessToken(user.getEmail());
            Thread.sleep(20);

            mockMvc.perform(get("/api/projects")
                            .header("Authorization", bearer(expiredToken)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void refreshTokenPresentedAsAnAccessToken_isRejected_returns401() throws Exception {
            User user = createUser("wrong-type-target", PASSWORD, Role.USER);
            // A syntactically valid, correctly-signed, unexpired token —
            // just the wrong *kind*. JwtAuthenticationFilter explicitly
            // checks isAccessToken(), not just isValid(), specifically so
            // a leaked refresh token can't be replayed as an API credential.
            String refreshToken = jwtService.generateRefreshToken(user.getEmail());

            mockMvc.perform(get("/api/projects")
                            .header("Authorization", bearer(refreshToken)))
                    .andExpect(status().isUnauthorized());
        }
    }
}
