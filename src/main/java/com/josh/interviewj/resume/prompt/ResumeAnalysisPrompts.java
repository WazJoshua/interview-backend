package com.josh.interviewj.resume.prompt;

/**
 * Resume Analysis Prompt Templates for LLM-based resume evaluation.
 *
 * <p>Implements the RESUME-EVAL-FW-v1.1 methodology with two-stage processing:
 * Stage A extracts structured evidence; Stage B scores resume quality (no JD input).</p>
 *
 * <p>All prompts include the "JSON" keyword for Provider structured output compatibility.</p>
 *
 * @see <a href="docs/resume_modules/evaluation-methodology/resume-evaluation-framework-v1.1.md">Evaluation Methodology v1.1</a>
 */
public final class ResumeAnalysisPrompts {

    private ResumeAnalysisPrompts() {
        // Utility class - prevent instantiation
    }

    // ====================================================================================
    // CONSTANTS
    // ====================================================================================

    /**
     * Framework version identifier.
     */
    public static final String FRAMEWORK_VERSION = "RESUME-EVAL-FW-v1.1";

    /**
     * Maximum characters for raw resume text input in Stage A.
     * First 8000 chars + last 2000 chars with separator.
     */
    public static final int STAGE_A_MAX_RAW_TEXT_CHARS = 12000;

    /**
     * Maximum characters for Stage A output (ResumeEvidence).
     */
    public static final int STAGE_A_MAX_OUTPUT_CHARS = 15000;

    // ====================================================================================
    // RUBRIC SPECIFICATION (COMPACT)
    // ====================================================================================

    /**
     * Compact RubricSpec defining evaluation dimensions, weights, and constraints.
     * Based on RESUME-EVAL-FW-v1.1 methodology.
     */
    public static final String RUBRIC_SPEC = """
        RESUME EVALUATION RUBRIC (v1.1)
        ================================

        VERSION: RESUME-EVAL-FW-v1.1

        DIMENSIONS & WEIGHTS:

        1. COMPLETENESS (50%)
           Sub-dimensions:
           - personalInfo (15%): name, contact, work years
           - education (15%): school, major, duration
           - workExperience (40%): company, position, duration, responsibilities, quantified results
           - skills (20%): skill names, proficiency levels, certifications
           - projects (10%): project name, role, tools, outcomes

        2. CLARITY (50%)
           Sub-dimensions:
           - language (30%): accuracy, grammar, professionalism
           - structure (25%): organization, timeline logic, emphasis
           - quantification (25%): data usage, metrics, outcomes
           - formatting (20%): consistency, visual hierarchy, readability

        OVERALL FORMULA:
        - overall = 0.5*completeness + 0.5*clarity

        SCORING SCALE (per dimension):
        - 90-100: Excellent - exceeds expectations
        - 75-89: Good - meets expectations with minor gaps
        - 60-74: Acceptable - basic requirements met, room for improvement
        - 40-59: Below average - significant gaps
        - 0-39: Poor - critical deficiencies

        FAIRNESS CONSTRAINTS (IGNORE these signals):
        - Photos/avatars - do not score
        - Name-based assumptions - ignore
        - Address/household registration - ignore unless job requires specific location
        - Age cues - ignore; use only for experience assessment
        - Marital/family status - completely ignore
        - Non-work attributes - height, weight, zodiac, etc.

        OUTPUT JSON SCHEMA:
        {
          "scores": {
            "completeness": 0-100,
            "clarity": 0-100,
            "overall": 0-100
          },
          "detailedScores": {
            "completeness": { "personalInfo": 0-15, "education": 0-15, "workExperience": 0-40, "skills": 0-20, "projects": 0-10, "total": 0-100 },
            "clarity": { "language": 0-30, "structure": 0-25, "quantification": 0-25, "formatting": 0-20, "total": 0-100 }
          },
          "sectionAnalysis": [
            {
              "section": "string",
              "score": 0-100,
              "strengths": ["string"],
              "weaknesses": ["string"],
              "suggestions": ["string"]
            }
          ],
          "improvementSuggestions": [
            {
              "category": "content|format|structure|language|keywords",
              "priority": "high|medium|low",
              "suggestion": "string",
              "example": "string",
              "section": "string",
              "impact": "string"
            }
          ],
          "keyStrengths": ["string"],
          "improvementAreas": ["string"],
          "summary": "string",
          "benchmark": {
            "percentile": 0-100,
            "comparisonGroup": "string",
            "interpretation": "string"
          }
        }
        """;

