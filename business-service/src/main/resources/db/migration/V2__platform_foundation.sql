CREATE TABLE edu_course (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_course_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO edu_course (id, code, name, description)
VALUES (1, 'PYTHON_PROGRAMMING', 'Python程序设计', '本期大创固定课程')
ON DUPLICATE KEY UPDATE name = VALUES(name);

ALTER TABLE edu_user
    ADD COLUMN email VARCHAR(255) NULL AFTER display_name,
    ADD COLUMN avatar_file_id BIGINT NULL AFTER email,
    ADD COLUMN force_password_change BOOLEAN NOT NULL DEFAULT FALSE AFTER status,
    ADD UNIQUE KEY uk_edu_user_email (email);

ALTER TABLE edu_class
    ADD COLUMN course_id BIGINT NULL AFTER id,
    ADD COLUMN description TEXT NULL AFTER course_name;

UPDATE edu_class SET course_id = 1 WHERE course_id IS NULL;

ALTER TABLE edu_class
    MODIFY COLUMN course_id BIGINT NOT NULL,
    ADD CONSTRAINT fk_edu_class_course FOREIGN KEY (course_id) REFERENCES edu_course (id),
    ADD KEY idx_edu_class_course_status (course_id, status);

ALTER TABLE edu_file_object
    ADD COLUMN purpose VARCHAR(40) NOT NULL DEFAULT 'TEACHING_RESOURCE' AFTER owner_id,
    ADD COLUMN deleted_at DATETIME(3) NULL AFTER status;

ALTER TABLE edu_teaching_resource
    ADD COLUMN description TEXT NULL AFTER title,
    ADD COLUMN review_comment TEXT NULL AFTER review_status,
    ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) AFTER created_at;

