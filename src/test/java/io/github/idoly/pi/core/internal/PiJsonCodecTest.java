package io.github.idoly.pi.core.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.idoly.pi.core.PiException;
import io.github.idoly.pi.protocol.PiEvent;
import io.github.idoly.pi.protocol.PiImage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PiJsonCodecTest {
    private final PiJsonCodec codec = new PiJsonCodec();

    @Test
    void encodesPromptWithStableProtocolFields() {
        byte[] encoded = codec.encode(PiCommand.prompt("pj-1", "hello", List.of()));

        assertArrayEquals(
                "{\"type\":\"prompt\",\"id\":\"pj-1\",\"message\":\"hello\"}"
                        .getBytes(StandardCharsets.UTF_8),
                encoded
        );
    }

    @Test
    void encodesImagesWithProtocolType() throws Exception {
        byte[] encoded = codec.encode(PiCommand.prompt(
                "pj-1", "inspect", List.of(new PiImage("YWJj", "image/png"))));

        var image = new ObjectMapper().readTree(encoded).path("images").path(0);
        assertEquals("image", image.path("type").textValue());
        assertEquals("YWJj", image.path("data").textValue());
        assertEquals("image/png", image.path("mimeType").textValue());
    }

    @Test
    void decodesTextDelta() {
        Object message = codec.decode(("""
                {"type":"message_update","usage":{},"assistantMessageEvent":{
                  "type":"text_delta","contentIndex":0,"delta":"Hello"
                }}
                """).getBytes(StandardCharsets.UTF_8));

        PiEvent update = assertInstanceOf(PiEvent.class, message);
        assertEquals("Hello", update.textDelta().orElseThrow());
    }

    @Test
    void preservesUnknownEvent() {
        Object message = codec.decode(
                "{\"type\":\"future_event\",\"value\":42}".getBytes(StandardCharsets.UTF_8));

        PiEvent event = assertInstanceOf(PiEvent.class, message);
        assertEquals(42, event.raw().path("value").intValue());
    }

    @Test
    void rejectsResponseWithoutCriticalFields() {
        assertThrows(PiException.class, () -> codec.decode(
                "{\"type\":\"response\",\"command\":\"prompt\"}"
                        .getBytes(StandardCharsets.UTF_8)));
    }
}
