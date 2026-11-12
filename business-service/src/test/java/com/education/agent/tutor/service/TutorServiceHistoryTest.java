package com.education.agent.tutor.service;

import com.education.agent.tutor.entity.TutorMessageEntity;
import com.education.agent.tutor.model.TutorMessageRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TutorServiceHistoryTest {

    @Test
    void boundsHistoryPerMessageAndAcrossTheWholePromptWhileKeepingNewestContext() {
        List<TutorMessageEntity> messages = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            TutorMessageEntity message = new TutorMessageEntity();
            message.setMessageRole(index % 2 == 0 ? TutorMessageRole.USER : TutorMessageRole.ASSISTANT);
            message.setContent("message-" + index + ":" + "x".repeat(24_990) + ":tail-" + index);
            messages.add(message);
        }

        var history = TutorService.toAiHistoryForPrompt(messages);

        assertThat(history).hasSizeLessThanOrEqualTo(20);
        assertThat(history).allSatisfy(item -> assertThat(item.content()).hasSizeLessThanOrEqualTo(20_000));
        assertThat(history.stream().mapToInt(item -> item.content().length()).sum()).isLessThanOrEqualTo(40_000);
        assertThat(history.get(history.size() - 1).content()).endsWith(":tail-24");
    }
}
