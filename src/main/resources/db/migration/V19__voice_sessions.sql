-- Spoken mock interview runs (KTD5, KTD6). A draft holds only its copy of up to six AI questions; Save adds the reviewed
-- transcript, its normalized hash and the report job IDs in one transaction. The report is written later onto the same row.
-- Deleting the practice set, resume or target job removes the session. Practice sets and attempts are unchanged.
CREATE TABLE ai_interview_app.voice_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES ai_interview_app.app_users(id) ON DELETE CASCADE,
    practice_set_id uuid NOT NULL,
    resume_id uuid NOT NULL,
    target_job_id uuid NOT NULL,
    questions jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    run_deadline timestamptz NOT NULL,
    draft_expires_at timestamptz NOT NULL,
    transcript jsonb,
    transcript_hash varchar(64),
    saved_at timestamptz,
    submission_job_id uuid,
    report_job_id uuid,
    report jsonb,
    CONSTRAINT ck_voice_sessions_questions CHECK (jsonb_typeof(questions) = 'array' AND jsonb_array_length(questions) BETWEEN 1 AND 6),
    CONSTRAINT ck_voice_sessions_deadlines CHECK (run_deadline > created_at AND draft_expires_at >= run_deadline),
    CONSTRAINT ck_voice_sessions_saved CHECK (
        (saved_at IS NULL) = (transcript IS NULL) AND (saved_at IS NULL) = (transcript_hash IS NULL)
        AND (saved_at IS NULL) = (submission_job_id IS NULL) AND (saved_at IS NULL) = (report_job_id IS NULL)
    ),
    CONSTRAINT ck_voice_sessions_transcript_object CHECK (transcript IS NULL OR jsonb_typeof(transcript) = 'object'),
    CONSTRAINT ck_voice_sessions_report CHECK (report IS NULL OR (saved_at IS NOT NULL AND jsonb_typeof(report) = 'object')),
    CONSTRAINT fk_voice_sessions_practice_set_owner FOREIGN KEY (practice_set_id, user_id)
        REFERENCES ai_interview_app.practice_sets(id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_voice_sessions_resume_owner FOREIGN KEY (resume_id, user_id)
        REFERENCES ai_interview_app.resumes(id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_voice_sessions_target_job_owner FOREIGN KEY (target_job_id, user_id)
        REFERENCES ai_interview_app.job_descriptions(id, user_id) ON DELETE CASCADE
);

CREATE INDEX idx_voice_sessions_practice_set_owner ON ai_interview_app.voice_sessions (practice_set_id, user_id);
CREATE INDEX idx_voice_sessions_resume_owner ON ai_interview_app.voice_sessions (resume_id, user_id);
CREATE INDEX idx_voice_sessions_target_job_owner ON ai_interview_app.voice_sessions (target_job_id, user_id);

ALTER TABLE ai_interview_app.background_job_effects
    DROP CONSTRAINT background_job_effect_type_check,
    ADD CONSTRAINT background_job_effect_type_check
        CHECK (effect_type IN ('ANSWER_FEEDBACK', 'RESUME_SCORE', 'JOB_FIT', 'EXPERIENCE_SUGGESTIONS', 'PRACTICE_QUESTIONS', 'VOICE_REPORT'));

-- Private like the other application tables: only the restricted runtime role reads and writes voice sessions.
REVOKE ALL ON TABLE ai_interview_app.voice_sessions FROM PUBLIC;

DO $$
DECLARE
    role_name text;
BEGIN
    FOR role_name IN
        SELECT rolname FROM pg_roles WHERE rolname IN ('anon', 'authenticated', 'service_role')
    LOOP
        EXECUTE format('REVOKE ALL ON TABLE ai_interview_app.voice_sessions FROM %I', role_name);
    END LOOP;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_interview_runtime') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE ai_interview_app.voice_sessions TO ai_interview_runtime;
    END IF;
END
$$;
