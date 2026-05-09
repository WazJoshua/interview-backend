package com.josh.interviewj.interview.llm.prompt;

import com.josh.interviewj.common.enums.ContentLocale;

import java.math.BigDecimal;

/**
 * Prompt templates for interview report generation.
 */
public final class InterviewReportPrompts {

    private InterviewReportPrompts() {
    }

    public static String buildSystemPrompt(ContentLocale locale) {
        String language = locale.getPromptLanguage();
        return """
                You are an expert technical interviewer generating interview reports.
                Write reports in %s language.
                Return ONLY valid JSON matching the required schema.
                Do not include markdown formatting or explanatory text.
                """.formatted(language);
    }

    public static String buildReportUserPrompt(
            String jobTitle,
            String jobDescription,
            String questionsSummary,
            String answersSummary,
            BigDecimal runningScore,
            BigDecimal overallScore,
            String completionReason,
            String branchSummary,
            ContentLocale locale
    ) {
        return """
                Generate an interview report based on the following interview data.

                Job Title: %s

                Job Description:
                %s

                Questions:
                %s

                Answers:
                %s

                Branch Summary:
                %s

                Running Score: %s

                Overall Score: %s

                Completion Reason: %s

                Write the report in %s language.

                Return a JSON object with this structure:
                {
                  "summary": "2-3 sentence overall assessment of the candidate",
                  "strengths": ["strength1", "strength2", ...],
                  "weaknesses": ["weakness1", "weakness2", ...],
                  "improvementSuggestions": ["suggestion1", "suggestion2", ...],
                  "skillAssessment": {
                    "skillName": {
                      "level": "beginner|intermediate|advanced|expert",
                      "evidence": "brief evidence from the interview"
                    }
                  }
                }

                Requirements:
                - summary is required and should be 2-3 sentences
                - strengths and weaknesses should each have 2-4 items
                - improvementSuggestions should have 2-3 actionable items
                - skillAssessment should cover 3-5 key skills relevant to the position
                - Be specific and provide evidence-based assessments
                - Consider the running score when assessing overall performance
                """.formatted(
                        jobTitle,
                        truncate(jobDescription, 1000),
                        truncate(questionsSummary, 3000),
                        truncate(answersSummary, 3000),
                        truncate(branchSummary, 3000),
                        runningScore != null ? runningScore.toString() : "N/A",
                        overallScore != null ? overallScore.toString() : "N/A",
                        completionReason != null ? completionReason : "N/A",
                        locale.getPromptLanguage()
                );
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
