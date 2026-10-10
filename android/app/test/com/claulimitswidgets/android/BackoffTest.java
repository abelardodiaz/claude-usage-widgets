package com.claulimitswidgets.android;

import com.claudewidgets.core.Assert;

public final class BackoffTest {
    public static void run(Assert a) {
        a.eq("primer reintento", 60L, Backoff.seconds(0));
        a.eq("segundo", 120L, Backoff.seconds(1));
        a.eq("tercero", 240L, Backoff.seconds(2));
        a.eq("cuarto antes del tope", 480L, Backoff.seconds(3));
        a.eq("quinto justo debajo del tope", 960L, Backoff.seconds(4));
        a.eq("sexto cruza el tope exacto", 1800L, Backoff.seconds(5));
        a.eq("se topa en 30 min", 1800L, Backoff.seconds(10));
        a.eq("sigue topado muy arriba", 1800L, Backoff.seconds(1000));
        a.eq("un intento negativo cuenta como el primero", 60L, Backoff.seconds(-1));
    }
}
