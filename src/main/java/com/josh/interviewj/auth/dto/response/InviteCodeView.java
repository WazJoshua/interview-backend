package com.josh.interviewj.auth.dto.response;

import com.josh.interviewj.auth.model.InviteCodeStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class InviteCodeView {

    private UUID id;
    private String code;
    private InviteCodeStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime usedAt;
    private InviteCodeActorView createdBy;
    private InviteCodeActorView usedBy;
}
