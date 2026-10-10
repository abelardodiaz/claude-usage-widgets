package com.claulimitswidgets.android;

import android.content.Context;

import com.claudewidgets.core.Json;
import com.claudewidgets.core.Sample;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
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
 * Ventana: 15 dias contados hacia atras desde el `now` que pasa quien llama (nunca desde el `t`
 * de una muestra: un `t` futuro o un reloj atrasado no puede gobernar la poda). Se aplica al
 * escribir Y al leer, de modo que un historico viejo no se devuelve aunque la app deje de refrescar.
 *
 * Concurrencia: un candado estatico serializa append/load/clear dentro de UN proceso (el job y la
 * interfaz comparten proceso). No protege entre procesos distintos.
 */
public final class SampleStore {

    static final String FILE_NAME = "samples.jsonl";
    /** Subdirectorio de filesDir. Lo usan la escritura (Tarea 4.2) y el logout: una sola fuente. */
    static final String DIR_NAME = "samples";
    private static final String TMP_NAME = FILE_NAME + ".tmp";
    private static final long WINDOW_DAYS = 15;
    private static final long FUTURE_TOLERANCE_SECONDS = 120;
    private static final Object LOCK = new Object();

    private final File dir;

    public SampleStore(File dir) {
        this.dir = dir;
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IllegalStateException("no se pudo crear el directorio de muestras");
        }
    }

    /** Directorio del almacen. Lo usan las pruebas para atar la ruta de produccion. */
    File dir() { return dir; }

    /** El almacen real de la app. Unico sitio que decide la ruta de produccion. */
    public static SampleStore of(Context ctx) {
        return new SampleStore(new File(ctx.getFilesDir(), DIR_NAME));
    }

    /**
     * Anade una muestra. Devuelve false, sin escribir, si no es aceptable: nula, `t` nulo,
     * porcentaje no finito, `t` mas de 2 min posterior a `now` (reloj saltado) o `t` fuera de la ventana. Asi la muestra que se acaba
     * de dar nunca se descarta en silencio al podar. Lanza IOException si no se pudo escribir.
     */
    public boolean append(Sample s, Instant now) throws IOException {
        if (s == null || s.t == null || now == null
                || Double.isNaN(s.percent) || Double.isInfinite(s.percent)) {
            return false;
        }
        // Tolerancia al desfase de reloj: un t hasta 2 min "en el futuro" se acota a `now`; mas
        // alla se rechaza. Contrato: lo normal es que el llamador pase now >= t.
        if (s.t.isAfter(now.plusSeconds(FUTURE_TOLERANCE_SECONDS))) return false;
        if (s.t.isAfter(now)) s = new Sample(now, s.percent, s.resetsAt);
        if (s.t.isBefore(cutoff(now))) return false;
        synchronized (LOCK) {
            List<Sample> all = readAll();
            all.add(s);
            rewrite(all, now);
        }
        return true;
    }

    /** Ascendente por `t`, solo las de los ultimos 15 dias respecto a `now`. Lineas ilegibles se ignoran. */
    public List<Sample> load(Instant now) throws IOException {
        Instant cut = cutoff(now);
        List<Sample> out = new ArrayList<>();
        synchronized (LOCK) {
            for (Sample s : readAll()) {
                if (!s.t.isBefore(cut)) out.add(s);
            }
        }
        return out;
    }

    /** Borra el archivo. true si ya no queda nada (incluido si no existia). */
    public boolean clear() {
        synchronized (LOCK) {
            boolean ok = deleteIfExists(new File(dir, FILE_NAME));
            ok &= deleteIfExists(new File(dir, TMP_NAME));
            return ok;
        }
    }

    private static Instant cutoff(Instant now) {
        return now.minusSeconds(WINDOW_DAYS * 86400);
    }

    private static boolean deleteIfExists(File f) {
        return !f.exists() || f.delete();
    }

    /** Todo lo legible del archivo, ordenado, sin aplicar ventana. Llamar con LOCK tomado. */
    private List<Sample> readAll() throws IOException {
        File f = new File(dir, FILE_NAME);
        List<Sample> out = new ArrayList<>();
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                Sample s = parseLine(line);
                if (s != null) out.add(s);
            }
        }
        out.sort(Comparator.comparing(s -> s.t));
        return out;
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

    /**
     * Escribe a un temporal, lo sincroniza a disco y lo renombra. Frente a la muerte del proceso
     * es atomico; frente a un apagon, el fsync del archivo ayuda pero no se sincroniza el
     * directorio, asi que en el peor caso se pierde la ultima escritura (nunca el aislamiento:
     * un archivo danado se ignora linea a linea al leer). Llamar con LOCK tomado.
     */
    private void rewrite(List<Sample> all, Instant now) throws IOException {
        Instant cut = cutoff(now);
        File tmp = new File(dir, TMP_NAME);
        try (FileOutputStream fos = new FileOutputStream(tmp, false);
             Writer w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            for (Sample s : all) {
                if (s.t.isBefore(cut)) continue;
                w.write("{\"t\":\"" + s.t + "\",\"percent\":" + s.percent
                        + ",\"resets_at\":" + (s.resetsAt == null ? "null" : "\"" + s.resetsAt + "\"")
                        + "}\n");
            }
            w.flush();
            fos.getFD().sync();
        }
        File f = new File(dir, FILE_NAME);
        if (!tmp.renameTo(f)) {
            throw new IOException("no se pudo reemplazar el archivo de muestras");
        }
    }
}
