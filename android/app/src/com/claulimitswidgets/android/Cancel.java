package com.claulimitswidgets.android;

/**
 * Aviso de "para, el sistema corto el trabajo". Lo crea quien lanza el refresco (el servicio del
 * job) y lo mira quien hace la red: {@link UsageClient} registra aqui como abortar la conexion en
 * curso, de modo que `onStopJob` no deja al hilo esperando los timeouts de 20 s por peticion.
 *
 * Sin estado de la cuenta: solo un indicador y un gancho.
 */
class Cancel {

    /** Nunca se cancela. Es lo que usan los caminos que no tienen quien los corte. */
    static final Cancel NONE = new Cancel() {
        @Override void cancel() { }
        @Override void setHook(Runnable h) { }
    };

    private volatile boolean cancelled;
    private Runnable hook;

    /** Pide parar; si hay una conexion registrada, la aborta ya. Idempotente. */
    void cancel() {
        Runnable h;
        synchronized (this) {
            cancelled = true;
            h = hook;
        }
        run(h);
    }

    boolean isCancelled() { return cancelled; }

    /**
     * Registra como abortar lo que esta en curso (null = ya no hay nada). Si la cancelacion llego
     * antes de registrar, el gancho se ejecuta enseguida: no se pierde por una carrera.
     */
    void setHook(Runnable h) {
        boolean now;
        synchronized (this) {
            hook = h;
            now = cancelled && h != null;
        }
        if (now) run(h);
    }

    private static void run(Runnable h) {
        if (h == null) return;
        try {
            h.run();
        } catch (RuntimeException ignored) {
            // Abortar es un esfuerzo: la conexion se cierra sola al agotar su timeout.
        }
    }
}
