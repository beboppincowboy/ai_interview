ALTER TABLE ai_interview_app.rag_document_indexes
    DROP CONSTRAINT rag_document_indexes_source_type_check,
    ADD CONSTRAINT rag_document_indexes_source_type_check
        CHECK (source_type IN ('RESUME', 'JOB_DESCRIPTION', 'EXPERIENCE'));
