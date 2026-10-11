package io.github.idoly.pi.protocol;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiEventTest {
    @Test
    void onlyMessageUpdatesExposeAssistantTextDelta() {
        var raw = JsonNodeFactory.instance.objectNode();
        raw.set("assistantMessageEvent", JsonNodeFactory.instance.objectNode().put("type", "text_delta").put("delta", "text"));
        assertEquals("text", new PiEvent("message_update", raw).textDelta().orElseThrow());
        for (String type : List.of("queue_update", "agent_start", "extension_ui_request", "future_event")) {
            assertTrue(new PiEvent(type, raw).textDelta().isEmpty(), type);
        }
    }

    @Test
    void thinkingAndIncompleteTextUpdatesHaveNoTextDelta() {
        var raw = JsonNodeFactory.instance.objectNode();
        var nested = raw.putObject("assistantMessageEvent").put("type", "thinking_delta").put("delta", "thought");
        assertTrue(new PiEvent("message_update", raw).textDelta().isEmpty());
        nested.put("type", "text_delta").remove("delta");
        assertTrue(new PiEvent("message_update", raw).textDelta().isEmpty());
    }
}
