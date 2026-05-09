package com.josh.interviewj.user.dto.request;

import lombok.Data;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * Partial user profile update payload.
 */
@Data
public class UserUpdateRequest {

    private JsonNullable<String> nickname = JsonNullable.undefined();

    private JsonNullable<String> phone = JsonNullable.undefined();

    private JsonNullable<String> locale = JsonNullable.undefined();

    private JsonNullable<String> timezone = JsonNullable.undefined();
}
