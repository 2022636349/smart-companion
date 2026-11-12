package com.education.agent.tutor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TutorRouterContextServiceTest {
  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:router_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("""
        CREATE TABLE edu_knowledge_mastery(course_id BIGINT NOT NULL,
          student_id BIGINT NOT NULL,
          knowledge_point_id VARCHAR(64) NOT NULL,
          score DECIMAL(8,2) NOT NULL,
          confidence DECIMAL(5,4) NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          PRIMARY KEY(student_id,course_id,knowledge_point_id)
        )
        """);
  }

  @Test
  void fixedIsBackwardCompatibleAndSendsNoMasteryProjection() {
    TutorRouterContextService service = new TutorRouterContextService(jdbc, "fixed");
    assertThat(service.forStudent(7L, 1L, "kp").agentMode()).isEqualTo("FIXED");
    assertThat(service.forStudent(7L, 1L, "kp").masteryContext()).isEmpty();
  }

  @Test
  void routerReadsOnlyCurrentStudentAndKeepsCurrentKnowledgePointFirst() {
    jdbc.update("INSERT INTO edu_knowledge_mastery VALUES(1,1,'other',70,0.6,CURRENT_TIMESTAMP)");
    jdbc.update("INSERT INTO edu_knowledge_mastery VALUES(1,1,'current',80,0.9,CURRENT_TIMESTAMP)");
    jdbc.update("INSERT INTO edu_knowledge_mastery VALUES(1,2,'foreign',99,1,CURRENT_TIMESTAMP)");
    TutorRouterContextService service = new TutorRouterContextService(jdbc, "ROUTER");
    var context = service.forStudent(1L, 1L, "current");
    assertThat(context.agentMode()).isEqualTo("ROUTER");
    assertThat(context.masteryContext()).extracting(value -> value.knowledgePointId())
        .containsExactly("current", "other")
        .doesNotContain("foreign");
    assertThat(context.masteryContext().get(0).score()).isEqualByComparingTo("80");
  }

  @Test
  void masteryProjectionHasHardHundredItemBudget() {
    for (int index = 0; index < 101; index++) {
      jdbc.update("INSERT INTO edu_knowledge_mastery VALUES(1,1,?,?,?,CURRENT_TIMESTAMP)",
          "kp-" + index, index % 101, 0.5);
    }
    TutorRouterContextService service = new TutorRouterContextService(jdbc, "SHADOW");
    assertThat(service.forStudent(1L, 1L, "kp-100").masteryContext()).hasSize(100);
  }

  @Test
  void illegalServerModeFailsAtConstructionInsteadOfTrustingARequest() {
    assertThatThrownBy(() -> new TutorRouterContextService(jdbc, "react-unbounded"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FIXED, ROUTER, or SHADOW");
  }
}
