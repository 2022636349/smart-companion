-- Cycle 5: deterministic learning evidence/mastery and durable memory index synchronization.
-- MySQL remains the source of truth.  The outbox is the only bridge to the AI
-- service's rebuildable learning_memory collection.

ALTER TABLE edu_learning_event
    ADD COLUMN dedupe_key VARCHAR(160) NULL AFTER id,
    ADD UNIQUE KEY uk_learning_event_dedupe (dedupe_key);

ALTER TABLE edu_learning_memory
    ADD COLUMN content_hash CHAR(64) NULL AFTER content,
    ADD COLUMN index_generation INT NOT NULL DEFAULT 0 AFTER version_no,
    ADD COLUMN revision_reason VARCHAR(1000) NULL AFTER supersedes_id,
    ADD KEY idx_memory_rebuild (status, student_id, id),
    ADD UNIQUE KEY uk_memory_revision_parent (student_id, supersedes_id);

UPDATE edu_learning_memory
SET content_hash = SHA2(content, 256)
WHERE content_hash IS NULL;

ALTER TABLE edu_learning_memory
    MODIFY COLUMN content_hash CHAR(64) NOT NULL;

CREATE TABLE edu_learning_evidence (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    student_id BIGINT NOT NULL,
    assessment_id BIGINT NOT NULL,
    submission_id BIGINT NOT NULL,
    answer_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    knowledge_point_id VARCHAR(64) NOT NULL,
    knowledge_point_name VARCHAR(200) NOT NULL,
    graph_version VARCHAR(100) NOT NULL,
    source_type VARCHAR(20) NOT NULL DEFAULT 'ASSESSMENT',
    source_id BIGINT NOT NULL,
    score_percent DECIMAL(7,4) NOT NULL,
    evidence_weight DECIMAL(8,2) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_learning_evidence_student FOREIGN KEY (student_id) REFERENCES edu_user (id),
    CONSTRAINT fk_learning_evidence_submission FOREIGN KEY (submission_id) REFERENCES edu_assessment_submission (id),
    CONSTRAINT fk_learning_evidence_answer FOREIGN KEY (answer_id) REFERENCES edu_submission_answer (id),
    UNIQUE KEY uk_learning_evidence_answer_kp (answer_id, knowledge_point_id),
    KEY idx_learning_evidence_student_kp_time (student_id, knowledge_point_id, occurred_at),
    KEY idx_learning_evidence_assessment (assessment_id, submission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_knowledge_mastery (
    student_id BIGINT NOT NULL,
    knowledge_point_id VARCHAR(64) NOT NULL,
    knowledge_point_name VARCHAR(200) NOT NULL,
    score DECIMAL(7,4) NOT NULL,
    confidence DECIMAL(5,4) NOT NULL,
    mastery_status VARCHAR(20) NOT NULL,
    evidence_count INT NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (student_id, knowledge_point_id),
    CONSTRAINT fk_knowledge_mastery_student_c5 FOREIGN KEY (student_id) REFERENCES edu_user (id),
    KEY idx_mastery_student_status (student_id, mastery_status, score)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_memory_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    dedupe_key VARCHAR(190) NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    memory_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    memory_version INT NOT NULL,
    sync_generation INT NOT NULL,
    rebuild_epoch VARCHAR(64),
    payload_json JSON,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_owner VARCHAR(100),
    lease_until DATETIME(3),
    last_error VARCHAR(1000),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_memory_outbox_memory FOREIGN KEY (memory_id) REFERENCES edu_learning_memory (id),
    CONSTRAINT fk_memory_outbox_student FOREIGN KEY (student_id) REFERENCES edu_user (id),
    UNIQUE KEY uk_memory_outbox_dedupe (dedupe_key),
    KEY idx_memory_outbox_claim (status, next_attempt_at, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_memory_index_rebuild (
    epoch VARCHAR(64) PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    job_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_memory_rebuild_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_profile_summary_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    result_json JSON,
    error_code VARCHAR(80),
    error_message VARCHAR(1000),
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_owner VARCHAR(100),
    lease_until DATETIME(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_profile_summary_session FOREIGN KEY (session_id) REFERENCES edu_tutor_session (id),
    CONSTRAINT fk_profile_summary_student FOREIGN KEY (student_id) REFERENCES edu_user (id),
    UNIQUE KEY uk_profile_summary_session (session_id),
    UNIQUE KEY uk_profile_summary_request (request_id),
    KEY idx_profile_summary_claim (status, next_attempt_at, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_profile_resource_suggestion (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    position_no INT NOT NULL,
    knowledge_point_id VARCHAR(64) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    preferred_resource_types_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_profile_resource_task FOREIGN KEY (task_id) REFERENCES edu_profile_summary_task (id),
    CONSTRAINT fk_profile_resource_session FOREIGN KEY (session_id) REFERENCES edu_tutor_session (id),
    CONSTRAINT fk_profile_resource_student FOREIGN KEY (student_id) REFERENCES edu_user (id),
    UNIQUE KEY uk_profile_resource_position (task_id, position_no),
    KEY idx_profile_resource_student_kp (student_id, knowledge_point_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
