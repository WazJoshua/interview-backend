package com.josh.interviewj.interview.llm.prompt;

import com.josh.interviewj.common.enums.ContentLocale;

/**
 * Prompt templates for interview answer evaluation.
 */
public final class InterviewEvaluationPrompts {

    private InterviewEvaluationPrompts() {
    }

    public static String buildSystemPrompt(ContentLocale locale) {
        String language = locale.getPromptLanguage();
        return """
                You are an expert technical interviewer evaluating candidate answers.
                Provide evaluations in %s language.
                Return ONLY valid JSON matching the required schema.
                Do not include markdown formatting or explanatory text.
                """.formatted(language);
    }

    public static String buildEvaluationUserPrompt(
            String question,
            String answer,
            String jobTitle,
            ContentLocale locale
    ) {
        return """
                Evaluate the following interview answer.

                Job Title: %s

                Question:
                %s

                Candidate's Answer:
                %s

                Provide evaluation in %s language.

                Return a JSON object with this structure:
                {
                  "answerRelevance": 0-5,
                  "specificity": 0-5,
                  "reasoning": 0-5,
                  "technicalJudgment": 0-5,
                  "communication": 0-5,
                  "overallComment": "A brief comment summarizing the evaluation",
                  "evidence": ["brief evidence 1"],
                  "risks": ["brief risk 1"]
                }

                Requirements:
                - Each score must be an integer between 0 and 5
                - 0 = Missing, 1 = Poor, 2 = Below Average, 3 = Average, 4 = Good, 5 = Excellent
                - overallComment should be 1-2 sentences
                - evidence and risks must always be arrays and may be empty
                - Be fair and objective in your evaluation
                """.formatted(jobTitle, question, truncate(answer, 3000), locale.getPromptLanguage());
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
