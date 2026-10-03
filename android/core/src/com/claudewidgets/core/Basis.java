package com.claudewidgets.core;

/** Sobre que se calculo una proyeccion (R5, R6). Nulo = no se proyecto. */
public enum Basis {
    /** Ritmo medio de la ventana completa. */
    WINDOW("window"),
    /** Ritmo de las ultimas 24 h. */
    RATE_24H("24h");

    private final String wire;

    Basis(String wire) { this.wire = wire; }

    @Override public String toString() { return wire; }
}
