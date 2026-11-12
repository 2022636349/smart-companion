package com.education.agent.smartcompanion;

import static org.assertj.core.api.Assertions.*;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="RUN_ENGINE_KG_MYSQL",matches="1")
class CompanionKnowledgeMySqlMigrationTest {
  @Test void v18ReportSurvivesV19AndMappingPersists() throws Exception {
    String url=System.getenv("ENGINE_KG_MYSQL_URL");
    assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:33416/education_engine_kg_gate_[a-z0-9_]+.*");
    String password=System.getenv("ENGINE_KG_MYSQL_PASSWORD");
    try(var db=DriverManager.getConnection(url,"root",password);var sql=db.createStatement()) {
      try(var rows=sql.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")) {
        rows.next(); assertThat(rows.getInt(1)).isZero();
      }
      var before=Flyway.configure().dataSource(url,"root",password).locations("classpath:db/migration").target("18").load();
      assertThat(before.migrate().migrationsExecuted).isEqualTo(18);
      sql.executeUpdate("INSERT INTO edu_companion_import(id,course_id,sha256,detail_json,source_json,normalized_json) VALUES(1,1,REPEAT('a',64),'{\"report\":{\"scoredCount\":785}}','{}','{}')");
      var after=Flyway.configure().dataSource(url,"root",password).locations("classpath:db/migration").target("19").load();
      assertThat(after.migrate().migrationsExecuted).isEqualTo(1); after.validate();
      sql.executeUpdate("INSERT INTO edu_companion_knowledge_state(import_id,revision,graph_version,bindings_json,diagnoses_json) VALUES(1,1,'v1','[]','[]')");
      try(var rows=sql.executeQuery("SELECT detail_json,revision,graph_version FROM edu_companion_import i JOIN edu_companion_knowledge_state k ON i.id=k.import_id")) {
        assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("{\"report\":{\"scoredCount\":785}}");
        assertThat(rows.getLong(2)).isEqualTo(1); assertThat(rows.getString(3)).isEqualTo("v1");
      }
      assertThat(after.migrate().migrationsExecuted).isZero();
      var packages=Flyway.configure().dataSource(url,"root",password).locations("classpath:db/migration").target("20").load();
      assertThat(packages.migrate().migrationsExecuted).isEqualTo(1); packages.validate();
      try(var rows=sql.executeQuery("SELECT COUNT(*) FROM edu_companion_package")){rows.next();assertThat(rows.getInt(1)).isZero();}
    }
  }
}
