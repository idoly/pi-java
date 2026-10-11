package io.github.idoly.pi.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.idoly.pi.protocol.PiEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiCodecTest {
    @Test
    void encodesPromptFields() throws Exception {
        byte[] encoded = PiCodec.encode("pj-1", "prompt", Map.of("message", "hello"));
        var command = new ObjectMapper().readTree(encoded);
        assertEquals("prompt", command.path("type").asText());
        assertEquals("pj-1", command.path("id").asText());
        assertEquals("hello", command.path("message").asText());
    }

    @Test
    void encodesImagesWithProtocolType() throws Exception {
        byte[] encoded = PiCodec.encode("pj-1", "prompt", Map.of("message", "inspect",
                "images", List.of(Map.of("type", "image", "data", "YWJj", "mimeType", "image/png"))));

        var image = new ObjectMapper().readTree(encoded).path("images").path(0);
        assertEquals("image", image.path("type").textValue());
        assertEquals("YWJj", image.path("data").textValue());
        assertEquals("image/png", image.path("mimeType").textValue());
    }

    @Test
    void decodesTextDelta() {
        Object message = PiCodec.decode(("""
                {"type":"message_update","usage":{},"assistantMessageEvent":{
                  "type":"text_delta","contentIndex":0,"delta":"Hello"
                }}
                """).getBytes(StandardCharsets.UTF_8));

        PiEvent update = assertInstanceOf(PiEvent.class, message);
        assertEquals("Hello", update.textDelta().orElseThrow());
    }

    @Test
    void preservesUnknownEvent() {
        Object message = PiCodec.decode(
                "{\"type\":\"future_event\",\"value\":42}".getBytes(StandardCharsets.UTF_8));

        PiEvent event = assertInstanceOf(PiEvent.class, message);
        assertEquals(42, event.raw().path("value").intValue());
    }

    @Test
    void countsUtf8JsonIncludingEscapes() throws Exception {
        var mapper = new ObjectMapper();
        var node = mapper.createObjectNode().put("text", "中文\n\"quoted\"\\path");
        assertEquals(mapper.writeValueAsBytes(node).length, PiCodec.jsonBytes(node));
    }

    @Test
    void decodesOnlyOnLfAcrossArbitraryChunks() {
        PiCodec codec = new PiCodec(1024);
        byte[] bytes = "{\"type\":\"probe\",\"v\":\"a\u2028b\u2029c\"}\r\n{\"type\":\"probe\",\"n\":2}\n".getBytes(StandardCharsets.UTF_8);
        List<Object> records = new ArrayList<>();
        for (byte value : bytes) codec.decode(new byte[]{value}, 0, 1, records::add);
        assertEquals(2, records.size());
        assertEquals("a\u2028b\u2029c", ((PiEvent) records.get(0)).raw().path("v").asText());
        assertEquals(2, ((PiEvent) records.get(1)).raw().path("n").asInt());
    }

    @Test
    void decodesFinalUnterminatedRecord() {
        PiCodec codec = new PiCodec(100);
        byte[] bytes = "{\"type\":\"probe\",\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        List<Object> records = new ArrayList<>();
        codec.decode(bytes, 0, bytes.length, records::add);
        assertEquals(0, records.size());
        codec.finish(records::add);
        assertTrue(((PiEvent) records.get(0)).raw().path("ok").asBoolean());
    }

    @Test
    void rejectsOversizedRecordBeforeUnboundedAllocation() {
        PiCodec codec = new PiCodec(4);
        assertThrows(PiException.class, () -> codec.decode("12345".getBytes(StandardCharsets.UTF_8), 0, 5, ignored -> {}));
    }

    @Test
    void rejectsExtraJsonObjectsOrGarbageInOneRecord() {
        for (String suffix : List.of(" {\"type\":\"extra\"}", " garbage", " 12")) {
            byte[] bytes = ("{\"type\":\"probe\"}" + suffix).getBytes(StandardCharsets.UTF_8);
            assertThrows(PiException.class, () -> PiCodec.decode(bytes));
        }
    }

    @Test
    void rejectsMalformedRecordsAndResponseFields() {
        for (String json : List.of("[]", "{}", "{\"type\":\"\"}",
                "{\"type\":\"response\",\"command\":\"prompt\"}",
                "{\"type\":\"response\",\"command\":\"prompt\",\"success\":\"true\"}",
                "{\"type\":\"response\",\"success\":true}")) {
            assertThrows(PiException.class, () -> PiCodec.decode(json.getBytes(StandardCharsets.UTF_8)), json);
        }
    }
}
