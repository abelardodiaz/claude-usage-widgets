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

    /** Resultado de sondear una organizacion. */
    public enum Answer {
        /** `/usage` respondio bien. */
        RESPONDS,
        /** El servidor contesto y esa organizacion NO sirve (p. ej. 403 o 404 de esa org). */
        NO,
        /** No se pudo saber: timeout, sin red, 429, 5xx, reto de Cloudflare. */
        UNKNOWN
    }

    /**
     * Sondea `/usage` de una organizacion.
     *
     * POLITICA (no negociable): un fallo de red NO es un descarte. Quien implemente la sonda
     * devuelve {@link Answer#NO} SOLO cuando el servidor contesto y esa organizacion no sirve;
     * todo lo demas (timeout, sin conexion, 429, 5xx, reto) es {@link Answer#UNKNOWN}. Mapear un
     * timeout a NO convertiria "responden varias" en "responde una sola" y el widget elegiria en
     * silencio una organizacion ajena, que es justo lo que D2 prohibe.
     */
    public interface Probe { Answer probe(String orgUuid); }

    private OrgSelector() {}

    /** Lo que decidio la regla: una organizacion, "ninguna sirve", o "que elija el usuario". */
    public static final class Choice {
        public final String orgUuid;      // null si hay que preguntar o si ninguna sirve
        public final boolean ambiguous;   // true: varias responden, o un sondeo fallo; que elija el usuario
        Choice(String orgUuid, boolean ambiguous) {
            this.orgUuid = orgUuid;
            this.ambiguous = ambiguous;
        }
    }

    /**
     * Aplica D2. Politica ante fallos de red: si algun sondeo dio {@link Answer#UNKNOWN} y no hay
     * seleccion manual, el resultado es "que elija el usuario" (`ambiguous`), salvo que la
     * `lastActiveOrg` responda, que gana antes de mirar nada mas. Un UNKNOWN nunca cuenta como
     * descarte: no se puede afirmar que "solo una responde" sin haber visto a las demas.
     */
    public static Choice choose(List<String> organizations, String manual,
                                String lastActiveOrg, Probe probe) {
        if (manual != null && !manual.isEmpty()) return new Choice(manual, false);

        String probedAlready = null;
        if (lastActiveOrg != null && !lastActiveOrg.isEmpty()) {
            Answer first = probe.probe(lastActiveOrg);
            if (first == Answer.RESPONDS) return new Choice(lastActiveOrg, false);
            // Un UNKNOWN ya decide el resultado (ambiguous): seguir sondeando la lista son N
            // peticiones inutiles justo cuando la red va mal.
            if (first == Answer.UNKNOWN) return new Choice(null, true);
            probedAlready = lastActiveOrg;
        }

        List<String> responding = new ArrayList<>();
        for (String uuid : organizations) {
            if (uuid.equals(probedAlready)) continue;
            Answer answer = probe.probe(uuid);
            if (answer == Answer.UNKNOWN) {
                return new Choice(null, true);   // fallo de red: que elija el usuario, sin mas sondeos
            } else if (answer == Answer.RESPONDS) {
                responding.add(uuid);
                if (responding.size() == 2) break;   // ya es ambiguo: no hace falta el resto
            }
        }
        if (responding.size() == 1) return new Choice(responding.get(0), false);
        if (responding.isEmpty()) return new Choice(null, false);
        return new Choice(null, true);   // varias responden y ninguna pista: no se adivina
    }
}
