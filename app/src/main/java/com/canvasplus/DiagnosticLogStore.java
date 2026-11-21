package com.canvasplus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Stores the two most recent generations of complete UTF-8 log batches. */
final class DiagnosticLogStore {
    private static final int MAX_FILE_BYTES = 256 * 1024;

    private final Path previous;
    private final Path current;

    DiagnosticLogStore(Path directory) {
        previous = directory.resolve("diagnostics.previous.txt");
        current = directory.resolve("diagnostics.current.txt");
    }

    void append(String batch) throws IOException {
        byte[] bytes = batch.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("Diagnostic batch too large");
        synchronized (DiagnosticLogStore.class) {
            if (Files.exists(current) && Files.size(current) + bytes.length > MAX_FILE_BYTES) {
                Files.move(current, previous, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.write(current, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    boolean hasLogs() {
        synchronized (DiagnosticLogStore.class) {
            return Files.isRegularFile(current) || Files.isRegularFile(previous);
        }
    }

    byte[] snapshot() throws IOException {
        synchronized (DiagnosticLogStore.class) {
            var output = new ByteArrayOutputStream();
            if (Files.exists(previous)) Files.copy(previous, output);
            if (Files.exists(current)) Files.copy(current, output);
            return output.toByteArray();
        }
    }
}
