package io.github.idoly.pi.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.idoly.pi.protocol.PiEvent;
import io.github.idoly.pi.protocol.PiResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;

/** Incremental Pi RPC JSONL codec and size accounting. */
final class PiCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final int lineLimit;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    PiCodec(int lineLimit) { this.lineLimit = lineLimit; }

    void decode(byte[] bytes, int offset, int length, Consumer<Object> output) {
        int start = offset;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            if (bytes[i] != '\n') continue;
            append(bytes, start, i - start);
            emit(output);
            start = i + 1;
        }
        append(bytes, start, end - start);
    }

    void finish(Consumer<Object> output) { if (pending.size() > 0) emit(output); }

    /** Counts UTF-8 JSON without constructing a whole serialized String and byte array. */
    static long jsonBytes(JsonNode node) {
        class Counter extends OutputStream {
            long count;
            @Override public void write(int value) { count++; }
            @Override public void write(byte[] value, int offset, int length) { count += length; }
        }
        Counter counter = new Counter();
        try { MAPPER.writeValue(counter, node); }
        catch (IOException failure) { throw new PiException("Cannot measure RPC JSON", failure); }
        return counter.count;
    }
    static long recordBytes(Object record) {
        if (record instanceof PiEvent event) return jsonBytes(event.raw());
        if (record instanceof PiResponse response) return jsonBytes(response.raw());
        throw new IllegalArgumentException("Unknown RPC record");
    }
    static byte[] encode(String id, String type, Map<String, ?> arguments) {
        ObjectNode root = MAPPER.createObjectNode().put("type", type).put("id", id);
        arguments.forEach((name, value) -> root.set(name, MAPPER.valueToTree(value)));
        try { return MAPPER.writeValueAsBytes(root); }
        catch (JsonProcessingException failure) { throw new PiException("Failed to encode " + type, failure); }
    }
    static Object decode(byte[] bytes) {
        JsonNode root;
        try { root = MAPPER.readTree(bytes); }
        catch (IOException failure) { throw new PiException("Invalid RPC JSON", failure); }
        if (root == null || !root.isObject()) throw new PiException("RPC message must be a JSON object");
        String type = requiredText(root, "type");
        if (!type.equals("response")) return new PiEvent(type, root);
        JsonNode success = root.get("success");
        if (success == null || !success.isBoolean()) throw new PiException("RPC response field 'success' must be boolean");
        JsonNode id = root.get("id");
        return new PiResponse(id != null && id.isTextual() ? id.textValue() : null,
                requiredText(root, "command"), success.booleanValue(), root.get("data"),
                root.path("error").isTextual() ? root.path("error").textValue() : null, root);
    }
    private void emit(Consumer<Object> output) {
        byte[] line = takeLine();
        if (line.length > 0) output.accept(decode(line));
    }
    private void append(byte[] bytes, int offset, int length) {
        if ((long) pending.size() + length > lineLimit) throw new PiException("RPC JSONL line exceeds " + lineLimit + " bytes");
        pending.write(bytes, offset, length);
    }
    private byte[] takeLine() {
        byte[] line = pending.toByteArray();
        pending.reset();
        return line.length > 0 && line[line.length - 1] == '\r' ? Arrays.copyOf(line, line.length - 1) : line;
    }

    private static String requiredText(JsonNode root, String name) {
        JsonNode field = root.get(name);
        if (field == null || !field.isTextual() || field.textValue().isBlank()) {
            throw new PiException("RPC field '" + name + "' must be non-blank text");
        }
        return field.textValue();
    }
}
