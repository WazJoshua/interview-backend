package com.josh.interviewj.auth.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminUserRoleFlagsResponse {

    private String userId;
    private List<String> roles;
    private Flags flags;

    @Data
    @Builder
    public static class Flags {
        private Boolean inviter;
    }
}
