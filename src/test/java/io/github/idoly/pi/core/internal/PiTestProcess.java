package io.github.idoly.pi.core.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public final class PiTestProcess {
    private PiTestProcess() {
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PrintWriter stdout = new PrintWriter(new OutputStreamWriter(
                System.out, StandardCharsets.UTF_8), true);
        PrintWriter stderr = new PrintWriter(new OutputStreamWriter(
                System.err, StandardCharsets.UTF_8), true);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode command = mapper.readTree(line);
                String response = mapper.createObjectNode()
                        .put("id", command.path("id").asText())
                        .put("type", "response")
                        .put("command", command.path("type").asText())
                        .put("success", true)
                        .set("data", mapper.createObjectNode().put("value", "a\u2028b\u2029c"))
                        .toString();
                int split = response.length() / 2;
                stdout.print(response.substring(0, split));
                stdout.flush();
                Thread.sleep(5);
                stdout.println(response.substring(split));
                stdout.flush();
                stderr.println("handled " + command.path("type").asText());
            }
        }
    }
}
