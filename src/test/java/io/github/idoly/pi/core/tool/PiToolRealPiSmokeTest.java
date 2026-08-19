package io.github.idoly.pi.core.tool;

import io.github.idoly.pi.core.PiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "pi.test.real", matches = "true")
class PiToolRealPiSmokeTest {
    @TempDir
    Path temp;

    @Test
    void startsRealPiWithGeneratedToolExtension() throws Exception {
        try (PiClient client = PiClient.open(temp, new EchoTools())) {
            assertTrue(client.request("get_state").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).data().path("sessionId").isTextual());
        }
    }

    static final class EchoTools {
        @PiTool(name = "java_echo", description = "Echo text through Java")
        public EchoResult echo(@PiToolParameter(name = "text") String text) {
            return new EchoResult(text);
        }
    }

    record EchoResult(String text) {
    }
}
