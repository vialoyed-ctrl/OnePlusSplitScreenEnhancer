package com.canvasplus;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Matches the embedded tasks' draw reports to the layout being revealed. */
final class TaskFrameReadiness {
    record Size(int width, int height) {
        static final Size ANY = new Size(0, 0);
    }

    private final Map<Integer, Set<Size>> drawn = new HashMap<>();
    private Map<Integer, Size> expected;
    private boolean committed;

    void expect(Map<Integer, Size> expected) {
        this.expected = expected;
    }

    void report(int taskId, Size size) {
        drawn.computeIfAbsent(taskId, ignored -> new HashSet<>()).add(size);
    }

    void markCommitted() {
        committed = true;
    }

    boolean isReady() {
        if (!committed || expected == null) return false;
        for (var task : expected.entrySet()) {
            Set<Size> drawnSizes = drawn.get(task.getKey());
            if (drawnSizes == null || (!Size.ANY.equals(task.getValue()) && !drawnSizes.contains(task.getValue()))) {
                return false;
            }
        }
        return true;
    }
}
