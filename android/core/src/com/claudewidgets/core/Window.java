package com.claudewidgets.core;

import java.time.Instant;

/**
 * Una ventana de cuota: sesion (5 h) o semana (7 dias). `resetsAt` puede ser nulo.
 *
 * Se llama como en el esquema (`$defs/window`) y como en el nucleo Rust, para que los tres
 * hablen el mismo idioma.
 */
public final class Window {
    public final double percent;
    public final Instant resetsAt;

    public Window(double percent, Instant resetsAt) {
        this.percent = percent;
        this.resetsAt = resetsAt;
    }

    @Override public String toString() { return "Window(" + percent + ", " + resetsAt + ")"; }
}
