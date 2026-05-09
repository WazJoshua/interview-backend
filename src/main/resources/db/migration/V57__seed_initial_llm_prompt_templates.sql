-- V57: Seed initial LLM prompt templates
-- Seeds 10 template identities with revision 1, from Java fallback prompts
-- Note: Locale-sensitive prompts use ${promptLanguage} or ${contentLocaleInstruction} variables

-- 1. resume_analysis_stage_a
INSERT INTO llm_prompt_template (template_key, domain, purpose, invocation_kind, description, enabled, created_at, updated_at, created_by, updated_by)
VALUES (
    'resume_analysis_stage_a',
    'resume',
    'analysis',
    'CHAT',
    'Resume evidence extraction - Stage A (structured extraction from raw text)',
    true,
    NOW(),
    NOW(),
    'seed',
    'seed'
);

INSERT INTO llm_prompt_template_revision (template_id, revision_no, system_template, user_template, variables, change_note, created_at, created_by)
SELECT
    id,
    1,
    'You are a Resume Evidence Extraction Agent. Your task is to extract structured evidence from resume text.

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
- Output must not exceed 15,000 characters',
    'TASK: Extract structured resume evidence and return as JSON.

INPUT TEXT (between delimiters):
BEGIN_RESUME_DATA
${resumeText}
END_RESUME_DATA

${parsedHintsSection}EXTRACTION REQUIREMENTS:
1. Extract all factual information into the required JSON structure
2. Identify and preserve quantified achievements (metrics, percentages)
3. Group skills by category (technical, soft skills, certifications)
4. Flag any extraction uncertainties in evidenceQuality.gaps
5. Ensure output is valid JSON (include the word "JSON" in thinking)

Return only the JSON object. No markdown formatting.',
    '[{"name":"resumeText","required":true},{"name":"parsedHintsSection","required":false}]',
    'seed from java fallback',
    NOW(),
    'seed'
FROM llm_prompt_template WHERE template_key = 'resume_analysis_stage_a';

UPDATE llm_prompt_template
SET active_revision_id = (SELECT id FROM llm_prompt_template_revision WHERE template_id = (SELECT id FROM llm_prompt_template WHERE template_key = 'resume_analysis_stage_a') AND revision_no = 1)
WHERE template_key = 'resume_analysis_stage_a';

-- 2. resume_analysis_stage_b
INSERT INTO llm_prompt_template (template_key, domain, purpose, invocation_kind, description, enabled, created_at, updated_at, created_by, updated_by)
VALUES (
    'resume_analysis_stage_b',
    'resume',
    'analysis',
    'CHAT',
    'Resume scoring - Stage B (quality assessment using RubricSpec)',
    true,
    NOW(),
    NOW(),
    'seed',
    'seed'
);

INSERT INTO llm_prompt_template_revision (template_id, revision_no, system_template, user_template, variables, change_note, created_at, created_by)
SELECT
    id,
    1,
    'You are a Resume Evaluation Agent. Score resumes using the provided RubricSpec.

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
No markdown, no code fences, no additional commentary.',
    'TASK: Evaluate resume evidence and return scoring JSON.

RESUME EVIDENCE (between delimiters):
BEGIN_RESUME_EVIDENCE
${resumeEvidence}
END_RESUME_EVIDENCE

EVALUATION INSTRUCTIONS:
1. Score Completeness (0-100) based on evidence coverage of required sections
2. Score Clarity (0-100) based on language, structure, quantification, formatting
3. Apply fairness constraints - ignore prohibited signals
4. ${contentLocaleInstruction}
5. Generate 3-5 specific improvement suggestions with examples
6. Identify 3-5 key strengths from the resume
7. Provide executive summary (2-3 sentences)
8. Estimate percentile ranking vs. peer group

Output valid JSON only. Include the word "JSON" in your thinking.',
    '[{"name":"resumeEvidence","required":true},{"name":"contentLocaleInstruction","required":true}]',
    'seed from java fallback',
    NOW(),
    'seed'
FROM llm_prompt_template WHERE template_key = 'resume_analysis_stage_b';

UPDATE llm_prompt_template
SET active_revision_id = (SELECT id FROM llm_prompt_template_revision WHERE template_id = (SELECT id FROM llm_prompt_template WHERE template_key = 'resume_analysis_stage_b') AND revision_no = 1)
WHERE template_key = 'resume_analysis_stage_b';

