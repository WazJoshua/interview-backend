package com.josh.interviewj.interview.llm.prompt;

import com.josh.interviewj.common.enums.ContentLocale;

/**
 * Prompt templates for interview question generation.
 */
public final class InterviewQuestionGenerationPrompts {

    private InterviewQuestionGenerationPrompts() {
    }

    public static String buildSystemPrompt(ContentLocale locale) {
        String language = locale.getPromptLanguage();
        return """
                You are an expert technical interviewer creating interview questions.
                Generate questions in %s language.
                Return ONLY valid JSON matching the required schema.
                Do not include markdown formatting or explanatory text.
                """.formatted(language);
    }

    public static String buildMainQuestionUserPrompt(
            String jobTitle,
            String jobDescription,
            String resumeContent,
            java.util.List<com.josh.interviewj.interview.support.InterviewQuestionBlueprintFactory.QuestionBlueprint> blueprints,
            ContentLocale locale
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate interview questions for the following position:\n\n");
        prompt.append("Job Title: ").append(jobTitle).append("\n");

        if (jobDescription != null && !jobDescription.isBlank()) {
            prompt.append("\nJob Description:\n").append(jobDescription).append("\n");
        }

        if (resumeContent != null && !resumeContent.isBlank()) {
            prompt.append("\nCandidate's Resume Summary:\n").append(truncate(resumeContent, 4000)).append("\n");
        }

        if (blueprints != null && !blueprints.isEmpty()) {
            prompt.append("\nQuestion Blueprints:\n");
            for (com.josh.interviewj.interview.support.InterviewQuestionBlueprintFactory.QuestionBlueprint blueprint : blueprints) {
                prompt.append("- sequenceNumber=")
                        .append(blueprint.sequenceNumber())
                        .append(", questionType=")
                        .append(blueprint.questionType())
                        .append(", difficulty=")
                        .append(blueprint.difficulty())
                        .append(", estimatedMinutes=")
                        .append(blueprint.estimatedMinutes())
                        .append(", questionGoal=")
                        .append(blueprint.questionGoal())
                        .append(", focusHint=")
                        .append(blueprint.focusHint())
                        .append("\n");
            }
        }

        prompt.append("\nGenerate questions in ").append(locale.getPromptLanguage()).append(" language.\n");
        prompt.append("""

                Return a JSON object with this structure:
                {
                  "questions": [
                    {
                      "sequenceNumber": 1,
                      "questionContent": "The question text",
                      "focusHint": "Optional context for the question"
                    }
                  ]
                }

                Requirements:
                - Use the provided blueprints as fixed slots
                - Return one item for each blueprint slot
                - sequenceNumber must match the blueprint slot exactly
                - Only write natural wording and optional focusHint
                - Do not invent questionType, difficulty, or estimatedMinutes in the output
                - focusHint is optional but recommended for context
                """);

        return prompt.toString();
    }

    public static String buildFollowUpUserPrompt(
            String mainQuestion,
            String answer,
            String followUpIntent,
            int branchDepth,
            String jobTitle,
            String jobDescription,
            String resumeContent,
            ContentLocale locale
    ) {
        StringBuilder prompt = new StringBuilder("""
                Generate a follow-up question based on the candidate's answer.

                Original Question:
                """.formatted(mainQuestion));
        prompt.append(mainQuestion).append("\n\n");
        prompt.append("""

                Candidate's Answer:
                """);
        prompt.append(truncate(answer, 2000)).append("\n\n");
        prompt.append("Follow-up Intent: ").append(followUpIntent).append("\n");
        prompt.append("Follow-up Depth: ").append(branchDepth).append("\n");
        if (jobTitle != null && !jobTitle.isBlank()) {
            prompt.append("Job Title: ").append(jobTitle).append("\n");
        }
        if (jobDescription != null && !jobDescription.isBlank()) {
            prompt.append("Job Description: ").append(truncate(jobDescription, 1200)).append("\n");
        }
        if (resumeContent != null && !resumeContent.isBlank()) {
            prompt.append("Resume Summary: ").append(truncate(resumeContent, 1500)).append("\n");
        }
        prompt.append("""

                Generate the follow-up question in %s language.

                Return a JSON object with this structure:
                {
                  "questionContent": "The follow-up question text",
                  "focusHint": "Why this follow-up is relevant"
                }

                Requirements:
                - questionContent is required
                - focusHint is optional but recommended
                - Respect the provided follow-up intent
                - Avoid repeating the original question
                """.formatted(locale.getPromptLanguage()));
        return prompt.toString();
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
