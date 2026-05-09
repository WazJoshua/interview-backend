package com.josh.interviewj.ragqa.dto.response;

import java.util.List;

public record KnowledgeBaseChatSessionListResponse(
        List<KnowledgeBaseChatSessionItemResponse> content,
        int number,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        boolean empty
) {
}
