package com.josh.interviewj.auth.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class UpdateUserRoleFlagsRequest {

    @NotNull
    private Boolean inviter;

    @JsonIgnore
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    @AssertTrue(message = "Only inviter flag is supported")
    public boolean isStrictShape() {
        return unknownFields.isEmpty();
    }
}
