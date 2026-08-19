package io.github.idoly.pi.core.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.idoly.pi.protocol.PiEvent;
import io.github.idoly.pi.protocol.PiResponse;

import java.io.IOException;

final class PiJsonCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    byte[] encode(PiCommand command) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", command.type());
        root.put("id", command.id());
        command.arguments().forEach((name, value) -> root.set(name, mapper.valueToTree(value)));

        try {
            return mapper.writeValueAsBytes(root);
        } catch (JsonProcessingException cause) {
            throw new PiProtocolException("Failed to encode " + command.type(), cause);
        }
    }

    Object decode(byte[] jsonBytes) {
        final JsonNode root;
        try {
            root = mapper.readTree(jsonBytes);
        } catch (IOException cause) {
            throw new PiProtocolException("Invalid RPC JSON", cause);
        }
        if (root == null || !root.isObject()) {
            throw new PiProtocolException("RPC message must be a JSON object");
        }

        String type = requiredText(root, "type");
        JsonNode raw = root.deepCopy();
        return "response".equals(type)
                ? decodeResponse(root, raw)
                : new PiEvent(type, raw);
    }

    private static PiResponse decodeResponse(JsonNode root, JsonNode raw) {
        JsonNode success = root.get("success");
        if (success == null || !success.isBoolean()) {
            throw new PiProtocolException("RPC response field 'success' must be boolean");
        }
        JsonNode id = root.get("id");
        return new PiResponse(
                id != null && id.isTextual() ? id.textValue() : null,
                requiredText(root, "command"),
                success.booleanValue(),
                root.get("data"),
                root.path("error").isTextual() ? root.path("error").textValue() : null,
                raw
        );
    }

    private static String requiredText(JsonNode root, String fieldName) {
        JsonNode field = root.get(fieldName);
        if (field == null || !field.isTextual() || field.textValue().isBlank()) {
            throw new PiProtocolException(
                    "RPC field '" + fieldName + "' must be non-blank text");
        }
        return field.textValue();
    }
}
