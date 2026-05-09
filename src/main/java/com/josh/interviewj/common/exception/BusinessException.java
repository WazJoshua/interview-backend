package com.josh.interviewj.common.exception;

import lombok.Getter;

import java.util.List;

/**
 * Domain-level exception carrying a stable business error code.
 */
@Getter
public class BusinessException extends RuntimeException {
    private final String errorCode;
    private final List<ErrorResponse.ErrorDetails.ErrorDetail> details;

    /**
     * Creates a business exception without nested cause.
     *
     * @param errorCode stable error code
     * @param message safe message for clients
     */
    public BusinessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.details = null;
    }

    /**
     * Creates a business exception with nested cause.
     *
     * @param errorCode stable error code
     * @param message safe message for clients
     * @param cause original cause
     */
    public BusinessException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = null;
    }

    /**
     * Creates a business exception with structured field-level details.
     *
     * @param errorCode stable error code
     * @param message safe message for clients
     * @param details optional field-level details
     */
    public BusinessException(String errorCode, String message, List<ErrorResponse.ErrorDetails.ErrorDetail> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }
}
