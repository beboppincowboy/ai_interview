-- A retried add with the same Idempotency-Key replays the saved question from PostgreSQL, so it cannot be duplicated
-- when Redis is unavailable. The key is stored only as a SHA-256 hash; rows added without a key keep NULL.
ALTER TABLE ai_interview_app.practice_questions
    ADD COLUMN idempotency_key_hash char(64),
    ADD CONSTRAINT uq_practice_questions_set_idempotency_key UNIQUE (practice_set_id, idempotency_key_hash);
