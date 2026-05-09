package com.josh.interviewj.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Standard API response envelope used by controllers.
 *
 * @param <T> response payload type
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private Integer code;
    private String message;
    private T data;
    private OffsetDateTime timestamp;
    private String requestId;

    /**
     * Builds a successful response with default message.
     *
     * @param data response payload
     * @param <T> payload type
     * @return success response
     */
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .code(200)
                .message("success")
                .data(data)
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .requestId(RequestIdContext.getOrCreate())
                .build();
    }

    /**
     * Builds a successful response with custom message.
     *
     * @param message response message
     * @param data response payload
     * @param <T> payload type
     * @return success response
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .code(200)
                .message(message)
                .data(data)
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .requestId(RequestIdContext.getOrCreate())
                .build();
    }

    /**
     * Builds a created response with default message.
     *
     * @param data response payload
     * @param <T> payload type
     * @return created response
     */
    public static <T> ApiResponse<T> created(T data) {
        return ApiResponse.<T>builder()
                .code(201)
                .message("created")
                .data(data)
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .requestId(RequestIdContext.getOrCreate())
                .build();
    }

    /**
     * Builds a created response with custom message.
     *
     * @param message response message
     * @param data response payload
     * @param <T> payload type
     * @return created response
     */
    public static <T> ApiResponse<T> created(String message, T data) {
        return ApiResponse.<T>builder()
                .code(201)
                .message(message)
                .data(data)
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .requestId(RequestIdContext.getOrCreate())
                .build();
    }
}
