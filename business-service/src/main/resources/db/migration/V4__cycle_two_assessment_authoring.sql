ALTER TABLE edu_assessment
    ADD COLUMN settings_json JSON NULL AFTER anti_cheat_policy_json;

UPDATE edu_assessment
SET settings_json = JSON_OBJECT(
        'autoGradeEnabled', auto_grade_enabled,
        'autoReleaseEnabled', auto_release_enabled,
        'aiAttachmentGradeEnabled', ai_attachment_grade_enabled,
        'allowLateSubmission', FALSE,
        'showScoreAfterRelease', TRUE,
        'showCorrectAnswerAfterRelease', TRUE,
        'showQuestionFeedbackAfterRelease', TRUE,
        'shuffleQuestions', FALSE,
        'shuffleOptions', FALSE,
        'antiCheatPolicy', COALESCE(anti_cheat_policy_json, JSON_OBJECT())
    )
WHERE settings_json IS NULL;

ALTER TABLE edu_assessment
    MODIFY COLUMN settings_json JSON NOT NULL;

ALTER TABLE edu_assessment_question
    ADD COLUMN client_key VARCHAR(64) NULL AFTER assessment_id;

UPDATE edu_assessment_question
SET client_key = CONCAT('legacy-question-', id)
WHERE client_key IS NULL;

ALTER TABLE edu_assessment_question
    MODIFY COLUMN client_key VARCHAR(64) NOT NULL,
    ADD UNIQUE KEY uk_question_client_key (assessment_id, client_key);

ALTER TABLE edu_question_rubric_item
    ADD COLUMN client_key VARCHAR(64) NULL AFTER question_id;

UPDATE edu_question_rubric_item
SET client_key = CONCAT('legacy-rubric-', id)
WHERE client_key IS NULL;

ALTER TABLE edu_question_rubric_item
    MODIFY COLUMN client_key VARCHAR(64) NOT NULL,
    ADD UNIQUE KEY uk_rubric_client_key (question_id, client_key);

ALTER TABLE edu_programming_test_case
    ADD COLUMN client_key VARCHAR(64) NULL AFTER question_id;

UPDATE edu_programming_test_case
SET client_key = CONCAT('legacy-test-', id)
WHERE client_key IS NULL;

ALTER TABLE edu_programming_test_case
    MODIFY COLUMN client_key VARCHAR(64) NOT NULL,
    ADD UNIQUE KEY uk_test_case_client_key (question_id, client_key);

ALTER TABLE edu_assessment_import_task
    ADD COLUMN progress_percent INT NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0 AFTER progress_percent,
    ADD COLUMN dedupe_hash CHAR(64) NULL AFTER request_id,
    ADD COLUMN started_at DATETIME(3) NULL AFTER created_at;

UPDATE edu_assessment_import_task task
JOIN edu_file_object file_object ON file_object.id = task.source_file_id
SET task.dedupe_hash = SHA2(CONCAT(task.assessment_id, ':', file_object.sha256), 256)
WHERE task.dedupe_hash IS NULL;

ALTER TABLE edu_assessment_import_task
    MODIFY COLUMN dedupe_hash CHAR(64) NOT NULL,
    ADD UNIQUE KEY uk_import_assessment_file_hash (assessment_id, dedupe_hash),
    ADD KEY idx_import_assessment_status (assessment_id, status);
