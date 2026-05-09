package com.josh.interviewj.billing.activation.dto.request;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {
        CreateActivationCodeRequestValidator.class,
        CreateActivationCodeBatchRequestValidator.class
})
@Documented
public @interface ValidActivationCodePayload {

    String message() default "Invalid activation code payload";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
