CREATE TABLE edu_companion_import (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 course_id BIGINT NOT NULL,
 sha256 CHAR(64) NOT NULL,
 detail_json LONGTEXT NOT NULL,
 source_json LONGTEXT NOT NULL,
 normalized_json LONGTEXT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_companion_course_hash(course_id,sha256),
 CONSTRAINT fk_companion_course FOREIGN KEY(course_id) REFERENCES edu_course(id)
);
