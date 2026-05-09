package com.josh.interviewj.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Standard error response envelope.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    private Integer code;
    private String message;
    private ErrorDetails error;
    private OffsetDateTime timestamp;
    private String requestId;

    /**
     * Structured error details including type and optional field-level issues.
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorDetails {
        private String type;
        private List<ErrorDetail> details;

        /**
         * Field-level validation or semantic error detail.
         */
        @Data
        @Builder
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class ErrorDetail {
            private String field;
            private String message;
            private String code;
        }
    }
}
