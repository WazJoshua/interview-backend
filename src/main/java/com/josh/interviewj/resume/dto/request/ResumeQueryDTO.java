package com.josh.interviewj.resume.dto.request;

import lombok.Data;

/**
 * Query parameters for paginated resume listing.
 */
@Data
public class ResumeQueryDTO {

    private Integer page = 0;

    private Integer size = 20;

    private String sort = "createdAt,desc";

    private String status;
}
