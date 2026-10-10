package com.claulimitswidgets.android;

import com.claudewidgets.core.Assert;
import com.claudewidgets.core.Sample;

import java.io.File;
import java.io.FileWriter;
import java.time.Instant;
import java.util.List;

public final class SampleStoreTest {

    public static void run(Assert a, File tmp) {
        File dir = new File(tmp, "samples-" + System.nanoTime());
        SampleStore s = new SampleStore(dir);
        Instant now = Instant.parse("2026-10-03T12:00:00Z");
        File file = new File(dir, SampleStore.FILE_NAME);

        try {
            a.eq("vacio al empezar", 0, s.load().size());
            a.isTrue("sin archivo no hay nada que borrar y clear no falla", s.clear());

            // Fuera de orden a proposito: load debe ordenar, no confiar en el orden de escritura.
            s.append(new Sample(now, 12, now.plusSeconds(86400)));
            s.append(new Sample(now.minusSeconds(3600), 10, null));
            List<Sample> got = s.load();
            a.eq("dos muestras", 2, got.size());
            a.isTrue("orden ascendente", got.get(0).t.isBefore(got.get(1).t));
            a.near("el porcentaje sobrevive", 12.0, got.get(1).percent, 0.001);
            a.eq("resets_at sobrevive", now.plusSeconds(86400), got.get(1).resetsAt);
            a.isTrue("resets_at null sobrevive", got.get(0).resetsAt == null);

            // Mas de 15 dias respecto a la muestra mas reciente: fuera.
            s.append(new Sample(now.minusSeconds(16L * 86400), 1, now));
            a.eq("la vieja no vuelve", 2, s.load().size());
            // Justo dentro de la ventana: se queda.
            s.append(new Sample(now.minusSeconds(14L * 86400), 3, now));
            a.eq("la de 14 dias se queda", 3, s.load().size());

            // Una linea corrupta no puede tirar el widget entero.
            try (FileWriter w = new FileWriter(file, true)) {
                w.write("{esto no es json}\n");
                w.write("\n");
                w.write("{\"t\":\"ayer\",\"percent\":5}\n");
                w.write("{\"t\":\"2026-10-03T11:00:00Z\",\"percent\":\"x\"}\n");
                w.write("{\"t\":\"2026-10-03T1");   // apagon a mitad de linea, sin salto
            }
            a.eq("las lineas corruptas se ignoran", 3, s.load().size());
            // Y se sigue escribiendo despues de un archivo danado.
            s.append(new Sample(now.plusSeconds(60), 13, null));
            a.eq("se puede seguir anadiendo tras corrupcion", 4, s.load().size());

            // Archivo vacio (0 bytes).
            try (FileWriter w = new FileWriter(file, false)) { w.write(""); }
            a.eq("archivo vacio", 0, s.load().size());

            s.append(new Sample(now, 12, null));
            a.isTrue("antes de clear existe el archivo", file.isFile());
            a.isTrue("clear devuelve true", s.clear());
            a.isTrue("clear borra el archivo", !file.exists());
            a.eq("clear deja vacio", 0, s.load().size());
        } catch (Exception e) {
            a.fail("SampleStore lanzo " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
