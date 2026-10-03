package com.claudewidgets.core;

import java.nio.file.Path;
import java.util.List;

/** Punto de entrada de las pruebas. Codigo de salida != 0 si algo falla. */
public final class TestRunner {

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("uso: TestRunner <ruta a spec/fixtures>");
            System.exit(2);
        }
        Assert a = new Assert();
        JsonTest.run(a);
        int jsonChecks = a.checks();
        System.out.println("  lector JSON: " + jsonChecks + " comprobaciones");

        FixtureRunner.run(a, Path.of(args[0]));

        List<String> failures = a.failures();
        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("OK: " + a.checks() + " comprobaciones, 0 fallos");
            return;
        }
        System.out.println("FALLOS (" + failures.size() + " de " + a.checks() + "):");
        for (String f : failures) System.out.println("  - " + f);
        System.exit(1);
    }
}
