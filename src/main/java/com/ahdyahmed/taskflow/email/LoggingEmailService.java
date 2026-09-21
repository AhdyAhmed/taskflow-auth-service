package com.ahdyahmed.taskflow.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Day 12. The only {@link EmailService} implementation for now — logs at
 * INFO instead of actually delivering anything, so the verification (and
 * later, Day 13 password-reset) flow is fully exercisable locally with
 * no SMTP account, API key, or network dependency. Swapping in a real
 * implementation later (Spring's {@code JavaMailSender}, or an SES/
 * Postmark/SendGrid SDK) means writing one new class against the same
 * {@link EmailService} interface and changing which bean is
 * {@code @Primary} — nothing in {@code AuthService} would need to change.
 */
@Component
@Slf4j
public class LoggingEmailService implements EmailService {

    @Override
    public void send(String to, String subject, String body) {
        log.info("Mock email — to: {} | subject: {} | body:\n{}", to, subject, body);
    }
}
