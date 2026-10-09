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
    private static final class Counter implements OrgSelector.Probe {
        private final java.util.function.Function<String, OrgSelector.Answer> answers;
        int calls = 0;

        Counter(java.util.function.Function<String, OrgSelector.Answer> answers) {
            this.answers = answers;
        }

        @Override public OrgSelector.Answer probe(String orgUuid) {
            calls++;
            return answers.apply(orgUuid);
        }
    }

    private static OrgSelector.Answer yn(boolean responds) {
        return responds ? OrgSelector.Answer.RESPONDS : OrgSelector.Answer.NO;
    }

    /** Atajo: true -> RESPONDS, false -> NO (el servidor contesto y no sirve). */
    private static OrgSelector.Probe p(java.util.function.Predicate<String> responds) {
        return u -> responds.test(u) ? OrgSelector.Answer.RESPONDS : OrgSelector.Answer.NO;
    }

    public static void run(Assert a) {
        List<String> two = Arrays.asList("org-a", "org-b");

        a.eq("el manual gana siempre", "org-z",
                OrgSelector.choose(two, "org-z", "org-a", p(u -> true)).orgUuid);
        a.eq("el manual gana aunque no responda", "org-z",
                OrgSelector.choose(two, "org-z", "org-a", p(u -> false)).orgUuid);

        a.eq("sin manual, lastActiveOrg si responde", "org-b",
                OrgSelector.choose(two, null, "org-b", p(u -> true)).orgUuid);
        a.eq("lastActiveOrg que no responde cae a la unica que si", "org-a",
                OrgSelector.choose(two, null, "org-b", p(u -> u.equals("org-a"))).orgUuid);
        a.eq("lastActiveOrg ajeno a la lista igual se intenta", "org-c",
                OrgSelector.choose(two, null, "org-c", p(u -> true)).orgUuid);

        a.eq("sin pistas y solo una responde", "org-b",
                OrgSelector.choose(two, null, null, p(u -> u.equals("org-b"))).orgUuid);
        // Lo que NO debe hacer: elegir por el usuario.
        a.eq("sin pistas y varias responden, no elige", null,
                OrgSelector.choose(two, null, null, p(u -> true)).orgUuid);
        a.isTrue("sin pistas y varias responden, pide elegir",
                OrgSelector.choose(two, null, null, p(u -> true)).ambiguous);
        a.eq("si ninguna responde, null", null,
                OrgSelector.choose(two, null, null, p(u -> false)).orgUuid);
        a.isTrue("si ninguna responde no es ambiguo",
                !OrgSelector.choose(two, null, null, p(u -> false)).ambiguous);
        a.eq("lista vacia sin manual, null", null,
                OrgSelector.choose(Arrays.asList(), null, null, p(u -> true)).orgUuid);

        // Llamadas a la sonda: cada una es una peticion de red real.
        Counter manual = new Counter(u -> yn(true));
        OrgSelector.choose(two, "org-z", "org-a", manual);
        a.eq("con manual no se sondea nada", 0, manual.calls);

        Counter active = new Counter(u -> yn(true));
        OrgSelector.choose(two, null, "org-b", active);
        a.eq("lastActiveOrg que responde: una sola sonda", 1, active.calls);

        Counter activeFails = new Counter(u -> yn(u.equals("org-a")));
        OrgSelector.choose(two, null, "org-b", activeFails);
        a.eq("lastActiveOrg que falla no se vuelve a sondear en la lista",
                2, activeFails.calls);

        a.eq("lastActiveOrg que falla y quedan varias: ambiguo, no la unica que sobra", null,
                OrgSelector.choose(Arrays.asList("a", "b", "c"), null, "c",
                        p(u -> !u.equals("c"))).orgUuid);
        a.isTrue("lastActiveOrg que falla y quedan varias: pide elegir",
                OrgSelector.choose(Arrays.asList("a", "b", "c"), null, "c",
                        p(u -> !u.equals("c"))).ambiguous);
        a.eq("manual vacio se trata como ausente", "org-b",
                OrgSelector.choose(two, "", "org-b", p(u -> true)).orgUuid);
        a.eq("manual vacio sin pistas: se sigue la regla normal", "org-a",
                OrgSelector.choose(two, "", null, p(u -> u.equals("org-a"))).orgUuid);

        Counter several = new Counter(u -> yn(true));
        OrgSelector.choose(Arrays.asList("org-a", "org-b", "org-c", "org-d"), null, null, several);
        a.eq("varias responden: se corta al segundo sin sondear el resto",
                2, several.calls);

        Counter none = new Counter(u -> yn(false));
        OrgSelector.choose(two, null, null, none);
        a.eq("ninguna responde: se sondea cada una una vez", 2, none.calls);

        // Politica: un fallo de red (UNKNOWN) NO es un descarte.
        OrgSelector.Choice oneUnknown = OrgSelector.choose(Arrays.asList("a", "b"), null, null,
                u -> u.equals("a") ? OrgSelector.Answer.RESPONDS : OrgSelector.Answer.UNKNOWN);
        a.eq("una responde y otra falla por red: no se elige la que quedo", null, oneUnknown.orgUuid);
        a.isTrue("una responde y otra falla por red: que elija el usuario", oneUnknown.ambiguous);
        OrgSelector.Choice allUnknown = OrgSelector.choose(two, null, null,
                u -> OrgSelector.Answer.UNKNOWN);
        a.isTrue("todas fallan por red: que elija el usuario, no 'ninguna sirve'",
                allUnknown.ambiguous && allUnknown.orgUuid == null);
        OrgSelector.Choice activeUnknown = OrgSelector.choose(Arrays.asList("a", "b", "c"), null, "c",
                u -> u.equals("c") ? OrgSelector.Answer.UNKNOWN
                        : u.equals("a") ? OrgSelector.Answer.RESPONDS : OrgSelector.Answer.NO);
        a.isTrue("lastActiveOrg falla por red y otra responde: que elija el usuario",
                activeUnknown.ambiguous && activeUnknown.orgUuid == null);
        a.eq("el manual gana aunque todo falle por red", "org-z",
                OrgSelector.choose(two, "org-z", null, u -> OrgSelector.Answer.UNKNOWN).orgUuid);
        a.eq("lastActiveOrg que responde gana aunque otras fallarian", "org-b",
                OrgSelector.choose(two, null, "org-b", u -> u.equals("org-b")
                        ? OrgSelector.Answer.RESPONDS : OrgSelector.Answer.UNKNOWN).orgUuid);
        a.eq("NO explicito en todas sigue siendo 'ninguna sirve' (no ambiguo)", false,
                OrgSelector.choose(two, null, null, p(u -> false)).ambiguous);

        // ambiguous es false en los caminos que deciden.
        a.eq("manual: no ambiguo", false, OrgSelector.choose(two, "org-z", null, p(u -> true)).ambiguous);
        a.eq("lastActiveOrg: no ambiguo", false,
                OrgSelector.choose(two, null, "org-b", p(u -> true)).ambiguous);
        a.eq("una sola responde: no ambiguo", false,
                OrgSelector.choose(two, null, null, p(u -> u.equals("org-a"))).ambiguous);
    }
}
