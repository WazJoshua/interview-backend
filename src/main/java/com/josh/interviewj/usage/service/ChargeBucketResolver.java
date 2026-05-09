package com.josh.interviewj.usage.service;

import com.josh.interviewj.usage.model.ChargeBucket;
import org.springframework.stereotype.Component;

@Component
public class ChargeBucketResolver {

    public ChargeBucket resolve(String purpose) {
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("purpose must not be blank");
        }
        return switch (purpose) {
            case "parse", "analysis" -> ChargeBucket.RESUME_CREDITS;
            case "rag", "kb_query_rewrite", "kb_query_embedding", "kb_query_rerank" -> ChargeBucket.KB_QUERY_CREDITS;
            case "kb_document_embedding" -> ChargeBucket.KB_INGESTION_CREDITS;
            case "interview_question_generation",
                 "interview_follow_up_generation",
                 "interview_answer_evaluation",
                 "interview_report_generation" -> ChargeBucket.INTERVIEW_CREDITS;
            default -> throw new IllegalArgumentException("Unsupported purpose for charge bucket mapping: " + purpose);
        };
    }
}
