package com.claulimitswidgets.android;

import com.claudewidgets.core.Json;
import com.claudewidgets.core.Sample;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Muestras del uso semanal, una por linea en JSON. Son la entrada de R3, R4 y R6.
 * Solo guarda y lee: no calcula nada (las reglas viven en el nucleo).
 *
 * Decision D5: en claro, en el almacenamiento privado de la app. Son porcentajes, no
 * credenciales; `allowBackup=false` y el aislamiento de la app ya las protegen de otras apps.
 * Lo que revelan es el patron de uso del dueno, por eso Session.logout las borra.
 *
 * Ventana: 15 dias contados hacia atras desde la muestra MAS RECIENTE guardada (no desde el reloj),
 * para que sea determinista. Se poda al escribir.
 */
public final class SampleStore {

    static final String FILE_NAME = "samples.jsonl";
    private static final String TMP_NAME = FILE_NAME + ".tmp";
    private static final long WINDOW_DAYS = 15;

    private final File dir;

    public SampleStore(File dir) {
        this.dir = dir;
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IllegalStateException("no se pudo crear el directorio de muestras");
        }
    }

    public void append(Sample s) throws IOException {
        List<Sample> all = load();
        all.add(s);
        rewrite(all);
    }

    /** Ascendente por `t`. Una linea ilegible se ignora. */
    public List<Sample> load() throws IOException {
        File f = new File(dir, FILE_NAME);
        List<Sample> out = new ArrayList<>();
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                Sample s = parseLine(line);
                if (s != null) out.add(s);
            }
        }
        out.sort(Comparator.comparing(s -> s.t));
        return out;
    }

    /** Borra el archivo. true si ya no queda nada (incluido si no existia). */
    public boolean clear() {
        boolean ok = deleteIfExists(new File(dir, FILE_NAME));
        ok &= deleteIfExists(new File(dir, TMP_NAME));
        return ok;
    }

    private static boolean deleteIfExists(File f) {
        return !f.exists() || f.delete();
    }

    private static Sample parseLine(String line) {
        if (line == null || line.trim().isEmpty()) return null;
        try {
            Object o = Json.parse(line);
            if (!(o instanceof Map)) return null;
            Map<?, ?> m = (Map<?, ?>) o;
            Object t = m.get("t");
            Object p = m.get("percent");
            if (!(t instanceof String) || !(p instanceof Double)) return null;
            Object r = m.get("resets_at");
            return new Sample(Instant.parse((String) t), (Double) p,
                    r instanceof String ? Instant.parse((String) r) : null);
        } catch (RuntimeException e) {
            // Linea corrupta: se ignora. Perder una muestra es mucho mejor que no pintar nada.
            return null;
        }
    }

    /** Escribe a un temporal y renombra: un apagon a medias no deja el archivo truncado. */
    private void rewrite(List<Sample> all) throws IOException {
        all.sort(Comparator.comparing(s -> s.t));
        Instant newest = all.get(all.size() - 1).t;
        Instant cutoff = newest.minusSeconds(WINDOW_DAYS * 86400);
        File tmp = new File(dir, TMP_NAME);
        try (FileWriter w = new FileWriter(tmp, false)) {
            for (Sample s : all) {
                if (s.t.isBefore(cutoff)) continue;
                w.write("{\"t\":\"" + s.t + "\",\"percent\":" + s.percent
                        + ",\"resets_at\":" + (s.resetsAt == null ? "null" : "\"" + s.resetsAt + "\"")
                        + "}\n");
            }
        }
        File f = new File(dir, FILE_NAME);
        if (!tmp.renameTo(f)) {
            throw new IOException("no se pudo reemplazar el archivo de muestras");
        }
    }
}
