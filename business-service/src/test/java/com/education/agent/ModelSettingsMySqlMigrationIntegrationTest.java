package com.education.agent;

import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_MODEL_SETTINGS_MYSQL", matches="1")
class ModelSettingsMySqlMigrationIntegrationTest {
    @Test void freshAndUpgradeRetainOldBusinessDataAndSeedExactlyTwoConfigurationSlots() throws Exception {
        String url=System.getenv("MODEL_SETTINGS_MYSQL_URL");
        if(url==null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:33339/model_settings_gate_[a-z0-9_]+"))
            throw new IllegalArgumentException("An isolated model-settings database is required");
        try(Connection db=DriverManager.getConnection(url,"root","")) {
            try(var statement=db.createStatement(); var rows=statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")) { rows.next();assertThat(rows.getInt(1)).isZero(); }
            var before=Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").target("15").load();
            assertThat(before.migrate().migrationsExecuted).isEqualTo(15);
            try(var statement=db.createStatement()) { statement.executeUpdate("INSERT INTO edu_user(username,password_hash,display_name,role) VALUES('migration_fixture','test-only','迁移保留验证','ADMIN')"); }
            var v16=Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").target("16").load();
            assertThat(v16.migrate().migrationsExecuted).isEqualTo(1);
            try(var statement=db.createStatement()) { statement.executeUpdate("UPDATE edu_model_settings SET encrypted_config='synthetic-v16-cipher',version_no=4 WHERE slot='DEFAULT'"); }
            var after=Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration").load();
            assertThat(after.migrate().migrationsExecuted).isEqualTo(1);after.validate();
            try(var statement=db.createStatement();var rows=statement.executeQuery("SELECT slot,version_no,encrypted_config,encrypted_connections FROM edu_model_settings ORDER BY slot")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("ANIMATION");assertThat(rows.getLong(2)).isZero();assertThat(rows.getString(3)).isNull();
                assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("DEFAULT");assertThat(rows.getLong(2)).isEqualTo(4);assertThat(rows.getString(3)).isEqualTo("synthetic-v16-cipher");assertThat(rows.getString(4)).isNull();assertThat(rows.next()).isFalse();
            }
            try(var statement=db.createStatement();var rows=statement.executeQuery("SELECT display_name FROM edu_user WHERE username='migration_fixture'")) { assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("迁移保留验证"); }
            assertThat(after.migrate().migrationsExecuted).isZero();
        }
    }
}
