CREATE TABLE IF NOT EXISTS edu_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_edu_user_username (username),
    KEY idx_edu_user_role_status (role, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_class (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    course_name VARCHAR(100) NOT NULL DEFAULT 'Python程序设计',
    teacher_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_edu_class_teacher FOREIGN KEY (teacher_id) REFERENCES edu_user (id),
    KEY idx_edu_class_teacher (teacher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_class_student (
    class_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    joined_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (class_id, student_id),
    CONSTRAINT fk_class_student_class FOREIGN KEY (class_id) REFERENCES edu_class (id),
    CONSTRAINT fk_class_student_user FOREIGN KEY (student_id) REFERENCES edu_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_file_object (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    original_name VARCHAR(255) NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    content_type VARCHAR(120),
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    owner_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_file_owner FOREIGN KEY (owner_id) REFERENCES edu_user (id),
    UNIQUE KEY uk_file_storage_key (storage_key),
    KEY idx_file_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_assessment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    assessment_type VARCHAR(20) NOT NULL,
    class_id BIGINT NOT NULL,
    creator_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    total_score DECIMAL(8,2) NOT NULL DEFAULT 0,
    start_at DATETIME(3),
    due_at DATETIME(3),
    duration_minutes INT,
    auto_grade_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    auto_release_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ai_attachment_grade_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    result_visibility_json JSON,
    anti_cheat_policy_json JSON,
    current_version INT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_assessment_class FOREIGN KEY (class_id) REFERENCES edu_class (id),
    CONSTRAINT fk_assessment_creator FOREIGN KEY (creator_id) REFERENCES edu_user (id),
    KEY idx_assessment_class_status (class_id, status),
    KEY idx_assessment_due_at (due_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_assessment_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    assessment_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    snapshot_json JSON NOT NULL,
    published_by BIGINT NOT NULL,
    published_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_version_assessment FOREIGN KEY (assessment_id) REFERENCES edu_assessment (id),
    CONSTRAINT fk_version_publisher FOREIGN KEY (published_by) REFERENCES edu_user (id),
    UNIQUE KEY uk_assessment_version (assessment_id, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_assessment_question (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    assessment_id BIGINT NOT NULL,
    question_type VARCHAR(30) NOT NULL,
    position_no INT NOT NULL,
    stem MEDIUMTEXT NOT NULL,
    score DECIMAL(8,2) NOT NULL,
    reference_answer_json JSON,
    programming_config_json JSON,
    attachment_policy_json JSON,
    ai_generated BOOLEAN NOT NULL DEFAULT FALSE,
    ai_confidence DECIMAL(5,4),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_question_assessment FOREIGN KEY (assessment_id) REFERENCES edu_assessment (id),
    UNIQUE KEY uk_question_position (assessment_id, position_no),
    KEY idx_question_type (question_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_question_option (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    question_id BIGINT NOT NULL,
    option_key VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    is_correct BOOLEAN NOT NULL DEFAULT FALSE,
    position_no INT NOT NULL,
    CONSTRAINT fk_option_question FOREIGN KEY (question_id) REFERENCES edu_assessment_question (id),
    UNIQUE KEY uk_question_option_key (question_id, option_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_question_rubric_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    question_id BIGINT NOT NULL,
    label VARCHAR(100) NOT NULL,
    description TEXT NOT NULL,
    max_score DECIMAL(8,2) NOT NULL,
    position_no INT NOT NULL,
    CONSTRAINT fk_rubric_question FOREIGN KEY (question_id) REFERENCES edu_assessment_question (id),
    KEY idx_rubric_question (question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_programming_test_case (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    question_id BIGINT NOT NULL,
    input_data MEDIUMTEXT,
    expected_output MEDIUMTEXT NOT NULL,
    score DECIMAL(8,2) NOT NULL,
    visibility VARCHAR(20) NOT NULL DEFAULT 'HIDDEN',
    compare_mode VARCHAR(30) NOT NULL DEFAULT 'EXACT',
    time_limit_ms INT NOT NULL DEFAULT 2000,
    position_no INT NOT NULL,
    CONSTRAINT fk_test_case_question FOREIGN KEY (question_id) REFERENCES edu_assessment_question (id),
    KEY idx_test_case_question_visibility (question_id, visibility)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_question_knowledge_point (
    question_id BIGINT NOT NULL,
    knowledge_point_id VARCHAR(64) NOT NULL,
    knowledge_point_name VARCHAR(200) NOT NULL,
    binding_source VARCHAR(20) NOT NULL DEFAULT 'TEACHER',
    PRIMARY KEY (question_id, knowledge_point_id),
    CONSTRAINT fk_question_kp_question FOREIGN KEY (question_id) REFERENCES edu_assessment_question (id),
    KEY idx_question_kp_id (knowledge_point_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_assessment_import_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    assessment_id BIGINT NOT NULL,
    source_file_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    result_json JSON,
    error_code VARCHAR(80),
    error_message TEXT,
    model_version VARCHAR(100),
    prompt_version VARCHAR(100),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at DATETIME(3),
    CONSTRAINT fk_import_assessment FOREIGN KEY (assessment_id) REFERENCES edu_assessment (id),
    CONSTRAINT fk_import_file FOREIGN KEY (source_file_id) REFERENCES edu_file_object (id),
    UNIQUE KEY uk_import_request_id (request_id),
    KEY idx_import_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_assessment_submission (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    assessment_id BIGINT NOT NULL,
    assessment_version INT NOT NULL,
    student_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'IN_PROGRESS',
    attempt_no INT NOT NULL DEFAULT 1,
    started_at DATETIME(3),
    submitted_at DATETIME(3),
    auto_score DECIMAL(8,2),
    final_score DECIMAL(8,2),
    grading_status VARCHAR(30) NOT NULL DEFAULT 'NOT_STARTED',
    result_released_at DATETIME(3),
    overall_comment TEXT,
    suspicious BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_submission_assessment FOREIGN KEY (assessment_id) REFERENCES edu_assessment (id),
    CONSTRAINT fk_submission_student FOREIGN KEY (student_id) REFERENCES edu_user (id),
    UNIQUE KEY uk_submission_attempt (assessment_id, student_id, attempt_no),
    KEY idx_submission_assessment_status (assessment_id, status),
    KEY idx_submission_student (student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_submission_answer (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    submission_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    answer_json JSON,
    code_text MEDIUMTEXT,
    saved_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    submitted_at DATETIME(3),
    auto_score DECIMAL(8,2),
    final_score DECIMAL(8,2),
    grading_status VARCHAR(30) NOT NULL DEFAULT 'NOT_STARTED',
    teacher_comment TEXT,
    CONSTRAINT fk_answer_submission FOREIGN KEY (submission_id) REFERENCES edu_assessment_submission (id),
    CONSTRAINT fk_answer_question FOREIGN KEY (question_id) REFERENCES edu_assessment_question (id),
    UNIQUE KEY uk_submission_question (submission_id, question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_submission_attachment (
    answer_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    PRIMARY KEY (answer_id, file_id),
    CONSTRAINT fk_attachment_answer FOREIGN KEY (answer_id) REFERENCES edu_submission_answer (id),
    CONSTRAINT fk_attachment_file FOREIGN KEY (file_id) REFERENCES edu_file_object (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_code_run_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    answer_id BIGINT,
    student_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    run_type VARCHAR(20) NOT NULL,
    source_hash CHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    score DECIMAL(8,2),
    result_json JSON NOT NULL,
    duration_ms INT,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_code_run_answer FOREIGN KEY (answer_id) REFERENCES edu_submission_answer (id),
    CONSTRAINT fk_code_run_student FOREIGN KEY (student_id) REFERENCES edu_user (id),
    CONSTRAINT fk_code_run_question FOREIGN KEY (question_id) REFERENCES edu_assessment_question (id),
    KEY idx_code_run_question_student (question_id, student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_grading_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    answer_id BIGINT NOT NULL,
    grading_source VARCHAR(20) NOT NULL,
    suggested_score DECIMAL(8,2),
    final_score DECIMAL(8,2),
    evidence_json JSON,
    confidence DECIMAL(5,4),
    model_version VARCHAR(100),
    prompt_version VARCHAR(100),
    graded_by BIGINT,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_grading_answer FOREIGN KEY (answer_id) REFERENCES edu_submission_answer (id),
    CONSTRAINT fk_grading_user FOREIGN KEY (graded_by) REFERENCES edu_user (id),
    KEY idx_grading_answer (answer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_anti_cheat_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    submission_id BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    severity VARCHAR(20) NOT NULL DEFAULT 'INFO',
    event_at DATETIME(3) NOT NULL,
    details_json JSON,
    CONSTRAINT fk_anti_cheat_submission FOREIGN KEY (submission_id) REFERENCES edu_assessment_submission (id),
    KEY idx_anti_cheat_submission_time (submission_id, event_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_teaching_resource (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(200) NOT NULL,
    resource_type VARCHAR(30) NOT NULL,
    file_id BIGINT,
    external_url VARCHAR(1000),
    difficulty VARCHAR(20),
    review_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    uploader_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_resource_file FOREIGN KEY (file_id) REFERENCES edu_file_object (id),
    CONSTRAINT fk_resource_uploader FOREIGN KEY (uploader_id) REFERENCES edu_user (id),
    KEY idx_resource_type_status (resource_type, review_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_resource_knowledge_point (
    resource_id BIGINT NOT NULL,
    knowledge_point_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (resource_id, knowledge_point_id),
    CONSTRAINT fk_resource_kp_resource FOREIGN KEY (resource_id) REFERENCES edu_teaching_resource (id),
    KEY idx_resource_kp_id (knowledge_point_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_student_knowledge_mastery (
    student_id BIGINT NOT NULL,
    knowledge_point_id VARCHAR(64) NOT NULL,
    mastery_score DECIMAL(5,2) NOT NULL DEFAULT 0,
    confidence DECIMAL(5,4) NOT NULL DEFAULT 0,
    evidence_count INT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (student_id, knowledge_point_id),
    CONSTRAINT fk_mastery_student FOREIGN KEY (student_id) REFERENCES edu_user (id),
    KEY idx_mastery_knowledge_point (knowledge_point_id, mastery_score)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_tutor_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    student_id BIGINT NOT NULL,
    knowledge_point_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    summary TEXT,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_tutor_session_student FOREIGN KEY (student_id) REFERENCES edu_user (id),
    KEY idx_tutor_student_kp (student_id, knowledge_point_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_tutor_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    message_role VARCHAR(20) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    content_blocks_json JSON,
    sources_json JSON,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_tutor_message_session FOREIGN KEY (session_id) REFERENCES edu_tutor_session (id),
    KEY idx_tutor_message_session_time (session_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS edu_cognitive_label (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    student_id BIGINT NOT NULL,
    knowledge_point_id VARCHAR(64) NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    source_id BIGINT,
    label_status VARCHAR(20) NOT NULL,
    confidence DECIMAL(5,4) NOT NULL,
    evidence TEXT,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_cognitive_label_student FOREIGN KEY (student_id) REFERENCES edu_user (id),
    KEY idx_cognitive_student_kp (student_id, knowledge_point_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