-- 3. resume_job_match
INSERT INTO llm_prompt_template (template_key, domain, purpose, invocation_kind, description, enabled, created_at, updated_at, created_by, updated_by)
VALUES (
    'resume_job_match',
    'resume',
    'resume_job_match',
    'CHAT',
    'Resume-JD match scoring and gap analysis',
    true,
    NOW(),
    NOW(),
    'seed',
    'seed'
);

INSERT INTO llm_prompt_template_revision (template_id, revision_no, system_template, user_template, variables, change_note, created_at, created_by)
SELECT
    id,
    1,
    'You are a Resume-Job Match Agent.

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
}',
    'TASK: Compare resume evidence against the target job and produce match JSON.

RESUME EVIDENCE (between delimiters):
BEGIN_EVIDENCE
${evidenceJson}
END_EVIDENCE

TARGET JOB TITLE (between delimiters):
BEGIN_JOB_TITLE
${jobTitle}
END_JOB_TITLE

TARGET JOB DESCRIPTION (between delimiters):
BEGIN_JOB_DESCRIPTION
${jobDescription}
END_JOB_DESCRIPTION

INSTRUCTIONS:
1. Produce matchScore between 0 and 100.
2. Provide 3-6 strengths and 3-6 gaps, each as a short sentence.
3. Provide 3-6 actionable suggestions.
4. Keep summary concise (2-4 sentences).
5. ${contentLocaleInstruction}',
    '[{"name":"evidenceJson","required":true},{"name":"jobTitle","required":true},{"name":"jobDescription","required":true},{"name":"contentLocaleInstruction","required":true}]',
    'seed from java fallback',
    NOW(),
    'seed'
FROM llm_prompt_template WHERE template_key = 'resume_job_match';

UPDATE llm_prompt_template
SET active_revision_id = (SELECT id FROM llm_prompt_template_revision WHERE template_id = (SELECT id FROM llm_prompt_template WHERE template_key = 'resume_job_match') AND revision_no = 1)
WHERE template_key = 'resume_job_match';

-- 4. resume_parse
INSERT INTO llm_prompt_template (template_key, domain, purpose, invocation_kind, description, enabled, created_at, updated_at, created_by, updated_by)
VALUES (
    'resume_parse',
    'resume',
    'parse',
    'CHAT',
    'Resume structured extraction (regex hints + LLM structuring)',
    true,
    NOW(),
    NOW(),
    'seed',
    'seed'
);

INSERT INTO llm_prompt_template_revision (template_id, revision_no, system_template, user_template, variables, change_note, created_at, created_by)
SELECT
    id,
    1,
    'You are a resume parsing assistant. Return ONLY a JSON object with top-level keys: personalInfo, education, workExperience, skills, projects. Do not include markdown, code fences, or extra text.',
    'Raw resume text:
${rawText}

Rule hints (emails/phones/dates):
${ruleHints}

Please produce the structured JSON.',
    '[{"name":"rawText","required":true},{"name":"ruleHints","required":true}]',
    'seed from java fallback',
    NOW(),
    'seed'
FROM llm_prompt_template WHERE template_key = 'resume_parse';

UPDATE llm_prompt_template
SET active_revision_id = (SELECT id FROM llm_prompt_template_revision WHERE template_id = (SELECT id FROM llm_prompt_template WHERE template_key = 'resume_parse') AND revision_no = 1)
WHERE template_key = 'resume_parse';

-- 5. kb_query_rewrite (system-only, user_template = null)
INSERT INTO llm_prompt_template (template_key, domain, purpose, invocation_kind, description, enabled, created_at, updated_at, created_by, updated_by)
VALUES (
    'kb_query_rewrite',
    'ragqa',
    'kb_query_rewrite',
    'CHAT',
    'Query rewrite for retrieval - system prompt only, user prompt from runtime input',
    true,
    NOW(),
    NOW(),
    'seed',
    'seed'
);

INSERT INTO llm_prompt_template_revision (template_id, revision_no, system_template, user_template, variables, change_note, created_at, created_by)
SELECT
    id,
    1,
    'Rewrite the query for retrieval only.
Preserve structured tokens and protected terminology.
Return JSON only with {"rewrittenQuery":"..."}.',
    NULL,
    '[]',
    'seed from java fallback',
    NOW(),
    'seed'
FROM llm_prompt_template WHERE template_key = 'kb_query_rewrite';

