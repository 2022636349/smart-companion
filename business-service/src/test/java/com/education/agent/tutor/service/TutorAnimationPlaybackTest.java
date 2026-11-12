package com.education.agent.tutor.service;

import com.education.agent.common.error.BusinessException;
import com.education.agent.integration.ai.AiTutorActivityDraft;
import com.education.agent.tutor.dto.TutorActivityDtos.AnimationScene;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class TutorAnimationPlaybackTest {
  private final ObjectMapper json = new ObjectMapper();
  private final TutorActivityDraftValidator validator = new TutorActivityDraftValidator(json);

  private AnimationScene validate(String steps) throws Exception {
    AiTutorActivityDraft draft = json.readValue("""
        {"activity_ref":"animation_test","activity_type":"ANIMATION","title":"列表访问",
         "knowledge_point_id":"kp-test","scene":{"scene_type":"ARRAY","entities":[
           {"id":"a","kind":"ARRAY","label":"values","values":["10","20","30"]},
           {"id":"p","kind":"POINTER","label":"index","values":[]},
           {"id":"r","kind":"TEXT","label":"result","values":[]}
         ],"steps":[%s]}}
        """.formatted(steps), AiTutorActivityDraft.class);
    return (AnimationScene) validator.validate(List.of(draft), Map.of("kp-test", "列表"), "v1").items().get(0).publicPayload();
  }

  private String step(String body) {
    return "{\"order\":1,\"caption\":\"观察变化\",\"duration_ms\":1800," + body + "}";
  }

  @Test void indicesPersistAcrossPublicPayloadRoundtrip() throws Exception {
    AnimationScene scene = validate(step("\"action\":\"MOVE_POINTER\",\"target_ids\":[\"p\",\"a\"],\"indices\":[-1]"));
    assertEquals(List.of(-1), scene.steps().get(0).indices());
    assertEquals(scene, json.readValue(json.writeValueAsString(scene), AnimationScene.class));
  }

  @Test void legacyStoredSceneStillDeserializes() throws Exception {
    AnimationScene scene = validate(step("\"action\":\"HIGHLIGHT\",\"target_ids\":[\"a\"]"));
    assertTrue(scene.steps().get(0).indices().isEmpty());
    String legacy = json.writeValueAsString(scene).replace(",\"indices\":[]", "");
    assertTrue(json.readValue(legacy, AnimationScene.class).steps().get(0).indices().isEmpty());
  }

  @ParameterizedTest @ValueSource(strings = {"[3]", "[-4]", "[0,0]", "[null]"})
  void rejectsInvalidIndices(String indices) {
    assertThrows(BusinessException.class, () -> validate(step("\"action\":\"HIGHLIGHT\",\"target_ids\":[\"a\"],\"indices\":" + indices)));
  }

  @ParameterizedTest @ValueSource(strings = {
      "\"action\":\"MOVE_POINTER\",\"target_ids\":[\"a\"],\"indices\":[0]",
      "\"action\":\"MOVE_POINTER\",\"target_ids\":[\"p\",\"a\"],\"indices\":[0,1]",
      "\"action\":\"HIGHLIGHT\",\"target_ids\":[\"r\"],\"indices\":[0]",
      "\"action\":\"HIDE\",\"target_ids\":[\"a\"],\"indices\":[0]",
      "\"action\":\"SET_VALUE\",\"target_ids\":[\"a\",\"r\"],\"indices\":[0],\"value\":\"x\""
  }) void rejectsAmbiguousOperations(String body) {
    assertThrows(BusinessException.class, () -> validate(step(body)));
  }

  @Test void validatesAgainstUpdatedArrayLength() throws Exception {
    String update = step("\"action\":\"SET_VALUE\",\"target_ids\":[\"a\"],\"value\":\"10,20\"");
    String next = step("\"action\":\"HIGHLIGHT\",\"target_ids\":[\"a\"],\"indices\":[-1]").replace("\"order\":1", "\"order\":2");
    assertEquals(2, validate(update + "," + next).steps().size());
    assertThrows(BusinessException.class, () -> validate(update + "," + next.replace("[-1]", "[2]")));
  }
}
