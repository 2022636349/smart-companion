package com.education.agent.tutor.service;

import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.integration.ai.AiTutorActivityDraft;
import com.education.agent.integration.ai.AiTutorActivityDraft.AnimationSceneDraft;
import com.education.agent.integration.ai.AiTutorActivityDraft.CodeTestCaseDraft;
import com.education.agent.integration.ai.AiTutorActivityDraft.QuizQuestionDraft;
import com.education.agent.integration.ai.AiTutorActivityDraft.SceneEntityDraft;
import com.education.agent.integration.ai.AiTutorActivityDraft.SceneStepDraft;
import com.education.agent.tutor.dto.TutorActivityDtos.AnimationScene;
import com.education.agent.tutor.dto.TutorActivityDtos.ChoiceOption;
import com.education.agent.tutor.dto.TutorActivityDtos.CodeLabPayload;
import com.education.agent.tutor.dto.TutorActivityDtos.CodePublicTest;
import com.education.agent.tutor.dto.TutorActivityDtos.QuizPayload;
import com.education.agent.tutor.dto.TutorActivityDtos.QuizQuestion;
import com.education.agent.tutor.dto.TutorActivityDtos.SceneEntity;
import com.education.agent.tutor.dto.TutorActivityDtos.SceneStep;
import com.education.agent.tutor.model.TutorActivityType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class TutorActivityDraftValidator {
  private static final Pattern REF = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,79}$");
  private static final Pattern ENTITY_ID = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,39}$");
  private static final Pattern OPTION_KEY = Pattern.compile("^[A-F]$");
  private static final Pattern ACTIVE_CONTENT = Pattern.compile(
      "(?:<\\s*(?:script|iframe|object|embed)|javascript\\s*:|data\\s*:\\s*text/html|"
          + "on(?:load|error|click)\\s*=)", Pattern.CASE_INSENSITIVE);
  private static final Set<String> DIFFICULTIES = Set.of("BASIC", "MEDIUM", "ADVANCED");
  private static final Set<String> QUESTION_TYPES =
      Set.of("SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE", "SHORT_ANSWER");
  private static final Set<String> VISIBILITIES = Set.of("PUBLIC", "HIDDEN");
  private static final Set<String> COMPARE_MODES =
      Set.of("EXACT", "TRIM_TRAILING_WHITESPACE", "FLOAT_TOLERANCE");
  private static final Set<String> SCENE_TYPES =
      Set.of("ARRAY", "SEARCH", "SORT", "TREE", "GRAPH", "CODE_FLOW", "CONCEPT");
  private static final Set<String> ENTITY_KINDS =
      Set.of("ARRAY", "NODE", "EDGE", "POINTER", "CODE_LINE", "TEXT");
  private static final Set<String> STEP_ACTIONS =
      Set.of("HIGHLIGHT", "CLEAR_HIGHLIGHT", "MOVE_POINTER", "SET_VALUE", "SHOW", "HIDE",
          "FOCUS_CODE", "COMPARE", "ANNOTATE");
  private static final int MAX_TEST_TEXT_BYTES = 2 * 1024 * 1024;
  private static final int MAX_ACTIVITY_JSON_BYTES = 5 * 1024 * 1024;
  private static final BigDecimal MAX_DB_SCORE = new BigDecimal("999999.99");

  private final ObjectMapper json;

  public TutorActivityDraftValidator(ObjectMapper json) {
    this.json = json;
  }

  public PreparedActivities validate(
      List<AiTutorActivityDraft> drafts,
      Map<String, String> allowedKnowledgePoints,
      String graphVersion) {
    if (drafts == null || drafts.size() > 3) throw invalid("互动活动数量不合法");
    if (allowedKnowledgePoints == null || allowedKnowledgePoints.isEmpty()
        || graphVersion == null || graphVersion.isBlank() || graphVersion.length() > 100) {
      throw invalid("互动活动缺少权威知识图谱范围");
    }
    List<PreparedActivity> result = new ArrayList<>();
    Map<String, PreparedActivity> byRef = new LinkedHashMap<>();
    for (AiTutorActivityDraft draft : drafts) {
      PreparedActivity prepared = validateOne(draft, allowedKnowledgePoints, graphVersion);
      if (byRef.putIfAbsent(prepared.activityRef(), prepared) != null) {
        throw invalid("互动活动 activity_ref 重复");
      }
      result.add(prepared);
    }
    requireJsonBytes(drafts, MAX_ACTIVITY_JSON_BYTES, "互动活动草稿");
    return new PreparedActivities(List.copyOf(result), Map.copyOf(byRef));
  }

  private PreparedActivity validateOne(
      AiTutorActivityDraft draft, Map<String, String> allowedKnowledgePoints, String graphVersion) {
    if (draft == null || !validRef(draft.activityRef()) || blankOrTooLong(draft.title(), 200)
        || blankOrTooLong(draft.knowledgePointId(), 64)) {
      throw invalid("互动活动基础字段不合法");
    }
    String knowledgePointId = draft.knowledgePointId().trim();
    String knowledgePointName = allowedKnowledgePoints.get(knowledgePointId);
    if (knowledgePointName == null || knowledgePointName.isBlank() || knowledgePointName.length() > 200) {
      throw invalid("互动活动知识点不在本轮已审核上下文范围内");
    }
    TutorActivityType type;
    try {
      type = TutorActivityType.valueOf(draft.activityType());
    } catch (RuntimeException exception) {
      throw invalid("互动活动类型不受支持");
    }
    return switch (type) {
      case QUIZ -> prepareQuiz(draft, knowledgePointName, graphVersion);
      case CODE_LAB -> prepareCode(draft, knowledgePointName, graphVersion);
      case ANIMATION -> prepareAnimation(draft, knowledgePointName, graphVersion);
    };
  }

  private PreparedActivity prepareQuiz(
      AiTutorActivityDraft draft, String knowledgePointName, String graphVersion) {
    if (!DIFFICULTIES.contains(draft.difficulty()) || draft.questions() == null
        || draft.questions().isEmpty() || draft.questions().size() > 5
        || draft.language() != null || draft.stem() != null || draft.starterCode() != null
        || draft.referenceSolution() != null || draft.testCases() != null || draft.scene() != null) {
      throw invalid("客观题活动字段不合法");
    }
    Set<String> refs = new HashSet<>();
    List<QuizQuestion> publicQuestions = new ArrayList<>();
    BigDecimal maxScore = BigDecimal.ZERO;
    for (QuizQuestionDraft question : draft.questions()) {
      if (question == null || !validRef(question.questionRef())
          || !refs.add(question.questionRef()) || !QUESTION_TYPES.contains(question.questionType())
          || blankOrTooLong(question.stem(), 2000) || blankOrTooLong(question.explanation(), 4000)) {
        throw invalid("客观题题目结构不合法");
      }
      if ("SHORT_ANSWER".equals(question.questionType())) {
        validateShortAnswer(question);
        BigDecimal score = validScore(question.score());
        maxScore = maxScore.add(score);
        publicQuestions.add(new QuizQuestion(
            question.questionRef(), question.questionType(), question.stem().trim(), List.of(), score));
        continue;
      }
      if (question.options() == null || question.options().size() < 2 || question.options().size() > 6
          || question.correctOptionKeys() == null || question.correctOptionKeys().isEmpty()
          || question.correctOptionKeys().size() > 6
          || question.acceptedAnswers() == null || !question.acceptedAnswers().isEmpty()
          || question.caseSensitive() == null || Boolean.TRUE.equals(question.caseSensitive())) {
        throw invalid("选择题题目结构不合法");
      }
      Set<String> optionKeys = new HashSet<>();
      List<ChoiceOption> publicOptions = new ArrayList<>();
      question.options().forEach(option -> {
        if (option == null || option.key() == null || !OPTION_KEY.matcher(option.key()).matches()
            || !optionKeys.add(option.key()) || blankOrTooLong(option.text(), 1000)) {
          throw invalid("客观题选项不合法");
        }
        publicOptions.add(new ChoiceOption(option.key(), option.text().trim()));
      });
      Set<String> answers = new HashSet<>();
      for (String answer : question.correctOptionKeys()) {
        if (answer == null || !OPTION_KEY.matcher(answer).matches()
            || !answers.add(answer) || !optionKeys.contains(answer)) {
          throw invalid("客观题答案不属于唯一有效选项");
        }
      }
      if (("SINGLE_CHOICE".equals(question.questionType()) || "TRUE_FALSE".equals(question.questionType()))
          && answers.size() != 1) {
        throw invalid("单选或判断题必须且只能有一个答案");
      }
      if ("MULTIPLE_CHOICE".equals(question.questionType()) && answers.size() < 2) {
        throw invalid("多选题至少需要两个正确选项");
      }
      if ("TRUE_FALSE".equals(question.questionType()) && optionKeys.size() != 2) {
        throw invalid("判断题必须恰好包含两个选项");
      }
      if ("TRUE_FALSE".equals(question.questionType())
          && (!"A".equals(publicOptions.get(0).key()) || !"B".equals(publicOptions.get(1).key()))) {
        throw invalid("判断题选项必须按 A、B 排列");
      }
      BigDecimal score = validScore(question.score());
      maxScore = maxScore.add(score);
      publicQuestions.add(new QuizQuestion(
          question.questionRef(), question.questionType(), question.stem().trim(),
          publicOptions, score));
    }
    requireDbScore(maxScore, "客观题总分");
    if (maxScore.compareTo(BigDecimal.valueOf(100)) > 0) throw invalid("客观题总分不能超过 100");
    QuizPayload publicPayload = new QuizPayload(publicQuestions);
    requireJsonBytes(publicPayload, MAX_ACTIVITY_JSON_BYTES, "客观题公开载荷");
    return prepared(draft, knowledgePointName, graphVersion, TutorActivityType.QUIZ,
        publicPayload, maxScore);
  }

  private void validateShortAnswer(QuizQuestionDraft question) {
    if (question.options() == null || !question.options().isEmpty()
        || question.correctOptionKeys() == null || !question.correctOptionKeys().isEmpty()
        || question.acceptedAnswers() == null || question.acceptedAnswers().isEmpty()
        || question.acceptedAnswers().size() > 10 || question.caseSensitive() == null) {
      throw invalid("简答题题目结构不合法");
    }
    boolean caseSensitive = Boolean.TRUE.equals(question.caseSensitive());
    Set<String> normalized = new HashSet<>();
    for (String answer : question.acceptedAnswers()) {
      if (answer == null || answer.isBlank() || answer.length() > 500) {
        throw invalid("简答题标准答案不合法");
      }
      String canonical = answer.trim();
      if (!caseSensitive) canonical = canonical.toLowerCase(Locale.ROOT);
      if (!normalized.add(canonical)) throw invalid("简答题标准答案重复");
    }
  }

  private PreparedActivity prepareCode(
      AiTutorActivityDraft draft, String knowledgePointName, String graphVersion) {
    if (!DIFFICULTIES.contains(draft.difficulty()) || !"PYTHON".equals(draft.language())
        || blankOrTooLong(draft.stem(), 5000) || draft.starterCode() == null
        || draft.starterCode().length() > 65536 || blankOrTooLong(draft.referenceSolution(), 65536)
        || draft.testCases() == null || draft.testCases().size() < 2 || draft.testCases().size() > 10
        || draft.questions() != null || draft.scene() != null) {
      throw invalid("代码实验活动字段不合法");
    }
    Set<String> refs = new HashSet<>();
    List<CodePublicTest> publicTests = new ArrayList<>();
    BigDecimal maxScore = BigDecimal.ZERO;
    boolean hasPublic = false;
    boolean hasHidden = false;
    Set<List<String>> testFingerprints = new HashSet<>();
    int textBytes = 0;
    for (CodeTestCaseDraft test : draft.testCases()) {
      if (test == null || !validRef(test.caseRef()) || !refs.add(test.caseRef())
          || test.inputData() == null || test.inputData().length() > 65536
          || test.expectedOutput() == null || test.expectedOutput().length() > 65536
          || !VISIBILITIES.contains(test.visibility()) || !COMPARE_MODES.contains(test.compareMode())
          || !testFingerprints.add(List.of(test.inputData(), test.expectedOutput()))) {
        throw invalid("代码测试点不合法");
      }
      textBytes += test.inputData().getBytes(StandardCharsets.UTF_8).length;
      textBytes += test.expectedOutput().getBytes(StandardCharsets.UTF_8).length;
      if (textBytes > MAX_TEST_TEXT_BYTES) throw invalid("代码测试点文本超过 2 MiB");
      BigDecimal score = validScore(test.score());
      maxScore = maxScore.add(score);
      if ("PUBLIC".equals(test.visibility())) {
        hasPublic = true;
        publicTests.add(new CodePublicTest(
            test.caseRef(), test.inputData(), test.expectedOutput(), score));
      } else {
        hasHidden = true;
      }
    }
    if (!hasPublic || !hasHidden) throw invalid("代码活动必须同时包含公开和隐藏测试点");
    requireDbScore(maxScore, "代码活动总分");
    if (maxScore.compareTo(BigDecimal.valueOf(100)) > 0) throw invalid("代码活动总分不能超过 100");
    CodeLabPayload publicPayload = new CodeLabPayload(
        "PYTHON", draft.stem().trim(), draft.starterCode(), publicTests);
    requireJsonBytes(publicPayload, MAX_ACTIVITY_JSON_BYTES, "代码活动公开载荷");
    return prepared(draft, knowledgePointName, graphVersion, TutorActivityType.CODE_LAB,
        publicPayload, maxScore);
  }

  private PreparedActivity prepareAnimation(
      AiTutorActivityDraft draft, String knowledgePointName, String graphVersion) {
    if (draft.difficulty() != null || draft.questions() != null || draft.language() != null
        || draft.stem() != null || draft.starterCode() != null || draft.referenceSolution() != null
        || draft.testCases() != null || draft.scene() == null) {
      throw invalid("动画活动字段不合法");
    }
    AnimationSceneDraft scene = draft.scene();
    if (!SCENE_TYPES.contains(scene.sceneType()) || scene.entities() == null
        || scene.entities().isEmpty() || scene.entities().size() > 30 || scene.steps() == null
        || scene.steps().isEmpty() || scene.steps().size() > 30) {
      throw invalid("动画场景结构不合法");
    }
    Set<String> entityIds = new HashSet<>();
    Map<String, String> entityKinds = new LinkedHashMap<>();
    List<SceneEntity> publicEntities = new ArrayList<>();
    for (SceneEntityDraft entity : scene.entities()) {
      if (entity == null || entity.id() == null || !ENTITY_ID.matcher(entity.id()).matches()
          || !entityIds.add(entity.id()) || !ENTITY_KINDS.contains(entity.kind())
          || blankOrTooLong(entity.label(), 200) || entity.values() == null
          || entity.values().size() > 30
          || activeContent(entity.label())
          || entity.values().stream().anyMatch(value -> value == null || value.length() > 100
              || activeContent(value))) {
        throw invalid("动画实体不合法");
      }
      entityKinds.put(entity.id(), entity.kind());
      publicEntities.add(new SceneEntity(
          entity.id(), entity.kind(), entity.label().trim(), entity.values(),
          trimToNull(entity.fromId()), trimToNull(entity.toId())));
    }
    for (SceneEntityDraft entity : scene.entities()) {
      String from = trimToNull(entity.fromId());
      String to = trimToNull(entity.toId());
      if ((from != null && !entityIds.contains(from)) || (to != null && !entityIds.contains(to))
          || ("EDGE".equals(entity.kind()) && (from == null || to == null))
          || (!"EDGE".equals(entity.kind()) && (from != null || to != null))) {
        throw invalid("动画实体引用不存在");
      }
    }
    List<SceneStep> publicSteps = new ArrayList<>();
    Map<String, Integer> arrayLengths = new LinkedHashMap<>();
    publicEntities.stream().filter(e -> "ARRAY".equals(e.kind()))
        .forEach(e -> arrayLengths.put(e.id(), e.values().size()));
    for (int index = 0; index < scene.steps().size(); index++) {
      SceneStepDraft step = scene.steps().get(index);
      if (step == null || step.order() == null || step.order() != index + 1
          || !STEP_ACTIONS.contains(step.action()) || step.targetIds() == null
          || step.targetIds().isEmpty() || step.targetIds().size() > 10
          || new HashSet<>(step.targetIds()).size() != step.targetIds().size()
          || step.targetIds().stream().anyMatch(id -> id == null || !entityIds.contains(id))
          || (step.value() != null && step.value().length() > 500)
          || activeContent(step.value()) || activeContent(step.caption())
          || blankOrTooLong(step.caption(), 1000) || step.durationMs() == null
          || step.durationMs() < 200 || step.durationMs() > 10000
          || ("MOVE_POINTER".equals(step.action())
              && step.targetIds().stream().noneMatch(id -> "POINTER".equals(entityKinds.get(id))))
          || ("FOCUS_CODE".equals(step.action())
              && step.targetIds().stream().anyMatch(id -> !"CODE_LINE".equals(entityKinds.get(id))))
          || ("SET_VALUE".equals(step.action()) && trimToNull(step.value()) == null)) {
        throw invalid("动画步骤顺序或引用不合法");
      }
      List<String> arrays = step.targetIds().stream().filter(arrayLengths::containsKey).toList();
      if (step.indices() != null && !step.indices().isEmpty()) {
        if (arrays.size() != 1 || step.indices().size() > 30
            || new HashSet<>(step.indices()).size() != step.indices().size()
            || !Set.of("HIGHLIGHT", "CLEAR_HIGHLIGHT", "MOVE_POINTER", "SET_VALUE", "COMPARE", "ANNOTATE").contains(step.action())) {
          throw invalid("动画数组下标目标不合法");
        }
        int length = arrayLengths.get(arrays.get(0));
        if (step.indices().stream().anyMatch(i -> i == null || i < -length || i >= length)
            || (Set.of("MOVE_POINTER", "SET_VALUE").contains(step.action()) && step.indices().size() != 1)
            || ("MOVE_POINTER".equals(step.action()) && step.targetIds().stream().filter(id -> "POINTER".equals(entityKinds.get(id))).count() != 1)
            || ("SET_VALUE".equals(step.action()) && (step.targetIds().size() != 1 || step.value().length() > 100))) {
          throw invalid("动画数组下标越界或操作不合法");
        }
      } else if ("SET_VALUE".equals(step.action())) {
        for (String array : arrays) {
          String[] values = step.value().split(",", -1);
          if (values.length > 30 || java.util.Arrays.stream(values).anyMatch(v -> v.trim().length() > 100)) {
            throw invalid("动画数组更新值不合法");
          }
          arrayLengths.put(array, values.length);
        }
      }
      publicSteps.add(new SceneStep(
          step.order(), step.action(), step.targetIds(), trimToNull(step.value()),
          step.caption().trim(), step.durationMs(), step.indices()));
    }
    AnimationScene publicPayload = new AnimationScene(scene.sceneType(), publicEntities, publicSteps);
    requireJsonBytes(publicPayload, 256 * 1024, "动画场景");
    return prepared(draft, knowledgePointName, graphVersion, TutorActivityType.ANIMATION,
        publicPayload, BigDecimal.ZERO.setScale(2));
  }

  private PreparedActivity prepared(
      AiTutorActivityDraft draft,
      String knowledgePointName,
      String graphVersion,
      TutorActivityType type,
      Object publicPayload,
      BigDecimal maxScore) {
    requireJsonBytes(draft, MAX_ACTIVITY_JSON_BYTES, "互动活动私有载荷");
    return new PreparedActivity(
        draft.activityRef(), type, draft.title().trim(), draft.knowledgePointId().trim(),
        knowledgePointName, graphVersion, trimToNull(draft.difficulty()), publicPayload,
        draft, maxScore);
  }

  private BigDecimal validScore(BigDecimal value) {
    if (value == null || value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(100)) > 0
        || value.stripTrailingZeros().scale() > 2) {
      throw invalid("活动分值必须大于零、至多 100 且最多两位小数");
    }
    return value.setScale(2);
  }

  private void requireDbScore(BigDecimal value, String field) {
    if (value.signum() <= 0 || value.compareTo(MAX_DB_SCORE) > 0) throw invalid(field + "不合法");
  }

  private void requireJsonBytes(Object value, int maximum, String field) {
    try {
      if (json.writeValueAsBytes(value).length > maximum) throw invalid(field + "超过大小预算");
    } catch (JsonProcessingException exception) {
      throw invalid(field + "无法序列化");
    }
  }

  private boolean validRef(String value) {
    return value != null && REF.matcher(value).matches();
  }

  private boolean blankOrTooLong(String value, int maximum) {
    return value == null || value.isBlank() || value.length() > maximum;
  }

  private String trimToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private boolean activeContent(String value) {
    return value != null && ACTIVE_CONTENT.matcher(value).find();
  }

  private BusinessException invalid(String detail) {
    return new BusinessException(
        ErrorCode.AI_SERVICE_INVALID_RESPONSE,
        "AI 导师活动草稿未通过业务校验",
        Map.of("reason", detail));
  }

  public record PreparedActivities(
      List<PreparedActivity> items, Map<String, PreparedActivity> byRef) {}

  public record PreparedActivity(
      String activityRef,
      TutorActivityType activityType,
      String title,
      String knowledgePointId,
      String knowledgePointName,
      String graphVersion,
      String difficulty,
      Object publicPayload,
      AiTutorActivityDraft privatePayload,
      BigDecimal maxScore) {}
}
