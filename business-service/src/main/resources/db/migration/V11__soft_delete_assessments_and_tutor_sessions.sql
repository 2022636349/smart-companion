ALTER TABLE edu_assessment
    ADD COLUMN deleted_at DATETIME(3) NULL AFTER updated_at,
    ADD KEY idx_assessment_deleted_at (deleted_at);

ALTER TABLE edu_tutor_session
    ADD COLUMN deleted_at DATETIME(3) NULL AFTER updated_at,
    ADD KEY idx_tutor_session_deleted_at (deleted_at);