CREATE TABLE edu_refresh_token (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES edu_user (id),
    UNIQUE KEY uk_refresh_token_hash (token_hash),
    KEY idx_refresh_token_user_active (user_id, revoked_at, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_ai_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_type VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    owner_id BIGINT NOT NULL,
    resource_type VARCHAR(50),
    resource_id BIGINT,
    request_id VARCHAR(64) NOT NULL,
    progress_percent INT NOT NULL DEFAULT 0,
    request_json JSON,
    result_json JSON,
    error_code VARCHAR(80),
    error_message TEXT,
    model_version VARCHAR(100),
    prompt_version VARCHAR(100),
    attempt_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_at DATETIME(3),
    finished_at DATETIME(3),
    CONSTRAINT fk_ai_task_owner FOREIGN KEY (owner_id) REFERENCES edu_user (id),
    UNIQUE KEY uk_ai_task_request (request_id),
    KEY idx_ai_task_owner_status (owner_id, status),
    KEY idx_ai_task_resource (resource_type, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_outbox_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_type VARCHAR(80) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    available_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at DATETIME(3),
    last_error TEXT,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_outbox_delivery (status, available_at),
    KEY idx_outbox_aggregate (aggregate_type, aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    action VARCHAR(80) NOT NULL,
    target_type VARCHAR(50),
    target_id VARCHAR(64),
    operator_id BIGINT,
    ip_address VARCHAR(64),
    severity VARCHAR(20) NOT NULL DEFAULT 'INFO',
    success BOOLEAN NOT NULL,
    request_id VARCHAR(64),
    details_json JSON,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_audit_operator FOREIGN KEY (operator_id) REFERENCES edu_user (id),
    KEY idx_audit_operator_time (operator_id, created_at),
    KEY idx_audit_action_time (action, created_at),
    KEY idx_audit_request_id (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_learning_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    student_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    title VARCHAR(200) NOT NULL,
    summary TEXT,
    knowledge_point_ids_json JSON,
    related_type VARCHAR(50),
    related_id BIGINT,
    score DECIMAL(8,2),
    occurred_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_learning_event_student FOREIGN KEY (student_id) REFERENCES edu_user (id),
    KEY idx_learning_event_student_time (student_id, occurred_at),
    KEY idx_learning_event_type (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_learning_memory (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    student_id BIGINT NOT NULL,
    memory_type VARCHAR(40) NOT NULL,
    knowledge_point_id VARCHAR(64),
    title VARCHAR(200),
    content TEXT NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_id BIGINT,
    confidence DECIMAL(5,4),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version_no INT NOT NULL DEFAULT 1,
    supersedes_id BIGINT,
    editable BOOLEAN NOT NULL DEFAULT TRUE,
    deletable BOOLEAN NOT NULL DEFAULT TRUE,
    expires_at DATETIME(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_learning_memory_student FOREIGN KEY (student_id) REFERENCES edu_user (id),
    CONSTRAINT fk_learning_memory_previous FOREIGN KEY (supersedes_id) REFERENCES edu_learning_memory (id),
    KEY idx_memory_student_status (student_id, status, updated_at),
    KEY idx_memory_student_kp (student_id, knowledge_point_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_memory_preference (
    student_id BIGINT PRIMARY KEY,
    long_term_memory_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    tutor_personalization_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    allow_weakness_memory BOOLEAN NOT NULL DEFAULT TRUE,
    allow_preference_memory BOOLEAN NOT NULL DEFAULT TRUE,
    retention_days INT NOT NULL DEFAULT 365,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_memory_preference_student FOREIGN KEY (student_id) REFERENCES edu_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_resource_ingestion (
    resource_id BIGINT PRIMARY KEY,
    task_id BIGINT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    parser_version VARCHAR(100),
    embedding_model VARCHAR(100),
    collection_name VARCHAR(80),
    chunk_count INT NOT NULL DEFAULT 0,
    progress_percent INT NOT NULL DEFAULT 0,
    warnings_json JSON,
    error_code VARCHAR(80),
    error_message TEXT,
    started_at DATETIME(3),
    finished_at DATETIME(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ingestion_resource FOREIGN KEY (resource_id) REFERENCES edu_teaching_resource (id),
    CONSTRAINT fk_ingestion_task FOREIGN KEY (task_id) REFERENCES edu_ai_task (id),
    KEY idx_resource_ingestion_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_resource_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    resource_id BIGINT NOT NULL,
    stable_chunk_id VARCHAR(160) NOT NULL,
    sequence_no INT NOT NULL,
    chapter VARCHAR(300),
    page_from INT,
    page_to INT,
    locator VARCHAR(500) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    knowledge_point_ids_json JSON NOT NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_resource_chunk_resource FOREIGN KEY (resource_id) REFERENCES edu_teaching_resource (id),
    UNIQUE KEY uk_resource_chunk_stable (stable_chunk_id),
    UNIQUE KEY uk_resource_chunk_sequence (resource_id, sequence_no),
    KEY idx_resource_chunk_review (resource_id, review_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_idempotency_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    operation_id VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_status INT,
    response_json JSON,
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id) REFERENCES edu_user (id),
    UNIQUE KEY uk_idempotency_scope (user_id, operation_id, idempotency_key),
    KEY idx_idempotency_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_notification (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    notification_type VARCHAR(50) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    related_type VARCHAR(50),
    related_id BIGINT,
    action_path VARCHAR(500),
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    read_at DATETIME(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES edu_user (id),
    KEY idx_notification_user_read (user_id, is_read, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_user_feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    category VARCHAR(30) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    attachment_file_ids_json JSON,
    page_path VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    admin_reply TEXT,
    handled_by BIGINT,
    handled_at DATETIME(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_feedback_user FOREIGN KEY (user_id) REFERENCES edu_user (id),
    CONSTRAINT fk_feedback_handler FOREIGN KEY (handled_by) REFERENCES edu_user (id),
    KEY idx_feedback_status_time (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_model_call_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(64) NOT NULL,
    task_id BIGINT,
    user_id BIGINT,
    capability VARCHAR(60) NOT NULL,
    model_version VARCHAR(100),
    prompt_version VARCHAR(100),
    duration_ms INT,
    input_units INT,
    output_units INT,
    success BOOLEAN NOT NULL,
    error_code VARCHAR(80),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_model_call_task FOREIGN KEY (task_id) REFERENCES edu_ai_task (id),
    CONSTRAINT fk_model_call_user FOREIGN KEY (user_id) REFERENCES edu_user (id),
    KEY idx_model_call_request (request_id),
    KEY idx_model_call_capability_time (capability, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
