CREATE TABLE edu_companion_package (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 course_id BIGINT NOT NULL,
 owner_id BIGINT NOT NULL,
 sha256 CHAR(64) NOT NULL,
 archive_key VARCHAR(500) NOT NULL,
 detail_json LONGTEXT NOT NULL,
 started TINYINT NOT NULL DEFAULT 0,
 source_label VARCHAR(500) NOT NULL DEFAULT '',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_companion_package_hash(course_id,sha256),
 CONSTRAINT fk_companion_package_course FOREIGN KEY(course_id) REFERENCES edu_course(id),
 CONSTRAINT fk_companion_package_owner FOREIGN KEY(owner_id) REFERENCES edu_user(id)
);
CREATE TABLE edu_companion_package_document (
 package_id BIGINT NOT NULL,
 entry_no INT NOT NULL,
 path VARCHAR(1000) NOT NULL,
 file_id BIGINT NULL,
 job_id BIGINT NULL,
 PRIMARY KEY(package_id,entry_no),
 KEY idx_companion_package_file(file_id),
 CONSTRAINT fk_companion_document_package FOREIGN KEY(package_id) REFERENCES edu_companion_package(id),
 CONSTRAINT fk_companion_document_file FOREIGN KEY(file_id) REFERENCES edu_file_object(id),
 CONSTRAINT fk_companion_document_job FOREIGN KEY(job_id) REFERENCES edu_graph_ingestion_job(id)
);