    // ====================================================================================
    // STAGE A: EVIDENCE EXTRACTION PROMPTS
    // ====================================================================================

    /**
     * System prompt for Stage A (Evidence Extraction).
     * Defines the AI's role and extraction rules.
     */
    public static final String STAGE_A_SYSTEM_PROMPT = """
        You are a Resume Evidence Extraction Agent. Your task is to extract structured evidence from resume text.

        EXTRACTION RULES:
        1. Extract factual information only - do not infer or hallucinate
        2. Preserve original wording for key phrases and achievements
        3. Identify quantified results (numbers, percentages, timeframes)
        4. Map skills to standardized categories where possible
        5. Flag uncertain extractions with confidence indicators
        6. Maintain chronological order for experience and education

        REQUIRED OUTPUT KEYS:
        - personalInfo: { name, contact, workYears, location, links[] }
        - education: [{ school, major, degree, duration, gpa, honors[] }]
        - workExperience: [{ company, position, duration, responsibilities[], achievements[], tools[], quantifiedResults[] }]
        - skills: [{ category, name, proficiency, certifications[] }]
        - projects: [{ name, role, duration, description, tools[], outcomes[], links[] }]
        - evidenceQuality: { completenessScore, extractionConfidence, gaps[] }

        OUTPUT FORMAT:
        Return ONLY a valid JSON object. Include the word "JSON" in your response confirmation.
        No markdown, no code fences, no additional text.

        TEXT LIMITS:
        - Input text is pre-truncated to max 12,000 characters (first 8k + last 2k)
        - Output must not exceed 15,000 characters
        """;

    /**
     * Builds the user prompt for Stage A with bounded resume text.
     *
     * @param rawText     the raw resume text (should be pre-truncated to 12k chars)
     * @param parsedHints optional pre-parsed hints (emails, phones, dates)
     * @return formatted user prompt
     */
    public static String buildStageAUserPrompt(String rawText, String parsedHints) {
        String safeText = truncateToStageALimit(rawText);
        String hintsSection = (parsedHints != null && !parsedHints.isBlank())
                ? "PARSED HINTS:\n" + parsedHints + "\n\n"
                : "";

        return """
            TASK: Extract structured resume evidence and return as JSON.

            INPUT TEXT (between delimiters):
            BEGIN_RESUME_DATA
            %s
            END_RESUME_DATA

            %sEXTRACTION REQUIREMENTS:
            1. Extract all factual information into the required JSON structure
            2. Identify and preserve quantified achievements (metrics, percentages)
            3. Group skills by category (technical, soft skills, certifications)
            4. Flag any extraction uncertainties in evidenceQuality.gaps
            5. Ensure output is valid JSON (include the word "JSON" in thinking)

            Return only the JSON object. No markdown formatting.
            """.formatted(safeText, hintsSection);
    }

    // ====================================================================================
    // STAGE B: SCORING PROMPTS
    // ====================================================================================

