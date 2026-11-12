ALTER TABLE edu_tutor_session
    ADD COLUMN knowledge_point_name VARCHAR(200) NULL AFTER knowledge_point_id,
    ADD COLUMN entry_source VARCHAR(30) NOT NULL DEFAULT 'MANUAL' AFTER knowledge_point_name,
    ADD COLUMN source_question_id BIGINT NULL AFTER entry_source;

UPDATE edu_tutor_session
SET knowledge_point_name = knowledge_point_id
WHERE knowledge_point_name IS NULL OR knowledge_point_name = '';

ALTER TABLE edu_tutor_session
    MODIFY COLUMN knowledge_point_name VARCHAR(200) NOT NULL,
    ADD KEY idx_tutor_student_status_updated (student_id, status, updated_at);

ALTER TABLE edu_tutor_message
    ADD COLUMN request_id VARCHAR(64) NULL AFTER sources_json,
    ADD COLUMN model_version VARCHAR(100) NULL AFTER request_id,
    ADD COLUMN prompt_version VARCHAR(100) NULL AFTER model_version,
    ADD KEY idx_tutor_message_request (request_id);
