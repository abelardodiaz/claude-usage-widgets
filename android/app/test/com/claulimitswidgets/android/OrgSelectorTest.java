package com.claulimitswidgets.android;

import com.claudewidgets.core.Assert;

import java.util.Arrays;
import java.util.List;

/**
 * Se prueba la REGLA, no la red: `OrgSelector` recibe un probador que dice que organizaciones
 * responden 200.
 */
public final class OrgSelectorTest {

    /** Probador que cuenta cuantas veces se le pregunta: cada sondeo es una peticion real. */
    private static final class Contador implements OrgSelector.Probe {
        private final java.util.function.Predicate<String> responde;
        int llamadas = 0;

        Contador(java.util.function.Predicate<String> responde) { this.responde = responde; }

        @Override public boolean responds(String orgUuid) {
            llamadas++;
            return responde.test(orgUuid);
        }
    }

    public static void run(Assert a) {
        List<String> dos = Arrays.asList("org-a", "org-b");

        a.eq("el manual gana siempre", "org-z",
                OrgSelector.choose(dos, "org-z", "org-a", u -> true).orgUuid);
        a.eq("el manual gana aunque no responda", "org-z",
                OrgSelector.choose(dos, "org-z", "org-a", u -> false).orgUuid);

        a.eq("sin manual, lastActiveOrg si responde", "org-b",
                OrgSelector.choose(dos, null, "org-b", u -> true).orgUuid);
        a.eq("lastActiveOrg que no responde cae a la unica que si", "org-a",
                OrgSelector.choose(dos, null, "org-b", u -> u.equals("org-a")).orgUuid);
        a.eq("lastActiveOrg ajeno a la lista igual se intenta", "org-c",
                OrgSelector.choose(dos, null, "org-c", u -> true).orgUuid);

        a.eq("sin pistas y solo una responde", "org-b",
                OrgSelector.choose(dos, null, null, u -> u.equals("org-b")).orgUuid);
        // Lo que NO debe hacer: elegir por el usuario.
        a.eq("sin pistas y varias responden, no elige", null,
                OrgSelector.choose(dos, null, null, u -> true).orgUuid);
        a.isTrue("sin pistas y varias responden, pide elegir",
                OrgSelector.choose(dos, null, null, u -> true).ambiguous);
        a.eq("si ninguna responde, null", null,
                OrgSelector.choose(dos, null, null, u -> false).orgUuid);
        a.isTrue("si ninguna responde no es ambiguo",
                !OrgSelector.choose(dos, null, null, u -> false).ambiguous);
        a.eq("lista vacia sin manual, null", null,
                OrgSelector.choose(Arrays.asList(), null, null, u -> true).orgUuid);

        // Llamadas a la sonda: cada una es una peticion de red real.
        Contador manual = new Contador(u -> true);
        OrgSelector.choose(dos, "org-z", "org-a", manual);
        a.eq("con manual no se sondea nada", 0, manual.llamadas);

        Contador activa = new Contador(u -> true);
        OrgSelector.choose(dos, null, "org-b", activa);
        a.eq("lastActiveOrg que responde: una sola sonda", 1, activa.llamadas);

        Contador activaNoResponde = new Contador(u -> u.equals("org-a"));
        OrgSelector.choose(dos, null, "org-b", activaNoResponde);
        a.eq("lastActiveOrg que falla no se vuelve a sondear en la lista",
                2, activaNoResponde.llamadas);

        a.eq("lastActiveOrg que falla y quedan varias: ambiguo, no la unica que sobra", null,
                OrgSelector.choose(Arrays.asList("a", "b", "c"), null, "c",
                        u -> !u.equals("c")).orgUuid);
        a.isTrue("lastActiveOrg que falla y quedan varias: pide elegir",
                OrgSelector.choose(Arrays.asList("a", "b", "c"), null, "c",
                        u -> !u.equals("c")).ambiguous);
        a.eq("manual vacio se trata como ausente", "org-b",
                OrgSelector.choose(dos, "", "org-b", u -> true).orgUuid);
        a.eq("manual vacio sin pistas: se sigue la regla normal", "org-a",
                OrgSelector.choose(dos, "", null, u -> u.equals("org-a")).orgUuid);

        Contador varias = new Contador(u -> true);
        OrgSelector.choose(Arrays.asList("org-a", "org-b", "org-c", "org-d"), null, null, varias);
        a.eq("varias responden: se corta al segundo sin sondear el resto",
                2, varias.llamadas);

        Contador ninguna = new Contador(u -> false);
        OrgSelector.choose(dos, null, null, ninguna);
        a.eq("ninguna responde: se sondea cada una una vez", 2, ninguna.llamadas);
    }
}
