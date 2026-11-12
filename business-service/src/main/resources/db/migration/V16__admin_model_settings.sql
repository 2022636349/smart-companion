CREATE TABLE edu_model_settings (
    slot VARCHAR(20) NOT NULL PRIMARY KEY,
    version_no BIGINT NOT NULL DEFAULT 0,
    encrypted_config TEXT NULL,
    updated_by BIGINT NULL,
    updated_at DATETIME(3) NULL
);
INSERT INTO edu_model_settings (slot, version_no) VALUES ('DEFAULT', 0), ('ANIMATION', 0);
