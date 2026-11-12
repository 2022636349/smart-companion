CREATE TABLE edu_companion_knowledge_state (
 import_id BIGINT NOT NULL PRIMARY KEY,
 revision BIGINT NOT NULL DEFAULT 0,
 graph_version VARCHAR(100) NOT NULL DEFAULT '',
 bindings_json LONGTEXT NOT NULL,
 diagnoses_json LONGTEXT NOT NULL,
 updated_at VARCHAR(64) NULL,
 CONSTRAINT fk_companion_knowledge_import FOREIGN KEY(import_id) REFERENCES edu_companion_import(id)
);
