-- Failed provisioning attempts count too: each request spends a slot before calling the provider.
ALTER TABLE ai_interview_app.voice_sessions
    ADD COLUMN token_mint_count smallint NOT NULL DEFAULT 0
        CHECK (token_mint_count BETWEEN 0 AND 12);
