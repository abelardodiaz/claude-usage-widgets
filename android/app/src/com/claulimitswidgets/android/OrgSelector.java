package com.claulimitswidgets.android;

import java.util.ArrayList;
import java.util.List;

/**
 * Decision D2: que organizacion mira el widget cuando la cuenta tiene varias.
 *
 * Orden: lo que el usuario eligio a mano, luego la que la web considera activa
 * (`lastActiveOrg`), luego la unica que responda; si responden varias, se pregunta. Nunca se elige "la del plan mas alto":
 * eso mostraria una cuota que el usuario no esta usando.
 *
 * Cada sondeo es una peticion de red real: no se repite una organizacion ya sondeada y, si ya
 * hay dos que responden, no se sondea el resto (con dos basta para saber que hay que preguntar).
 */
public final class OrgSelector {

    /** Dice si `/usage` de esa organizacion responde bien. */
    public interface Probe { boolean responds(String orgUuid); }

    private OrgSelector() {}

    /** Lo que decidio la regla: una organizacion, "ninguna sirve", o "que elija el usuario". */
    public static final class Choice {
        public final String orgUuid;      // null si hay que preguntar o si ninguna sirve
        public final boolean ambiguous;   // true: varias responden y no hay pista
        Choice(String orgUuid, boolean ambiguous) {
            this.orgUuid = orgUuid;
            this.ambiguous = ambiguous;
        }
    }

    public static Choice choose(List<String> organizations, String manual,
                                String lastActiveOrg, Probe probe) {
        if (manual != null && !manual.isEmpty()) return new Choice(manual, false);

        String yaSondeada = null;
        if (lastActiveOrg != null && !lastActiveOrg.isEmpty()) {
            if (probe.responds(lastActiveOrg)) return new Choice(lastActiveOrg, false);
            yaSondeada = lastActiveOrg;
        }

        List<String> responden = new ArrayList<>();
        for (String uuid : organizations) {
            if (uuid.equals(yaSondeada)) continue;
            if (probe.responds(uuid)) {
                responden.add(uuid);
                if (responden.size() == 2) break;   // ya es ambiguo: no hace falta el resto
            }
        }
        if (responden.size() == 1) return new Choice(responden.get(0), false);
        if (responden.isEmpty()) return new Choice(null, false);
        return new Choice(null, true);   // varias responden y ninguna pista: no se adivina
    }
}
