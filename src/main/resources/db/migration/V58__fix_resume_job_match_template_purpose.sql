-- V58: Fix resume_job_match template purpose to match admin API contract
-- ResumeJobMatchService invokes with purpose='match', but seed used "resume_job_match"
-- This makes the template visible when admins filter by purpose=match per the new admin API docs

UPDATE llm_prompt_template
SET purpose = 'match'
WHERE template_key = 'resume_job_match';