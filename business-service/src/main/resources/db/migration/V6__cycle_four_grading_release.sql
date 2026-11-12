ALTER TABLE edu_assessment
    ADD COLUMN scheduled_release_at DATETIME(3) NULL AFTER settings_json,
    ADD KEY idx_assessment_scheduled_release (status, scheduled_release_at);

ALTER TABLE edu_grading_record
    ADD COLUMN request_id VARCHAR(64) NULL AFTER graded_by,
    ADD KEY idx_grading_request_id (request_id);

ALTER TABLE edu_ai_task
    ADD COLUMN active_key VARCHAR(160) NULL AFTER request_id,
    ADD UNIQUE KEY uk_ai_task_active_key (active_key),
    ADD KEY idx_ai_task_request_status (request_id, status);

CREATE TABLE edu_ai_task_file_grant (
    task_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    answer_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    PRIMARY KEY (task_id, file_id),
    CONSTRAINT fk_ai_task_file_grant_task FOREIGN KEY (task_id) REFERENCES edu_ai_task (id),
    CONSTRAINT fk_ai_task_file_grant_answer FOREIGN KEY (answer_id) REFERENCES edu_submission_answer (id),
    CONSTRAINT fk_ai_task_file_grant_file FOREIGN KEY (file_id) REFERENCES edu_file_object (id),
    KEY idx_ai_task_file_request (request_id, file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

UPDATE edu_submission_answer
SET final_score = auto_score
WHERE final_score IS NULL
  AND auto_score IS NOT NULL
  AND grading_status = 'COMPLETED';

UPDATE edu_assessment_submission
SET final_score = auto_score
WHERE final_score IS NULL
  AND auto_score IS NOT NULL
  AND grading_status = 'COMPLETED';

INSERT INTO edu_grading_record
    (answer_id, grading_source, suggested_score, final_score, evidence_json, confidence, created_at)
SELECT answer.id, 'AUTO', answer.auto_score, answer.final_score, JSON_OBJECT('rubricGrades', JSON_ARRAY()), 1.0000,
       COALESCE(answer.submitted_at, answer.saved_at)
FROM edu_submission_answer answer
WHERE answer.auto_score IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM edu_grading_record record
      WHERE record.answer_id = answer.id AND record.grading_source = 'AUTO'
  );
