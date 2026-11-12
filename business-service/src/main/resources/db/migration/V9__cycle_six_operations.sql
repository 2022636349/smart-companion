ALTER TABLE edu_notification ADD COLUMN notification_group_id VARCHAR(64) NULL;
ALTER TABLE edu_notification ADD COLUMN dedupe_key VARCHAR(190) NULL;
ALTER TABLE edu_notification ADD CONSTRAINT uk_notification_user_dedupe UNIQUE (user_id, dedupe_key);
CREATE INDEX idx_notification_group ON edu_notification (notification_group_id);

CREATE TABLE edu_notification_publication (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    notification_group_id VARCHAR(64) NOT NULL,
    publisher_id BIGINT NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    request_json JSON NOT NULL,
    created_count INT NOT NULL DEFAULT 0,
    response_json JSON NULL,
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_notification_publication_user FOREIGN KEY (publisher_id) REFERENCES edu_user (id),
    CONSTRAINT uk_notification_publication_group UNIQUE (notification_group_id),
    CONSTRAINT uk_notification_publication_key UNIQUE (publisher_id, idempotency_key),
    KEY idx_notification_publication_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_feedback_attachment (
    feedback_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    position_no INT NOT NULL,
    PRIMARY KEY (feedback_id, file_id),
    CONSTRAINT fk_feedback_attachment_feedback FOREIGN KEY (feedback_id) REFERENCES edu_user_feedback (id),
    CONSTRAINT fk_feedback_attachment_file FOREIGN KEY (file_id) REFERENCES edu_file_object (id),
    CONSTRAINT uk_feedback_attachment_position UNIQUE (feedback_id, position_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE edu_model_call_log ADD COLUMN invocation_id VARCHAR(64) NULL;
ALTER TABLE edu_model_call_log ADD COLUMN provider VARCHAR(40) NULL;
ALTER TABLE edu_model_call_log ADD COLUMN model VARCHAR(100) NULL;
ALTER TABLE edu_model_call_log ADD COLUMN attempts INT NULL;
ALTER TABLE edu_model_call_log ADD COLUMN provider_request_id VARCHAR(200) NULL;
ALTER TABLE edu_model_call_log ADD COLUMN outcome VARCHAR(20) NULL;
ALTER TABLE edu_model_call_log ADD COLUMN pricing_version VARCHAR(40) NULL;
ALTER TABLE edu_model_call_log ADD COLUMN estimated_cost_microunits BIGINT NULL;
ALTER TABLE edu_model_call_log ADD COLUMN currency CHAR(3) NULL;
ALTER TABLE edu_model_call_log ADD COLUMN occurred_at DATETIME(3) NULL;
ALTER TABLE edu_model_call_log ADD COLUMN payload_hash CHAR(64) NULL;
ALTER TABLE edu_model_call_log ADD CONSTRAINT uk_model_call_invocation UNIQUE (invocation_id);
CREATE INDEX idx_model_call_occurred ON edu_model_call_log (occurred_at);

CREATE TABLE edu_rate_limit_bucket (
    bucket_key VARCHAR(190) NOT NULL,
    window_started_at DATETIME(3) NOT NULL,
    request_count INT NOT NULL DEFAULT 0,
    expires_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (bucket_key, window_started_at),
    KEY idx_rate_limit_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_maintenance_lease (
    job_name VARCHAR(100) PRIMARY KEY,
    lease_owner VARCHAR(100) NULL,
    lease_until DATETIME(3) NULL,
    heartbeat_at DATETIME(3) NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_maintenance_lease_until (lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE edu_maintenance_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    job_name VARCHAR(100) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    lease_owner VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    scanned_count BIGINT NOT NULL DEFAULT 0,
    affected_count BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(80) NULL,
    error_message VARCHAR(1000) NULL,
    details_json JSON NULL,
    request_id VARCHAR(64) NULL,
    started_at DATETIME(3) NOT NULL,
    finished_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_maintenance_run_id UNIQUE (run_id),
    KEY idx_maintenance_run_job_time (job_name, started_at),
    KEY idx_maintenance_run_status_time (status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
