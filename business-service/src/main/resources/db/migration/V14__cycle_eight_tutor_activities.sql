-- Cycle 8: stateful Tutor activities. AI output remains an untrusted draft; Spring owns
-- public/private payload separation, deterministic grading, evidence, and mastery.

ALTER TABLE edu_tutor_message
    ADD COLUMN teaching_plan_json JSON NULL AFTER prompt_version,
    ADD COLUMN specialist_trace_json JSON NULL AFTER teaching_plan_json;

CREATE TABLE edu_tutor_activity (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    activity_ref VARCHAR(80) NOT NULL,
    activity_type VARCHAR(20) NOT NULL,
    title VARCHAR(200) NOT NULL,
    knowledge_point_id VARCHAR(64) NOT NULL,
    knowledge_point_name VARCHAR(200) NOT NULL,
    graph_version VARCHAR(100) NOT NULL,
    difficulty VARCHAR(20) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'READY',
    public_payload_json JSON NOT NULL,
    private_payload_json JSON NOT NULL,
    max_score DECIMAL(8,2) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_tutor_activity_session FOREIGN KEY (session_id) REFERENCES edu_tutor_session (id),
    CONSTRAINT fk_tutor_activity_message FOREIGN KEY (message_id) REFERENCES edu_tutor_message (id),
    CONSTRAINT fk_tutor_activity_student FOREIGN KEY (student_id) REFERENCES edu_user (id),
    UNIQUE KEY uk_tutor_activity_message_ref (message_id, activity_ref),
    KEY idx_tutor_activity_student_created (student_id, created_at),
    KEY idx_tutor_activity_session_message (session_id, message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_tutor_activity_attempt (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    idempotency_record_id BIGINT NULL,
    attempt_no INT NOT NULL,
    attempt_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    answer_json JSON NOT NULL,
    score DECIMAL(8,2) NOT NULL,
    max_score DECIMAL(8,2) NOT NULL,
    result_json JSON NOT NULL,
    ai_analysis VARCHAR(4000) NULL,
    mastery_before DECIMAL(7,4) NULL,
    mastery_after DECIMAL(7,4) NULL,
    mastery_delta DECIMAL(7,4) NULL,
    submitted_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_tutor_attempt_activity FOREIGN KEY (activity_id) REFERENCES edu_tutor_activity (id),
    CONSTRAINT fk_tutor_attempt_student FOREIGN KEY (student_id) REFERENCES edu_user (id),
    CONSTRAINT fk_tutor_attempt_idempotency FOREIGN KEY (idempotency_record_id)
        REFERENCES edu_idempotency_record (id) ON DELETE SET NULL,
    UNIQUE KEY uk_tutor_attempt_activity_no (activity_id, attempt_no),
    UNIQUE KEY uk_tutor_attempt_idempotency (idempotency_record_id),
    KEY idx_tutor_attempt_student_time (student_id, submitted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_tutor_code_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL,
    attempt_id BIGINT NULL,
    student_id BIGINT NOT NULL,
    run_type VARCHAR(20) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    source_hash CHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    score DECIMAL(8,2) NOT NULL,
    total_score DECIMAL(8,2) NOT NULL,
    result_json JSON NOT NULL,
    duration_ms INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_tutor_code_run_activity FOREIGN KEY (activity_id) REFERENCES edu_tutor_activity (id),
    CONSTRAINT fk_tutor_code_run_attempt FOREIGN KEY (attempt_id)
        REFERENCES edu_tutor_activity_attempt (id),
    CONSTRAINT fk_tutor_code_run_student FOREIGN KEY (student_id) REFERENCES edu_user (id),
    UNIQUE KEY uk_tutor_code_run_request (request_id),
    KEY idx_tutor_code_run_activity_time (activity_id, created_at),
    KEY idx_tutor_code_run_student_time (student_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Cycle 5 evidence originally required assessment foreign keys. Tutor practice is a
-- separate domain and uses source_type/source_id without fabricating assessment rows.
ALTER TABLE edu_learning_evidence
    ADD KEY idx_learning_evidence_answer (answer_id);

ALTER TABLE edu_learning_evidence
    DROP INDEX uk_learning_evidence_answer_kp,
    MODIFY COLUMN assessment_id BIGINT NULL,
    MODIFY COLUMN submission_id BIGINT NULL,
    MODIFY COLUMN answer_id BIGINT NULL,
    MODIFY COLUMN question_id BIGINT NULL,
    ADD UNIQUE KEY uk_learning_evidence_source_kp
        (student_id, source_type, source_id, knowledge_point_id);
