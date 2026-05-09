package com.josh.interviewj.auth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InviteCodeFormatResponse {

    private int length;
    private String displayPattern;
    private boolean caseSensitive;
    private boolean allowWhitespace;
    private boolean allowHyphen;
}
