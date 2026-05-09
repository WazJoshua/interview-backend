package com.josh.interviewj.interview.llm.dto;

import java.util.List;
import java.util.Map;

/**
 * Payload for LLM-generated interview report.
 */
public record InterviewReportPayload(
        String summary,
        List<String> strengths,
        List<String> weaknesses,
        List<String> improvementSuggestions,
        Map<String, SkillAssessment> skillAssessment
) {
    public record SkillAssessment(
            String level,
            String evidence
    ) {
        public boolean isValid() {
            return level != null && isValidLevel()
                    && evidence != null && !evidence.isBlank();
        }

        private boolean isValidLevel() {
            return level.equals("beginner")
                    || level.equals("intermediate")
                    || level.equals("advanced")
                    || level.equals("expert");
        }
    }

    public boolean isValid() {
        return summary != null && !summary.isBlank()
                && strengths != null && !strengths.isEmpty()
                && weaknesses != null && !weaknesses.isEmpty();
    }
}