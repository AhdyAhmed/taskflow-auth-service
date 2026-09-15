package com.ahdyahmed.taskflow.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Day 8: {@code ownerId} is gone. The Day 1/3 note that lived here said
 * this would change "once authentication exists" — it now does:
 * {@code ProjectService.create} derives the owner from the authenticated
 * principal instead of trusting a client-supplied id. Letting the client
 * name any user as owner was a privilege-escalation-adjacent bug (create
 * a project, declare someone else the owner) in the same family as the
 * one {@code RegisterRequest} avoids by not accepting a {@code role} field.
 */
@Getter
@Setter
public class ProjectCreateRequest {

    @NotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 1000)
    private String description;
}
