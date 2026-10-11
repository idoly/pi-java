package io.github.idoly.pi.core;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.idoly.pi.core.PiConnection.Request;
import io.github.idoly.pi.protocol.PiEvent;
import io.github.idoly.pi.protocol.PiResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PiClientConcurrencyTest {
    @Test
    void keepsConcurrentClientResultsIndependent() throws Exception {
        List<PiProcessFixture> fixtures = new ArrayList<>();
        List<PiClient> clients = new ArrayList<>();
        List<PiActivity> activities = new ArrayList<>();
        try {
            for (int index = 0; index < 5; index++) {
                PiProcessFixture fixture = new PiProcessFixture();
                fixtures.add(fixture);
                PiClient client = fixture.openClient();
                clients.add(client);
                activities.add(client.prompt("prompt-" + index));
            }
            for (int index = 0; index < fixtures.size(); index++) {
                PiProcessFixture fixture = fixtures.get(index);
                Request command = fixture.awaitCommand(2);
                var raw = JsonNodeFactory.instance.objectNode();
                fixture.emitInbound(new PiResponse(command.id(), command.type(), true, raw, null, raw));
                fixture.emitInbound(assistantResult("result-" + index));
                fixture.emitInbound(new PiEvent("agent_settled",
                        JsonNodeFactory.instance.objectNode().put("type", "agent_settled")));
            }
            CompletableFuture<?>[] completions = activities.stream()
                    .map(PiActivity::completion)
                    .map(stage -> stage.toCompletableFuture())
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(completions).get(5, TimeUnit.SECONDS);
            for (int index = 0; index < clients.size(); index++) {
                assertEquals("result-" + index, activities.get(index).completion().toCompletableFuture().join().text());
                assertEquals(PiClient.State.IDLE, clients.get(index).state());
            }
        } finally {
            try { clients.forEach(PiClient::close); }
            finally { fixtures.forEach(PiProcessFixture::close); }
        }
    }

    private static PiEvent assistantResult(String text) {
        var message = JsonNodeFactory.instance.objectNode().put("role", "assistant").put("stopReason", "stop");
        message.putArray("content").addObject().put("type", "text").put("text", text);
        var raw = JsonNodeFactory.instance.objectNode().put("type", "message_end");
        raw.set("message", message);
        return new PiEvent("message_end", raw);
    }
}
