-- Cycle 3: bind every attempt to an immutable publication and make autosave/exam
-- runtime state enforceable by the database.  Historical rows remain nullable;
-- all new rows are validated by the service before insert.
ALTER TABLE edu_assessment_submission
    ADD COLUMN assessment_version_id BIGINT NULL AFTER assessment_version,
    ADD COLUMN accepted_save_sequence BIGINT NOT NULL DEFAULT 0 AFTER attempt_no,
    ADD COLUMN grading_attempt_count INT NOT NULL DEFAULT 0 AFTER accepted_save_sequence,
    ADD COLUMN exam_session_token_hash CHAR(64) NULL AFTER grading_attempt_count,
    ADD COLUMN device_id_hash CHAR(64) NULL AFTER exam_session_token_hash,
    ADD COLUMN exam_deadline_at DATETIME(3) NULL AFTER device_id_hash,
    ADD COLUMN exam_session_started_at DATETIME(3) NULL AFTER exam_deadline_at,
    ADD COLUMN last_heartbeat_at DATETIME(3) NULL AFTER exam_session_started_at,
    ADD COLUMN heartbeat_interval_seconds INT NULL AFTER last_heartbeat_at,
    ADD COLUMN submitted_by_timeout BOOLEAN NOT NULL DEFAULT FALSE AFTER heartbeat_interval_seconds,
    ADD CONSTRAINT fk_submission_assessment_version
        FOREIGN KEY (assessment_version_id) REFERENCES edu_assessment_version (id),
    ADD KEY idx_submission_expired (status, exam_deadline_at);

ALTER TABLE edu_submission_answer
    ADD COLUMN client_save_sequence BIGINT NOT NULL DEFAULT 0 AFTER question_id;

ALTER TABLE edu_anti_cheat_event
    ADD COLUMN client_event_id VARCHAR(128) NULL AFTER submission_id,
    ADD COLUMN received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) AFTER details_json,
    ADD UNIQUE KEY uk_anti_cheat_client_event (submission_id, client_event_id);

ALTER TABLE edu_code_run_record
    ADD COLUMN request_id VARCHAR(64) NULL AFTER id,
    ADD UNIQUE KEY uk_code_run_request (request_id);

-- Existing production rows predate immutable-version binding.  Backfill them
-- only when the corresponding version exists; future writes always set the FK.
UPDATE edu_assessment_submission s
JOIN edu_assessment_version v
  ON v.assessment_id = s.assessment_id AND v.version_no = s.assessment_version
SET s.assessment_version_id = v.id
WHERE s.assessment_version_id IS NULL;
