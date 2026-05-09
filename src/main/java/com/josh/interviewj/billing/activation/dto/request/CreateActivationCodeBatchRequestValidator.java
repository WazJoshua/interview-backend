package com.josh.interviewj.billing.activation.dto.request;

import com.josh.interviewj.billing.activation.model.ActivationCodeType;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CreateActivationCodeBatchRequestValidator
        implements ConstraintValidator<ValidActivationCodePayload, CreateActivationCodeBatchRequest> {

    @Override
    public boolean isValid(CreateActivationCodeBatchRequest value, ConstraintValidatorContext context) {
        if (value == null || value.getCodeType() == null) {
            return true;
        }

        if (value.getCodeType() == ActivationCodeType.SUBSCRIPTION) {
            boolean valid = true;
            if (value.getBillingPlanVersionId() == null) {
                addViolation(context, "billingPlanVersionId",
                        "SUBSCRIPTION type requires billingPlanVersionId and subscriptionDurationDays");
                valid = false;
            }
            if (value.getSubscriptionDurationDays() == null || value.getSubscriptionDurationDays() <= 0) {
                addViolation(context, "subscriptionDurationDays",
                        "SUBSCRIPTION type requires billingPlanVersionId and subscriptionDurationDays");
                valid = false;
            }
            return valid;
        }

        if (value.getCodeType() == ActivationCodeType.CREDIT
                && (value.getCreditAmountMicros() == null || value.getCreditAmountMicros() <= 0)) {
            addViolation(context, "creditAmountMicros", "CREDIT type requires creditAmountMicros > 0");
            return false;
        }

        return true;
    }

    private static void addViolation(ConstraintValidatorContext context, String field, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message)
                .addPropertyNode(field)
                .addConstraintViolation();
    }
}
