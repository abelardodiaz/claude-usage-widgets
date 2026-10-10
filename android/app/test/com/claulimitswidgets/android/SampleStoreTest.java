package com.claulimitswidgets.android;

import com.claudewidgets.core.Assert;
import com.claudewidgets.core.Sample;

import java.io.File;
import java.io.FileWriter;
import java.time.Instant;
import java.util.List;

public final class SampleStoreTest {

    private static final long DAY = 86400;

    private static void raw(File f, boolean append, String text) throws Exception {
        try (FileWriter w = new FileWriter(f, append)) { w.write(text); }
    }

    public static void run(Assert a, File tmp) {
        File dir = new File(tmp, "samples-" + System.nanoTime());
        SampleStore s = new SampleStore(dir);
        Instant now = Instant.parse("2026-10-03T12:00:00Z");
        File file = new File(dir, SampleStore.FILE_NAME);

        try {
            a.eq("vacio al empezar", 0, s.load(now).size());
            a.isTrue("sin archivo clear no falla", s.clear());

            // Fuera de orden a proposito al anadir.
            a.isTrue("acepta", s.append(new Sample(now, 12, now.plusSeconds(DAY)), now));
            a.isTrue("acepta la anterior",
                    s.append(new Sample(now.minusSeconds(3600), 10, null), now));
            List<Sample> got = s.load(now);
            a.eq("dos muestras", 2, got.size());
            a.isTrue("orden ascendente", got.get(0).t.isBefore(got.get(1).t));
            a.near("el porcentaje sobrevive", 12.0, got.get(1).percent, 0.001);
            a.eq("resets_at sobrevive", now.plusSeconds(DAY), got.get(1).resetsAt);
            a.isTrue("resets_at null sobrevive", got.get(0).resetsAt == null);

            // load ordena de verdad: dos lineas validas escritas INVERTIDAS a mano.
            raw(file, false,
                "{\"t\":\"2026-10-03T11:00:00Z\",\"percent\":2,\"resets_at\":null}\n"
              + "{\"t\":\"2026-10-03T10:00:00Z\",\"percent\":1,\"resets_at\":null}\n");
            got = s.load(now);
            a.eq("dos lineas a mano", 2, got.size());
            a.near("load ordena: la mas vieja primero", 1.0, got.get(0).percent, 0.001);
            s.clear();
            a.isTrue("siembra 1 aceptada", s.append(new Sample(now, 12, null), now));

            // Ventana: una muestra de 16 dias se RECHAZA (false) y no entra.
            a.isTrue("la de 16 dias se rechaza",
                    !s.append(new Sample(now.minusSeconds(16 * DAY), 1, now), now));
            a.eq("la vieja no vuelve", 1, s.load(now).size());
            a.isTrue("la de 14 dias entra",
                    s.append(new Sample(now.minusSeconds(14 * DAY), 3, now), now));
            a.eq("la de 14 dias se queda", 2, s.load(now).size());

            // load aplica la ventana con el now del que llama, aunque nadie escriba mas.
            Instant later = now.plusSeconds(20 * DAY);
            a.eq("el historico viejo no se devuelve", 0, s.load(later).size());
            a.isTrue("pero sigue en disco hasta que se pode o se borre", file.length() > 0);

            // Borde exacto: t == now - 15 dias se acepta y se lee (paridad rechazo/poda).
            s.clear();
            Instant edge = now.minusSeconds(15 * DAY);
            a.isTrue("el borde exacto de la ventana se acepta", s.append(new Sample(edge, 7, null), now));
            a.eq("el borde exacto aparece en load", 1, s.load(now).size());
            a.isTrue("un segundo mas viejo se rechaza",
                    !s.append(new Sample(edge.minusSeconds(1), 7, null), now));
            // Tolerancia de reloj: unos segundos en el futuro se acotan a now; mas de 2 min no.
            a.isTrue("t 30 s en el futuro se acepta",
                    s.append(new Sample(now.plusSeconds(30), 8, now.plusSeconds(DAY)), now));
            a.isTrue("el t acotado no supera now", !s.load(now).get(1).t.isAfter(now));
            // El acotado reconstruye la muestra: resetsAt (del que depende sameWindow en R3/R6)
            // y el porcentaje tienen que sobrevivir.
            a.eq("la muestra acotada conserva su resetsAt", now.plusSeconds(DAY), s.load(now).get(1).resetsAt);
            a.near("la muestra acotada conserva su porcentaje", 8.0, s.load(now).get(1).percent, 0.001);
            a.isTrue("t 3 min en el futuro se rechaza",
                    !s.append(new Sample(now.plusSeconds(180), 8, null), now));
            a.isTrue("muestra nula se rechaza sin lanzar", !s.append(null, now));
            a.isTrue("t nulo se rechaza sin lanzar", !s.append(new Sample(null, 5, null), now));
            s.clear();
            a.isTrue("siembra 2 aceptada", s.append(new Sample(now, 12, null), now));
            a.isTrue("siembra 3 aceptada", s.append(new Sample(now.minusSeconds(14 * DAY), 3, null), now));

            // Muestras no aceptables: futuro, NaN, infinito. No tiran las buenas.
            int before = s.load(now).size();
            a.isTrue("t futuro se rechaza",
                    !s.append(new Sample(now.plusSeconds(30 * DAY), 50, null), now));
            a.isTrue("NaN se rechaza", !s.append(new Sample(now, Double.NaN, null), now));
            a.isTrue("infinito se rechaza",
                    !s.append(new Sample(now, Double.POSITIVE_INFINITY, null), now));
            a.eq("las buenas siguen", before, s.load(now).size());
            // Tras un intento futuro, una muestra normal sigue entrando (no se atasca).
            a.isTrue("sigue creciendo", s.append(new Sample(now.minusSeconds(60), 11, null), now));
            a.eq("una mas", before + 1, s.load(now).size());

            // Una linea futura PARSEABLE ya en disco no gobierna la poda de las nuevas.
            raw(file, true, "{\"t\":\"2030-01-01T00:00:00Z\",\"percent\":1,\"resets_at\":null}\n");
            int n = s.load(now).size();
            a.isTrue("con basura futura en disco, append sigue guardando",
                    s.append(new Sample(now.minusSeconds(30), 14, null), now));
            a.eq("y las reales no se borraron", n + 1, s.load(now).size());

            // Lineas corruptas varias, incluida truncada sin salto.
            raw(file, false, "");
            a.isTrue("siembra 4 aceptada", s.append(new Sample(now.minusSeconds(100), 5, null), now));
            raw(file, true, "{esto no es json}\n\n{\"t\":\"ayer\",\"percent\":5}\n"
                    + "{\"t\":\"2026-10-03T11:00:00Z\",\"percent\":\"x\"}\n"
                    + "{\"t\":\"2026-10-03T1");
            a.eq("las lineas corruptas se ignoran", 1, s.load(now).size());
            a.isTrue("se puede seguir anadiendo tras corrupcion",
                    s.append(new Sample(now, 13, null), now));
            a.eq("dos tras corrupcion", 2, s.load(now).size());

            // Archivo vacio (0 bytes).
            raw(file, false, "");
            a.eq("archivo vacio", 0, s.load(now).size());
            a.isTrue("append sobre archivo vacio", s.append(new Sample(now, 12, null), now));

            // Concurrencia: dos productores no se pisan.
            s.clear();
            final SampleStore shared = s;
            final Throwable[] err = {null};
            Thread[] ts = new Thread[2];
            for (int k = 0; k < 2; k++) {
                final int base = k * 1000;
                ts[k] = new Thread(() -> {
                    try {
                        for (int i = 0; i < 15; i++) {
                            shared.append(new Sample(now.minusSeconds(base + i + 1), 5, null), now);
                        }
                    } catch (Throwable e) { err[0] = e; }
                });
                ts[k].start();
            }
            for (Thread t : ts) t.join();
            a.isTrue("los hilos no fallaron", err[0] == null);
            a.eq("ninguna muestra se perdio entre hilos", 30, s.load(now).size());

            a.isTrue("antes de clear existe el archivo", file.isFile());
            a.isTrue("clear devuelve true", s.clear());
            a.isTrue("clear borra el archivo", !file.exists());
            a.eq("clear deja vacio", 0, s.load(now).size());
        } catch (Exception e) {
            a.fail("SampleStore lanzo " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
