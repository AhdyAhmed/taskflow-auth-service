package com.ahdyahmed.taskflow.email;

/**
 * Day 12. Deliberately just {@code send(to, subject, body)} — transport
 * only, no knowledge of verification tokens, reset links, or any other
 * content this project will ever send. {@code AuthService} builds the
 * subject/body; this interface's only job is delivering it. That split
 * is what makes swapping {@link LoggingEmailService} for a real SMTP
 * (or SES, or Postmark, ...) implementation later a one-class change —
 * nothing about content-building has to move or change shape to make
 * that swap.
 */
public interface EmailService {

    void send(String to, String subject, String body);
}
