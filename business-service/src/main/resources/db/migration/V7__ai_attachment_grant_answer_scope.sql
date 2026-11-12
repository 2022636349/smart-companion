ALTER TABLE edu_ai_task_file_grant
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (task_id, answer_id, file_id);
