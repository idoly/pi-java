package io.github.idoly.pi.core.internal;

import io.github.idoly.pi.core.PiException;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

final class PiJsonlFramer {
    private final int maxLineBytes;
    private final ByteArrayOutputStream currentLine = new ByteArrayOutputStream();

    PiJsonlFramer(int maxLineBytes) {
        if (maxLineBytes < 1) {
            throw new IllegalArgumentException("maxLineBytes must be positive");
        }
        this.maxLineBytes = maxLineBytes;
    }

    List<byte[]> accept(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IndexOutOfBoundsException("Invalid byte range");
        }
        List<byte[]> completeLines = new ArrayList<>();
        int segmentStart = offset;
        int end = offset + length;
        for (int index = offset; index < end; index++) {
            if (bytes[index] != '\n') {
                continue;
            }
            append(bytes, segmentStart, index - segmentStart);
            completeLines.add(completeLine());
            segmentStart = index + 1;
        }
        append(bytes, segmentStart, end - segmentStart);
        return completeLines;
    }

    byte[] finish() {
        return currentLine.size() == 0 ? null : completeLine();
    }

    private void append(byte[] bytes, int offset, int length) {
        if ((long) currentLine.size() + length > maxLineBytes) {
            throw new PiException("RPC JSONL line exceeds " + maxLineBytes + " bytes");
        }
        currentLine.write(bytes, offset, length);
    }

    private byte[] completeLine() {
        byte[] line = currentLine.toByteArray();
        currentLine.reset();
        if (line.length > 0 && line[line.length - 1] == '\r') {
            byte[] trimmed = new byte[line.length - 1];
            System.arraycopy(line, 0, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return line;
    }
}
