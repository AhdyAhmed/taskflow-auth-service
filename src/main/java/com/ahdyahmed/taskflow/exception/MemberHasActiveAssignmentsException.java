package com.ahdyahmed.taskflow.exception;

/**
 * Day 9: thrown when removing a project member is blocked because
 * they're still the assignee on at least one non-DONE task in that
 * project. See {@code ProjectService#removeMember} for the decision
 * this exists to enforce, and why it's a 409 (the request is well-formed,
 * but conflicts with the resource's current state) rather than a 400 or 403.
 */
public class MemberHasActiveAssignmentsException extends RuntimeException {

    public MemberHasActiveAssignmentsException(String message) {
        super(message);
    }
}
