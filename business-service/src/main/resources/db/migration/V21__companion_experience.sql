CREATE TABLE edu_companion_workspace (
 course_id BIGINT NOT NULL PRIMARY KEY,
 revision BIGINT NOT NULL DEFAULT 0,
 state_json LONGTEXT NOT NULL
);
CREATE TABLE edu_companion_session (
 token_hash CHAR(64) NOT NULL PRIMARY KEY,
 course_id BIGINT NOT NULL,
 expires_at BIGINT NOT NULL,
 view_name VARCHAR(16) NOT NULL,
 student_id VARCHAR(200) NULL
);