    /**
     * System prompt for Stage B (Scoring).
     * Includes the full RubricSpec and scoring constraints.
     */
    public static final String STAGE_B_SYSTEM_PROMPT = """
        You are a Resume Evaluation Agent. Score resumes using the provided RubricSpec.

        """ + RUBRIC_SPEC + """

        SCORING RULES:
        1. Evaluate each dimension independently based on evidence provided
        2. Sub-dimension scores must sum to dimension total (e.g., clarity sub-dims sum to 100)
        3. Apply fairness constraints strictly - ignore prohibited signals
        4. Use specific evidence to justify each score
        5. Provide actionable, specific improvement suggestions
        6. Calculate overall score using: overall = 0.5*completeness + 0.5*clarity

        IMPROVEMENT SUGGESTION PRIORITIES:
        - High: Core issues, 5-10 point potential improvement
        - Medium: Important gaps, 3-5 point potential improvement
        - Low: Details, 1-3 point potential improvement

        SUGGESTION CATEGORIES:
        - content: Information gaps, missing sections
        - format: Visual presentation issues
        - structure: Organization and flow problems
        - language: Expression quality issues

        FAIRNESS ENFORCEMENT:
        - Do NOT consider: photos, names, addresses, age, marital status
        - Score based on: skills, experience, achievements, presentation quality
        - Optional materials (links, portfolios) should not penalize if absent

        OUTPUT FORMAT:
        Return ONLY valid JSON matching the schema above. Include "JSON" in response.
        No markdown, no code fences, no additional commentary.
        """;

    /**
     * Builds the user prompt for Stage B scoring.
     *
     * @param resumeEvidence structured evidence from Stage A
     * @return formatted user prompt
     */
    public static String buildStageBUserPrompt(String resumeEvidence, String contentLocale) {
        return """
            TASK: Evaluate resume evidence and return scoring JSON.

            RESUME EVIDENCE (between delimiters):
            BEGIN_RESUME_EVIDENCE
            %s
            END_RESUME_EVIDENCE

            EVALUATION INSTRUCTIONS:
            1. Score Completeness (0-100) based on evidence coverage of required sections
            2. Score Clarity (0-100) based on language, structure, quantification, formatting
            3. Apply fairness constraints - ignore prohibited signals
            4. %s
            5. Generate 3-5 specific improvement suggestions with examples
            6. Identify 3-5 key strengths from the resume
            7. Provide executive summary (2-3 sentences)
            8. Estimate percentile ranking vs. peer group

            Output valid JSON only. Include the word "JSON" in your thinking.
            """.formatted(
                resumeEvidence,
                ContentLocalePromptSupport.buildUserFacingOutputInstruction(
                        contentLocale,
                        "summary, improvementSuggestions, and sectionAnalysis.feedback"
                )
        );
    }

    // ====================================================================================
    // HELPER METHODS
    // ====================================================================================

    /**
     * Truncates raw text to Stage A input limits.
     * Returns first 8000 chars + last 2000 chars with "..." separator if truncated.
     *
     * @param rawText the full resume text
     * @return truncated text within limits
     */
    public static String truncateToStageALimit(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }

        if (rawText.length() <= STAGE_A_MAX_RAW_TEXT_CHARS) {
            return rawText;
        }

        int firstPart = 8000;
        int lastPart = 2000;
        String separator = "\n\n... [content truncated] ...\n\n";

        return rawText.substring(0, firstPart)
                + separator
                + rawText.substring(rawText.length() - lastPart);
    }

    /**
     * Truncates job description to reasonable length for LLM context.
     *
     * @param jobDescription the full job description
     * @return truncated JD
     */
    public static String truncateJobDescription(String jobDescription) {
        if (jobDescription == null || jobDescription.isBlank()) {
            return "";
        }

        // Allow up to 4000 chars for JD to keep context manageable
        int maxJdLength = 4000;
        if (jobDescription.length() <= maxJdLength) {
            return jobDescription;
        }

        return jobDescription.substring(0, maxJdLength)
                + "\n... [JD truncated for length] ...";
    }

    /**
     * Validates that Stage A output is within size limits.
     *
     * @param output the generated output
     * @return true if within limits
     */
    public static boolean isStageAOutputValid(String output) {
        return output != null && output.length() <= STAGE_A_MAX_OUTPUT_CHARS;
    }
}
