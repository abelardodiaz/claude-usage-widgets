package com.claulimitswidgets.android;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Une los refrescos que coinciden: el job periodico (4201), el de arranque (4202) y un toque
 * pueden llegar casi a la vez y bastan UNA consulta a claude.ai.
 *
 * La regla que evita perder un refresco legitimo: quien llega anota (epoca de sesion, version) AL
 * LLEGAR. Un refresco ya hecho puede contestar por el si EMPEZO con una epoca y una version
 * iguales o mayores (no hubo un login, una eleccion de organizacion ni un cierre de sesion entre
 * medias) y acabo hace menos de {@link #WINDOW_MS}. Los cambios de estado (login, organizacion)
 * suben la version con {@link #invalidate}; el cierre de sesion sube la epoca.
 *
 * Una instancia para produccion ({@link #SHARED}) y las que cada prueba quiera: la suite no puede
 * compartir estado con el refresco real del dueno.
 */
final class Coalescer {

    /** Cuanto tiempo vale un refresco ya hecho para quien llega justo detras. */
    static final long WINDOW_MS = 20_000L;

    static final Coalescer SHARED = new Coalescer(() -> System.nanoTime() / 1_000_000L);

    private final LongSupplier nowMs;
    private final AtomicLong version = new AtomicLong();

    private Snapshot last;
    private long lastEpoch;
    private long lastVersion;
    private long lastDoneMs;

    Coalescer(LongSupplier nowMs) { this.nowMs = nowMs; }

    /** Cambio de estado que obliga a consultar de nuevo (login, eleccion de organizacion). */
    void invalidate() { version.incrementAndGet(); }

    long version() { return version.get(); }

    /** El resultado reutilizable para quien llego con (epoch, version), o null. */
    synchronized Snapshot reusable(long arrivedEpoch, long arrivedVersion) {
        if (last == null) return null;
        if (lastEpoch < arrivedEpoch || lastVersion < arrivedVersion) return null;
        if (nowMs.getAsLong() - lastDoneMs >= WINDOW_MS) return null;
        return last;
    }

    /** Anota un refresco terminado, con la epoca y la version con que EMPEZO. */
    synchronized void record(long startEpoch, long startVersion, Snapshot s) {
        last = s;
        lastEpoch = startEpoch;
        lastVersion = startVersion;
        lastDoneMs = nowMs.getAsLong();
    }
}
