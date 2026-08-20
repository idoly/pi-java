package io.github.idoly.pi.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiClientConfigTest {
    @Test
    void preservesIsolatedCompatibilityDefaults() {
        Path workspace = Path.of("workspace");

        PiClientConfig config = PiClientConfig.builder(workspace).build();

        assertEquals(workspace, config.workspace());
        assertEquals(PiResourcePolicy.ISOLATED, config.resourcePolicy());
        assertTrue(config.command().isEmpty());
        assertEquals(Duration.ofSeconds(15), config.requestTimeout());
    }

    @Test
    void rejectsAmbiguousOrUnsafeConfiguration() {
        assertThrows(IllegalArgumentException.class, () ->
                PiClientConfig.builder(Path.of("workspace")).command(List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                PiClientConfig.builder(Path.of("workspace"))
                        .requestTimeout(Duration.ZERO));
    }
}
