package com.ahdyahmed.taskflow.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Deliberately a real {@link Constraint}, not just {@code @Size(min = 8)}
 * chained with other annotations — this is the "custom validator" piece
 * called out in the project brief, and it reads clearly at the point of
 * use ({@code @StrongPassword} on the DTO field) rather than being a pile
 * of generic annotations someone has to mentally combine.
 */
@Documented
@Constraint(validatedBy = StrongPasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongPassword {

    String message() default "Password must be at least 8 characters and include an uppercase letter, a lowercase letter, and a digit";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