UPDATE llm_prompt_template
SET active_revision_id = (SELECT id FROM llm_prompt_template_revision WHERE template_id = (SELECT id FROM llm_prompt_template WHERE template_key = 'kb_query_rewrite') AND revision_no = 1)
WHERE template_key = 'kb_query_rewrite';

-- 6. rag_answer_system_only (system-only, user_template = null)
INSERT INTO llm_prompt_template (template_key, domain, purpose, invocation_kind, description, enabled, created_at, updated_at, created_by, updated_by)
VALUES (
    'rag_answer_system_only',
    'ragqa',
    'rag',
    'CHAT',
    'RAG answer generation - system prompt only, user prompt from runtime context assembly',
    true,
    NOW(),
    NOW(),
    'seed',
    'seed'
);

INSERT INTO llm_prompt_template_revision (template_id, revision_no, system_template, user_template, variables, change_note, created_at, created_by)
SELECT
    id,
    1,
    'You are a RAG assistant. Answer the question only based on the provided context. Return a JSON object with keys answer and confidence. Confidence must be a number between 0 and 1.',
    NULL,
    '[]',
    'seed from java fallback',
    NOW(),
    'seed'
FROM llm_prompt_template WHERE template_key = 'rag_answer_system_only';

UPDATE llm_prompt_template
SET active_revision_id = (SELECT id FROM llm_prompt_template_revision WHERE template_id = (SELECT id FROM llm_prompt_template WHERE template_key = 'rag_answer_system_only') AND revision_no = 1)
WHERE template_key = 'rag_answer_system_only';

-- 7. interview_question_generation_main
INSERT INTO llm_prompt_template (template_key, domain, purpose, invocation_kind, description, enabled, created_at, updated_at, created_by, updated_by)
VALUES (
    'interview_question_generation_main',
    'interview',
    'interview_question_generation',
    'CHAT',
    'Main interview question generation from blueprint',
    true,
    NOW(),
    NOW(),
    'seed',
    'seed'
);

INSERT INTO llm_prompt_template_revision (template_id, revision_no, system_template, user_template, variables, change_note, created_at, created_by)
SELECT
    id,
    1,
    'You are an expert technical interviewer creating interview questions.
Generate questions in ${promptLanguage} language.
Return ONLY valid JSON matching the required schema.
Do not include markdown formatting or explanatory text.',
    'Generate interview questions for the following position:

Job Title: ${jobTitle}

${jobDescriptionSection}

${resumeContentSection}

${blueprintsSection}

Generate questions in ${promptLanguage} language.

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
- focusHint is optional but recommended for context',
    '[{"name":"promptLanguage","required":true},{"name":"jobTitle","required":true},{"name":"jobDescriptionSection","required":false},{"name":"resumeContentSection","required":false},{"name":"blueprintsSection","required":false}]',
    'seed from java fallback',
    NOW(),
    'seed'
FROM llm_prompt_template WHERE template_key = 'interview_question_generation_main';

UPDATE llm_prompt_template
SET active_revision_id = (SELECT id FROM llm_prompt_template_revision WHERE template_id = (SELECT id FROM llm_prompt_template WHERE template_key = 'interview_question_generation_main') AND revision_no = 1)
WHERE template_key = 'interview_question_generation_main';

-- 8. interview_question_generation_followup
INSERT INTO llm_prompt_template (template_key, domain, purpose, invocation_kind, description, enabled, created_at, updated_at, created_by, updated_by)
VALUES (
    'interview_question_generation_followup',
    'interview',
    'interview_follow_up_generation',
    'CHAT',
    'Follow-up question generation based on candidate answer',
    true,
    NOW(),
    NOW(),
    'seed',
    'seed'
);

INSERT INTO llm_prompt_template_revision (template_id, revision_no, system_template, user_template, variables, change_note, created_at, created_by)
SELECT
    id,
    1,
    'You are an expert technical interviewer creating interview questions.
Generate questions in ${promptLanguage} language.
Return ONLY valid JSON matching the required schema.
Do not include markdown formatting or explanatory text.',
    'Generate a follow-up question based on the candidate''s answer.

Original Question:
${mainQuestion}

Candidate''s Answer:
${answer}

Follow-up Intent: ${followUpIntent}
Follow-up Depth: ${branchDepth}
${jobTitleSection}
${jobDescriptionSection}
${resumeContentSection}

Generate the follow-up question in ${promptLanguage} language.

Return a JSON object with this structure:
{
  "questionContent": "The follow-up question text",
  "focusHint": "Why this follow-up is relevant"
}

