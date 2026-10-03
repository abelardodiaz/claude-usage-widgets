package com.claudewidgets.core;

import java.time.Instant;

/** Limite con alcance propio (por modelo o por superficie). Sale de `limits[]` (R1). */
public final class ScopedLimit {
    public final String label;
    public final double percent;
    public final Instant resetsAt;

    public ScopedLimit(String label, double percent, Instant resetsAt) {
        this.label = label;
        this.percent = percent;
        this.resetsAt = resetsAt;
    }

    @Override public String toString() {
        return "ScopedLimit(" + label + ", " + percent + ", " + resetsAt + ")";
    }
}
