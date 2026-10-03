package com.claudewidgets.core;

/** De donde vino la respuesta. Los nombres son los que usa el contrato. */
public enum Source {
    CLAUDE_CODE("claude_code"),
    CLAUDE_AI("claude_ai");

    private final String wire;

    Source(String wire) { this.wire = wire; }

    @Override public String toString() { return wire; }

    public static Source of(String wire) {
        for (Source s : values()) if (s.wire.equals(wire)) return s;
        throw new IllegalArgumentException("source desconocido: " + wire);
    }
}
