package com.claudewidgets.core;

import java.util.ArrayList;
import java.util.List;

/** Comprobaciones minimas. Sin JUnit: el nucleo no tiene dependencias. */
public final class Assert {
    private final List<String> failures = new ArrayList<>();
    private int checks = 0;

    public void eq(String what, Object expected, Object actual) {
        checks++;
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) failures.add(what + ": esperado <" + expected + "> obtenido <" + actual + ">");
    }

    public void near(String what, double expected, double actual, double tol) {
        checks++;
        // Negada a proposito: con NaN `abs(...) > tol` es falso y el fallo pasaria en verde.
        if (!(Math.abs(expected - actual) <= tol)) {
            failures.add(what + ": esperado <" + expected + "> obtenido <" + actual
                    + "> (tolerancia " + tol + ")");
        }
    }

    public void isTrue(String what, boolean cond) {
        checks++;
        if (!cond) failures.add(what + ": se esperaba cierto");
    }

    /** Falla si el bloque NO lanza una excepcion del tipo pedido. */
    public void throwsOf(String what, Class<?> type, Runnable body) {
        checks++;
        try {
            body.run();
            failures.add(what + ": no lanzo " + type.getSimpleName());
        } catch (Throwable t) {
            if (!type.isInstance(t)) {
                failures.add(what + ": lanzo " + t.getClass().getSimpleName()
                        + " en vez de " + type.getSimpleName());
            }
        }
    }

    public void fail(String what) {
        checks++;
        failures.add(what);
    }

    public int checks() { return checks; }
    public List<String> failures() { return failures; }
}
