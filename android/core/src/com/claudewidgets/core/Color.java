package com.claudewidgets.core;

/** Los cuatro colores de R7. El texto es el que usan los fixtures; la UI decide como se ven. */
public enum Color {
    GREEN("green"),
    AMBER("amber"),
    RED("red"),
    GRAY("gray");

    private final String wire;

    Color(String wire) { this.wire = wire; }

    @Override public String toString() { return wire; }
}