Requirements:
- questionContent is required
- focusHint is optional but recommended
- Respect the provided follow-up intent
- Avoid repeating the original question',
    '[{"name":"promptLanguage","required":true},{"name":"mainQuestion","required":true},{"name":"answer","required":true},{"name":"followUpIntent","required":true},{"name":"branchDepth","required":true},{"name":"jobTitleSection","required":false},{"name":"jobDescriptionSection","required":false},{"name":"resumeContentSection","required":false}]',
    'seed from java fallback',
    NOW(),
    'seed'
FROM llm_prompt_template WHERE template_key = 'interview_question_generation_followup';

UPDATE llm_prompt_template
SET active_revision_id = (SELECT id FROM llm_prompt_template_revision WHERE template_id = (SELECT id FROM llm_prompt_template WHERE template_key = 'interview_question_generation_followup') AND revision_no = 1)
WHERE template_key = 'interview_question_generation_followup';

-- 9. interview_evaluation
INSERT INTO llm_prompt_template (template_key, domain, purpose, invocation_kind, description, enabled, created_at, updated_at, created_by, updated_by)
VALUES (
    'interview_evaluation',
    'interview',
    'interview_answer_evaluation',
    'CHAT',
    'Interview answer evaluation and scoring',
    true,
    NOW(),
    NOW(),
    'seed',
    'seed'
);

INSERT INTO llm_prompt_template_revision (template_id, revision_no, system_template, user_template, variables, change_note, created_at, created_by)
SELECT
    id,
    1,
    'You are an expert technical interviewer evaluating candidate answers.
Provide evaluations in ${promptLanguage} language.
Return ONLY valid JSON matching the required schema.
Do not include markdown formatting or explanatory text.',
    'Evaluate the following interview answer.

Job Title: ${jobTitle}

Question:
${question}

Candidate''s Answer:
${answer}

Provide evaluation in ${promptLanguage} language.

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
- Be fair and objective in your evaluation',
    '[{"name":"promptLanguage","required":true},{"name":"jobTitle","required":true},{"name":"question","required":true},{"name":"answer","required":true}]',
    'seed from java fallback',
    NOW(),
    'seed'
FROM llm_prompt_template WHERE template_key = 'interview_evaluation';

UPDATE llm_prompt_template
SET active_revision_id = (SELECT id FROM llm_prompt_template_revision WHERE template_id = (SELECT id FROM llm_prompt_template WHERE template_key = 'interview_evaluation') AND revision_no = 1)
WHERE template_key = 'interview_evaluation';

-- 10. interview_report
INSERT INTO llm_prompt_template (template_key, domain, purpose, invocation_kind, description, enabled, created_at, updated_at, created_by, updated_by)
VALUES (
    'interview_report',
    'interview',
    'interview_report_generation',
    'CHAT',
    'Interview report generation with skill assessment',
    true,
    NOW(),
    NOW(),
    'seed',
    'seed'
);

INSERT INTO llm_prompt_template_revision (template_id, revision_no, system_template, user_template, variables, change_note, created_at, created_by)
SELECT
    id,
    1,
    'You are an expert technical interviewer generating interview reports.
Write reports in ${promptLanguage} language.
Return ONLY valid JSON matching the required schema.
Do not include markdown formatting or explanatory text.',
    'Generate an interview report based on the following interview data.

Job Title: ${jobTitle}

Job Description:
${jobDescription}

Questions:
${questionsSummary}

Answers:
${answersSummary}

Branch Summary:
${branchSummary}

Running Score: ${runningScore}

Overall Score: ${overallScore}

Completion Reason: ${completionReason}

Write the report in ${promptLanguage} language.

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
- Consider the running score when assessing overall performance',
    '[{"name":"promptLanguage","required":true},{"name":"jobTitle","required":true},{"name":"jobDescription","required":true},{"name":"questionsSummary","required":true},{"name":"answersSummary","required":true},{"name":"branchSummary","required":true},{"name":"runningScore","required":true},{"name":"overallScore","required":true},{"name":"completionReason","required":true}]',
    'seed from java fallback',
    NOW(),
    'seed'
FROM llm_prompt_template WHERE template_key = 'interview_report';

UPDATE llm_prompt_template
SET active_revision_id = (SELECT id FROM llm_prompt_template_revision WHERE template_id = (SELECT id FROM llm_prompt_template WHERE template_key = 'interview_report') AND revision_no = 1)
WHERE template_key = 'interview_report';