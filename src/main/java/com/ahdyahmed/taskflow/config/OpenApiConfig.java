package com.ahdyahmed.taskflow.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Day 17. One bean, not a {@code springdoc.*} block in {@code application.yml}
 * for everything: the security scheme in particular needs to be a real
 * object springdoc can reference from every operation, which the
 * properties-file approach doesn't support as directly.
 * <p>
 * The one thing worth understanding about {@link #BEARER_SCHEME_NAME}:
 * declaring it here with {@code addSecurityItem(...)} makes it the
 * <b>default</b> for every operation in the API — which is correct for
 * {@code ProjectController}/{@code TaskController}/{@code AdminUserController},
 * all of which do require a token, but wrong for {@code AuthController},
 * none of whose endpoints do (see {@code SecurityConfig}'s
 * {@code PUBLIC_AUTH_ENDPOINTS} — every one of them is on that list).
 * {@code AuthController} carries a class-level {@code @SecurityRequirements}
 * (empty) specifically to opt back out of this default, so Swagger UI
 * doesn't show a misleading padlock on {@code /auth/login} itself.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI taskflowOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("TaskFlow — Auth & Authorization Service")
                        .description("""
                                A mini Jira/Trello backend built to demonstrate JWT authentication, \
                                role-based access control, and ownership-based authorization in Spring \
                                Boot. Project 2 of a 3-project backend portfolio.

                                Most endpoints below require a Bearer access token — click **Authorize** \
                                and paste the `accessToken` from a `POST /auth/login` (or `/auth/register` \
                                + `/auth/verify` for a brand-new account) response. The `/auth/*` endpoints \
                                themselves are the exception: they're reachable with no token by design, \
                                since you can't require a login to log in.""")
                        .version("v1 (Day 17)")
                        .contact(new Contact()
                                .name("TaskFlow")
                                .url("https://github.com/AhdyAhmed/taskflow-auth-service")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME_NAME, new SecurityScheme()
                                .name(BEARER_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("""
                                        A TaskFlow access token (not the refresh token) — the short-lived \
                                        (15-minute) one returned as `accessToken` from login/register/refresh. \
                                        Paste just the token itself; Swagger UI adds the `Bearer ` prefix.""")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME));
    }
}
