package com.claulimitswidgets.android;

/** Espera exponencial de la spec 3.4: desde 1 min, duplicando, con tope de 30 min. */
public final class Backoff {

    private static final long FIRST = 60L;
    /** Tope de la espera, en segundos. Lo comparte el cap del reloj atrasado de UsageRefresher. */
    static final long MAX = 1800L;

    private Backoff() {}

    public static long seconds(int attempt) {
        if (attempt <= 0) return FIRST;
        if (attempt >= 5) return MAX;          // 60 * 2^5 = 1920 > 1800
        return Math.min(FIRST << attempt, MAX);
    }
}
