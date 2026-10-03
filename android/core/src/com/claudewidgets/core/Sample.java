package com.claudewidgets.core;

import java.time.Instant;

/** Una medicion guardada: cuanto marcaba la semana en ese instante. */
public final class Sample {
    public final Instant t;
    public final double percent;
    public final Instant resetsAt;

    public Sample(Instant t, double percent, Instant resetsAt) {
        this.t = t;
        this.percent = percent;
        this.resetsAt = resetsAt;
    }

    @Override public String toString() { return "Sample(" + t + ", " + percent + ")"; }
}
