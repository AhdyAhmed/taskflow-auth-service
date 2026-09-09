package com.ahdyahmed.taskflow.domain.enums;

/**
 * Application-level roles used for role-based access control (wired up on Day 7).
 * <p>
 * ADMIN    - full access, manages users
 * MANAGER  - creates/owns projects, assigns tasks
 * USER     - views and updates tasks assigned to them
 */
public enum Role {
    ADMIN,
    MANAGER,
    USER
}
