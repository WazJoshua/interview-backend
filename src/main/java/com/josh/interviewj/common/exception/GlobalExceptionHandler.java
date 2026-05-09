package com.josh.interviewj.common.exception;

import com.josh.interviewj.common.api.RequestIdContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps application exceptions to unified HTTP error responses.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Handles domain business exceptions.
     *
     * @param ex business exception
     * @return mapped error response
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex) {
        log.warn("业务异常: code={}, message={}", ex.getErrorCode(), ex.getMessage());
        
        HttpStatus httpStatus = getHttpStatusByErrorCode(ex.getErrorCode());
        ErrorResponse response = ErrorResponse.builder()
                .code(httpStatus.value())
                .message(ex.getMessage())
                .error(ErrorResponse.ErrorDetails.builder()
                        .type(ex.getErrorCode())
                        .details(ex.getDetails())
                        .build())
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .requestId(RequestIdContext.getOrCreate())
                .build();
                
        return new ResponseEntity<>(response, httpStatus);
    }

    /**
     * Handles bean validation failures from request payloads.
     *
     * @param ex validation exception
     * @return bad request response with field-level details
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        List<ErrorResponse.ErrorDetails.ErrorDetail> errorDetails = new ArrayList<>();
        
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            String errorCode = "invalid_value";
            
            errorDetails.add(ErrorResponse.ErrorDetails.ErrorDetail.builder()
                    .field(fieldName)
                    .message(errorMessage)
                    .code(errorCode)
                    .build());
        });

        log.warn("参数验证失败: {}", errorDetails);
        
        ErrorResponse response = ErrorResponse.builder()
                .code(HttpStatus.BAD_REQUEST.value())
                .message("Request validation failed")
                .error(ErrorResponse.ErrorDetails.builder()
                        .type("VALIDATION_ERROR")
                        .details(errorDetails)
                        .build())
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .requestId(RequestIdContext.getOrCreate())
                .build();
                
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles type conversion failures for path variables and request parameters.
     *
     * @param ex type mismatch exception
     * @return bad request response with field-level details
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatchException(MethodArgumentTypeMismatchException ex) {
        String fieldName = ex.getName();
        String requiredType = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown";
        String errorMessage = "Invalid value for parameter '" + fieldName + "'. Expected type: " + requiredType;

        log.warn("参数类型转换失败: field={}, value={}, requiredType={}",
                fieldName, ex.getValue(), requiredType);

        ErrorResponse.ErrorDetails.ErrorDetail errorDetail = ErrorResponse.ErrorDetails.ErrorDetail.builder()
                .field(fieldName)
                .message(errorMessage)
                .code("invalid_type")
                .build();

        ErrorResponse response = ErrorResponse.builder()
                .code(HttpStatus.BAD_REQUEST.value())
                .message("Request validation failed")
                .error(ErrorResponse.ErrorDetails.builder()
                        .type("VALIDATION_ERROR")
                        .details(List.of(errorDetail))
                        .build())
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .requestId(RequestIdContext.getOrCreate())
                .build();

        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles malformed JSON payloads, including invalid UUID literals.
     *
     * @param ex message conversion exception
     * @return bad request response with validation envelope
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败: {}", ex.getMessage());

        ErrorResponse.ErrorDetails.ErrorDetail errorDetail = ErrorResponse.ErrorDetails.ErrorDetail.builder()
                .field("requestBody")
                .message("Malformed request body")
                .code("invalid_value")
                .build();

        ErrorResponse response = ErrorResponse.builder()
                .code(HttpStatus.BAD_REQUEST.value())
                .message("Request validation failed")
                .error(ErrorResponse.ErrorDetails.builder()
                        .type("VALIDATION_ERROR")
                        .details(List.of(errorDetail))
                        .build())
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .requestId(RequestIdContext.getOrCreate())
                .build();

        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles unexpected exceptions as internal server errors.
     *
     * @param ex unexpected exception
     * @return internal error response
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("未处理的异常", ex);
        
        ErrorResponse response = ErrorResponse.builder()
                .code(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .message("Internal server error")
                .error(ErrorResponse.ErrorDetails.builder()
                        .type("INTERNAL_ERROR")
                        .build())
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .requestId(RequestIdContext.getOrCreate())
                .build();
                
        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Resolves HTTP status by business error code.
     *
     * @param errorCode business error code
     * @return mapped status
     */
    private HttpStatus getHttpStatusByErrorCode(String errorCode) {
        return switch (errorCode) {
            case "VALIDATION_ERROR" -> HttpStatus.BAD_REQUEST;
            case "AUTH_001" -> HttpStatus.UNAUTHORIZED;
            case "AUTH_002", "AUTH_003" -> HttpStatus.UNAUTHORIZED;
            case "AUTH_004", "AUTH_005" -> HttpStatus.FORBIDDEN;
            case "AUTH_006" -> HttpStatus.FORBIDDEN;
            case "AUTH_007", "AUTH_008" -> HttpStatus.BAD_REQUEST;
            case "USER_001", "USER_002" -> HttpStatus.CONFLICT;
            case "USER_003" -> HttpStatus.NOT_FOUND;
            case "USER_004" -> HttpStatus.BAD_REQUEST;
            case "USER_CREDIT_002" -> HttpStatus.CONFLICT;
            case "USER_BILLING_001" -> HttpStatus.CONFLICT;
            case "USER_BILLING_002", "USER_BILLING_003" -> HttpStatus.NOT_FOUND;
            case "USER_BILLING_004", "USER_BILLING_005", "USER_BILLING_006" -> HttpStatus.CONFLICT;
            case "PAYMENT_001" -> HttpStatus.BAD_REQUEST;
            case "PAYMENT_002" -> HttpStatus.NOT_FOUND;
            case "PAYMENT_003", "PAYMENT_004", "PAYMENT_005", ErrorCode.PAYMENT_006 -> HttpStatus.CONFLICT;
            case "INVITE_001", "INVITE_002" -> HttpStatus.BAD_REQUEST;
            case "INVITE_003", "INVITE_004" -> HttpStatus.CONFLICT;
            case "RESUME_001", "RESUME_002", "RESUME_003", "RESUME_015" -> HttpStatus.BAD_REQUEST;
            case "RESUME_004", "RESUME_006", "RESUME_009", "RESUME_011", "RESUME_014" -> HttpStatus.CONFLICT;
            case "RESUME_005", "RESUME_010", "RESUME_012", "RESUME_013" -> HttpStatus.NOT_FOUND;
            case "KB_001", "KB_002" -> HttpStatus.NOT_FOUND;
            case "KB_003" -> HttpStatus.CONFLICT;
            case "KB_004" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "INTERVIEW_001", "INTERVIEW_002" -> HttpStatus.BAD_REQUEST;
            case "INTERVIEW_003" -> HttpStatus.FORBIDDEN;
            case "INTERVIEW_004", "INTERVIEW_005" -> HttpStatus.NOT_FOUND;
            case "INTERVIEW_006", "INTERVIEW_007", "INTERVIEW_008" -> HttpStatus.CONFLICT;
            case "ADMIN_CREDIT_001" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "ADMIN_CREDIT_002", "ADMIN_CREDIT_003" -> HttpStatus.CONFLICT;
            case "ADMIN_BILLING_001" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "ADMIN_BILLING_002", "ADMIN_BILLING_004", ErrorCode.ADMIN_BILLING_005 -> HttpStatus.CONFLICT;
            case "ADMIN_BILLING_003" -> HttpStatus.NOT_FOUND;
            case "ADMIN_LLM_001" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "ADMIN_LLM_002" -> HttpStatus.NOT_FOUND;
            case ErrorCode.BILLING_ACTIVATION_001 -> HttpStatus.NOT_FOUND;
            case ErrorCode.BILLING_ACTIVATION_002 -> HttpStatus.CONFLICT;
            case ErrorCode.BILLING_ACTIVATION_003 -> HttpStatus.GONE;
            case ErrorCode.BILLING_ACTIVATION_004 -> HttpStatus.CONFLICT;
            case "FILE_001", "LLM_001" -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
    
}
