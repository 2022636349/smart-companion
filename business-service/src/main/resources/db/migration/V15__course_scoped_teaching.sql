-- Course 1 is the historical Python course. Backfill is migration-only, never an API default.
ALTER TABLE edu_course
 ADD COLUMN subject_type VARCHAR(64) NOT NULL DEFAULT 'GENERAL',
 ADD COLUMN default_language VARCHAR(20) NOT NULL DEFAULT 'zh-CN',
 ADD COLUMN code_language VARCHAR(20) NULL,
 ADD COLUMN enabled_activity_types_json JSON NULL;
UPDATE edu_course SET subject_type='COMPUTER_SCIENCE',code_language='PYTHON',
 enabled_activity_types_json=JSON_ARRAY('EXPLAIN','QUIZ','CODE','VISUAL') WHERE id=1;
UPDATE edu_course SET enabled_activity_types_json=JSON_ARRAY('EXPLAIN','QUIZ','VISUAL') WHERE enabled_activity_types_json IS NULL;
ALTER TABLE edu_course MODIFY COLUMN enabled_activity_types_json JSON NOT NULL;

CREATE TABLE edu_course_teacher (
 course_id BIGINT NOT NULL, teacher_id BIGINT NOT NULL,
 member_role VARCHAR(20) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 joined_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(course_id,teacher_id),
 CONSTRAINT fk_course_teacher_course FOREIGN KEY(course_id) REFERENCES edu_course(id),
 CONSTRAINT fk_course_teacher_user FOREIGN KEY(teacher_id) REFERENCES edu_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO edu_course_teacher(course_id,teacher_id,member_role)
 SELECT DISTINCT course_id,teacher_id,'OWNER' FROM edu_class;

UPDATE edu_graph_ingestion_job SET course_id=1 WHERE course_id IS NULL;
ALTER TABLE edu_graph_ingestion_job MODIFY COLUMN course_id BIGINT NOT NULL;
ALTER TABLE edu_graph_release_rollback_outbox ADD COLUMN course_id BIGINT NULL;
UPDATE edu_graph_release_rollback_outbox SET course_id=1;
ALTER TABLE edu_graph_release_rollback_outbox MODIFY COLUMN course_id BIGINT NOT NULL;
ALTER TABLE edu_tutor_session ADD COLUMN course_id BIGINT NULL, ADD COLUMN graph_version VARCHAR(100) NULL;
UPDATE edu_tutor_session SET course_id=1;
ALTER TABLE edu_tutor_session MODIFY COLUMN course_id BIGINT NOT NULL, ADD KEY idx_tutor_course_student(course_id,student_id,updated_at);
ALTER TABLE edu_teaching_resource ADD COLUMN course_id BIGINT NULL;
UPDATE edu_teaching_resource SET course_id=1;
ALTER TABLE edu_teaching_resource MODIFY COLUMN course_id BIGINT NOT NULL, ADD KEY idx_resource_course(course_id,review_status);
ALTER TABLE edu_learning_memory ADD COLUMN course_id BIGINT NULL;
UPDATE edu_learning_memory SET course_id=1;
ALTER TABLE edu_learning_memory MODIFY COLUMN course_id BIGINT NOT NULL, ADD KEY idx_memory_course_student(course_id,student_id,status);
ALTER TABLE edu_learning_event ADD COLUMN course_id BIGINT NULL;
UPDATE edu_learning_event SET course_id=1;
ALTER TABLE edu_learning_event MODIFY COLUMN course_id BIGINT NOT NULL, ADD KEY idx_event_course_student(course_id,student_id,occurred_at);
ALTER TABLE edu_learning_evidence ADD COLUMN course_id BIGINT NULL;
UPDATE edu_learning_evidence SET course_id=1;
ALTER TABLE edu_learning_evidence MODIFY COLUMN course_id BIGINT NOT NULL, ADD KEY idx_evidence_course_student(course_id,student_id,knowledge_point_id);
ALTER TABLE edu_knowledge_mastery ADD COLUMN course_id BIGINT NULL;
UPDATE edu_knowledge_mastery SET course_id=1;
ALTER TABLE edu_knowledge_mastery MODIFY COLUMN course_id BIGINT NOT NULL,
 DROP PRIMARY KEY, ADD PRIMARY KEY(student_id,course_id,knowledge_point_id);
ALTER TABLE edu_cognitive_label ADD COLUMN course_id BIGINT NULL;
UPDATE edu_cognitive_label SET course_id=1;
ALTER TABLE edu_cognitive_label MODIFY COLUMN course_id BIGINT NOT NULL, ADD KEY idx_label_course_student(course_id,student_id);
ALTER TABLE edu_profile_resource_suggestion ADD COLUMN course_id BIGINT NULL;
UPDATE edu_profile_resource_suggestion SET course_id=1;
ALTER TABLE edu_profile_resource_suggestion MODIFY COLUMN course_id BIGINT NOT NULL;
