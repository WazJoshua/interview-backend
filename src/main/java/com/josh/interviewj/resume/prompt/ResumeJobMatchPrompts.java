package com.josh.interviewj.resume.prompt;

/**
 * Prompt templates for resume × JD matching.
 */
public final class ResumeJobMatchPrompts {

    private ResumeJobMatchPrompts() {
    }

    public static final String PROMPT_VERSION = "RESUME-JOB-MATCH-v1.0";

    public static final String SYSTEM_PROMPT = """
        You are a Resume-Job Match Agent.

        You MUST ONLY use the provided resume evidence and job description.
        Do NOT fabricate facts, degrees, employers, or years of experience.

        OUTPUT FORMAT:
        Return ONLY valid JSON. No markdown, no code fences, no extra commentary.

        OUTPUT JSON SCHEMA (minimum fields):
        {
          "matchScore": 0-100,
          "summary": "string",
          "strengths": ["string"],
          "gaps": ["string"],
          "suggestions": ["string"]
        }
        """;

    /**
     * Builds the user prompt that combines normalized resume evidence with the target JD.
     *
     * @param evidenceJson structured resume evidence
     * @param jobTitle target job title
     * @param jobDescription target job description
     * @return formatted user prompt
     */
    public static String buildUserPrompt(String evidenceJson, String jobTitle, String jobDescription, String contentLocale) {
        return """
            TASK: Compare resume evidence against the target job and produce match JSON.

            RESUME EVIDENCE (between delimiters):
            BEGIN_EVIDENCE
            %s
            END_EVIDENCE

            TARGET JOB TITLE (between delimiters):
            BEGIN_JOB_TITLE
            %s
            END_JOB_TITLE

            TARGET JOB DESCRIPTION (between delimiters):
            BEGIN_JOB_DESCRIPTION
            %s
            END_JOB_DESCRIPTION

            INSTRUCTIONS:
            1. Produce matchScore between 0 and 100.
            2. Provide 3-6 strengths and 3-6 gaps, each as a short sentence.
            3. Provide 3-6 actionable suggestions.
            4. Keep summary concise (2-4 sentences).
            5. %s
            """.formatted(
                evidenceJson == null ? "" : evidenceJson,
                jobTitle == null ? "" : jobTitle,
                jobDescription == null ? "" : jobDescription,
                ContentLocalePromptSupport.buildUserFacingOutputInstruction(
                        contentLocale,
                        "summary, strengths, gaps, and suggestions"
                )
        );
    }
}
