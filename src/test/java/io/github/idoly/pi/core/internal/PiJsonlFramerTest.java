package io.github.idoly.pi.core.internal;

import io.github.idoly.pi.core.PiException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PiJsonlFramerTest {
    @Test
    void framesOnlyOnLfAcrossArbitraryChunks() {
        PiJsonlFramer framer = new PiJsonlFramer(1024);
        byte[] bytes = "{\"v\":\"a\u2028b\u2029c\"}\r\n{\"n\":2}\n".getBytes(StandardCharsets.UTF_8);
        List<byte[]> lines = new ArrayList<>();

        for (byte value : bytes) {
            lines.addAll(framer.accept(new byte[]{value}, 0, 1));
        }

        assertEquals(2, lines.size());
        assertEquals("{\"v\":\"a\u2028b\u2029c\"}", new String(lines.get(0), StandardCharsets.UTF_8));
        assertEquals("{\"n\":2}", new String(lines.get(1), StandardCharsets.UTF_8));
    }

    @Test
    void returnsFinalUnterminatedRecord() {
        PiJsonlFramer framer = new PiJsonlFramer(100);
        byte[] line = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);

        framer.accept(line, 0, line.length);

        assertEquals("{\"ok\":true}", new String(framer.finish(), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsOversizedRecordBeforeUnboundedAllocation() {
        PiJsonlFramer framer = new PiJsonlFramer(4);

        assertThrows(PiException.class,
                () -> framer.accept("12345".getBytes(StandardCharsets.UTF_8), 0, 5));
    }
}
