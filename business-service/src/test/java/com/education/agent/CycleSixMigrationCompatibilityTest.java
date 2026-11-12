package com.education.agent;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import static org.assertj.core.api.Assertions.assertThat;

class CycleSixMigrationCompatibilityTest {
 @Test
 void v9RunsOnH2MySqlCompatibilityModeAndCreatesSharedOperationsSchema() throws Exception {
  try(Connection connection=DriverManager.getConnection("jdbc:h2:mem:c6_v9_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE")){
   try(Statement statement=connection.createStatement()){
    statement.execute("CREATE TABLE edu_user(id BIGINT PRIMARY KEY)");
    statement.execute("CREATE TABLE edu_file_object(id BIGINT PRIMARY KEY)");
    statement.execute("CREATE TABLE edu_user_feedback(id BIGINT PRIMARY KEY)");
    statement.execute("CREATE TABLE edu_notification(id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL)");
    statement.execute("CREATE TABLE edu_model_call_log(id BIGINT PRIMARY KEY)");
   }
   String sql=new String(CycleSixMigrationCompatibilityTest.class.getResourceAsStream(
     "/db/migration/V9__cycle_six_operations.sql").readAllBytes(), StandardCharsets.UTF_8);
   try(Statement statement=connection.createStatement()){
    for(String command:sql.split(";")){if(!command.isBlank())statement.execute(command);}
   }
   DatabaseMetaData meta=connection.getMetaData();
   assertThat(exists(meta,"TABLE","edu_rate_limit_bucket")).isTrue();
   assertThat(exists(meta,"TABLE","edu_maintenance_lease")).isTrue();
   assertThat(exists(meta,"TABLE","edu_maintenance_run")).isTrue();
   assertThat(exists(meta,"TABLE","edu_notification_publication")).isTrue();
   assertThat(exists(meta,"COLUMN","edu_model_call_log","invocation_id")).isTrue();
   assertThat(exists(meta,"COLUMN","edu_notification","dedupe_key")).isTrue();
  }
 }
 private boolean exists(DatabaseMetaData meta,String type,String...names)throws SQLException{
  try(ResultSet rows="TABLE".equals(type)?meta.getTables(null,null,names[0],new String[]{"TABLE"}):meta.getColumns(null,null,names[0],names[1])){return rows.next();}
 }
}
