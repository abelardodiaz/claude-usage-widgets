package com.claudewidgets.core;

/** Una fila del desglose semanal. `key` es estable; `label` es solo para mostrar (R1b). */
public final class BreakdownRow {
    public final String key;
    public final String label;
    public final double percent;

    public BreakdownRow(String key, String label, double percent) {
        this.key = key;
        this.label = label;
        this.percent = percent;
    }

    @Override public String toString() {
        return "BreakdownRow(" + key + ", " + label + ", " + percent + ")";
    }
}
