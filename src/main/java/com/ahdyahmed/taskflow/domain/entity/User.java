package com.ahdyahmed.taskflow.domain.entity;

import com.ahdyahmed.taskflow.domain.enums.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * Day 2: no collection side of the Project/Task relationships is kept
 * here on purpose. A bidirectional {@code @OneToMany} back-reference on
 * User (e.g. "ownedProjects", "assignedTasks") is tempting but tends to
 * cause accidental large-collection fetches and messy
 * equals/hashCode/toString cycles. Those lookups are exposed instead as
 * explicit repository queries (see ProjectRepository/TaskRepository) —
 * slightly more code, much easier to reason about performance-wise.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class User extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Role role = Role.USER;

    /** Starts false; flipped to true once email verification (Day 12) completes. */
    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = false;

    @Column(name = "failed_login_attempts", nullable = false)
    @Builder.Default
    private int failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;
}
