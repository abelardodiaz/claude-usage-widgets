package com.claulimitswidgets.android;

import android.content.Context;
import android.content.SharedPreferences;

import com.claudewidgets.core.Colors;
import com.claudewidgets.core.DayUsage;
import com.claudewidgets.core.History;
import com.claudewidgets.core.Projection;
import com.claudewidgets.core.Sample;
import com.claudewidgets.core.UnrecognizedFormatException;
import com.claudewidgets.core.UsageModel;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orquesta una actualizacion: cookie -> consulta -> muestra -> nucleo -> Snapshot.
 *
 * No calcula nada por su cuenta: reparto por dia, proyecciones y marca de ritmo salen de
 * `android/core`, que es la unica implementacion de R0-R7.
 *
 * `refresh()` nunca lanza: un widget que se cae no es un widget. Hace red: no llamarlo desde el
 * hilo principal.
 */
public final class UsageRefresher {

    /** La parte de red, separada para probar los fracasos con entradas sinteticas. */
    interface Remote {
        List<UsageClient.Org> organizations() throws IOException, AuthExpiredException,
                BlockedException, UsageClient.RetryLaterException, UnrecognizedFormatException;
        UsageModel usage(String orgUuid) throws IOException, AuthExpiredException,
                BlockedException, UsageClient.RetryLaterException, UnrecognizedFormatException;
    }

    interface RemoteFactory { Remote create(String cookies); }

    interface Clock { Instant now(); }

    private final SessionStore session;
    private final SampleStore samples;      // null si no se pudo abrir el directorio
    private final SnapshotStore meta;
    private final SharedPreferences prefs;
    private final RemoteFactory remoteFactory;
    private final Clock clock;

    /** true si la muestra de la ultima consulta buena se guardo; false si el almacen la rechazo. */
    volatile boolean lastSampleStored;

    public UsageRefresher(Context ctx) {
        this(new SessionStore(app(ctx)), openSamples(app(ctx)), new SnapshotStore(app(ctx)),
                app(ctx).getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE),
                cookies -> productionRemote(app(ctx), cookies),
                Instant::now);
    }

    private static Context app(Context ctx) {
        return ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
    }

    private static Remote productionRemote(Context app, String cookies) {
        UsageClient c = new UsageClient(cookies, userAgent(app));
        return new Remote() {
            @Override public List<UsageClient.Org> organizations()
                    throws IOException, AuthExpiredException, BlockedException,
                    UsageClient.RetryLaterException, UnrecognizedFormatException {
                return c.organizations();
            }
            @Override public UsageModel usage(String orgUuid)
                    throws IOException, AuthExpiredException, BlockedException,
                    UsageClient.RetryLaterException, UnrecognizedFormatException {
                return c.usage(orgUuid);
            }
        };
    }

    /** Para las pruebas: todo inyectado. */
    UsageRefresher(SessionStore session, SampleStore samples, SnapshotStore meta,
                   SharedPreferences prefs, RemoteFactory remoteFactory, Clock clock) {
        this.session = session;
        this.samples = samples;
        this.meta = meta;
        this.prefs = prefs;
        this.remoteFactory = remoteFactory;
        this.clock = clock;
    }

    /** Por `Session.samplesFor`: quien escribe y quien borra al cerrar sesion comparten la via. */
    private static SampleStore openSamples(Context app) {
        try {
            return Session.samplesFor(app);
        } catch (RuntimeException e) {
            return null;   // sin directorio no hay historico; el dato de ahora igual se muestra
        }
    }

    /** Almacen de muestras que usa este refrescador. Lo ata la prueba de la ruta. */
    SampleStore samples() { return samples; }

    /**
     * Un solo refresco a la vez. `runNow` (toque, login) y el JobService pueden coincidir, y
     * `SampleStore.append` es leer-y-reescribir: sin esto, dos a la vez se pisarian.
     */
    static final Object LOCK = new Object();

    public Snapshot refresh() {
        synchronized (LOCK) {
            try {
                return refreshLocked();
            } catch (RuntimeException e) {
                // Algo inesperado (p. ej. del analizador). No se cita el mensaje: podria llevar
                // un trozo de la respuesta. Cuenta para el backoff, para no insistir en bucle.
                return keepOld(Snapshot.Problem.BAD_FORMAT);
            }
        }
    }

    private static final String KEY_ATTEMPT = "backoff_attempt";
    private static final String KEY_NEXT_ALLOWED = "backoff_next_allowed_at";
    private static final String KEY_LAST_PROBLEM = "backoff_last_problem";

    private Snapshot refreshLocked() {
        String cookies;
        try {
            cookies = session.load();
        } catch (Exception e) {
            return Snapshot.of(Snapshot.Problem.NO_SESSION);
        }
        if (cookies == null || !cookies.contains("sessionKey=")) {
            return Snapshot.of(Snapshot.Problem.NO_SESSION);
        }

        // Backoff: si el ultimo intento fallo, no se vuelve a la red hasta que toque.
        // Aqui NO se llama a keepOld: incrementaria el contador sin haber hecho una sola
        // peticion, y cinco toques en un minuto dejarian al widget media hora sin consultar.
        long notBefore = prefs.getLong(KEY_NEXT_ALLOWED, 0L);
        if (clock.now().getEpochSecond() < notBefore) {
            Snapshot old = last();
            Snapshot.Problem p = problemFromName(prefs.getString(KEY_LAST_PROBLEM, null));
            return old.hasData() ? old.withProblem(p) : Snapshot.of(p);
        }

        Remote remote = remoteFactory.create(cookies);
        try {
            String manual = prefs.getString(SettingsActivity.KEY_ORG, null);
            String lastActive = UsageClient.lastActiveOrg(cookies);

            // Con una pista basta: no se llama a /organizations en cada refresco.
            List<UsageClient.Org> orgs =
                    (manual != null || lastActive != null) && !meta.knownOrgs().isEmpty()
                            ? meta.knownOrgs()
                            : remote.organizations();
            meta.rememberOrgs(orgs);   // Ajustes los necesita aunque el resto falle
            // `OrgSelector` recibe solo uuids: con "uuid|nombre" el uuid no pasaria la validacion.
            List<String> uuids = new ArrayList<>();
            for (UsageClient.Org o : orgs) uuids.add(o.uuid);

            // La sonda guarda el modelo por organizacion: la consulta que decide cual es la
            // misma que se muestra, en vez de tirarla y repetirla.
            final Map<String, UsageModel> fetched = new HashMap<>();
            final Exception[] fatal = new Exception[1];
            OrgSelector.Choice choice = OrgSelector.choose(uuids, manual, lastActive, uuid -> {
                try {
                    fetched.put(uuid, remote.usage(uuid));
                    return OrgSelector.Answer.RESPONDS;
                } catch (UnrecognizedFormatException e) {
                    // El servidor contesto y esta organizacion no sirve: se prueba la siguiente.
                    return OrgSelector.Answer.NO;
                } catch (AuthExpiredException | BlockedException
                        | UsageClient.RetryLaterException | IOException e) {
                    // Fallo del intento entero, NO un descarte: UNKNOWN (nunca NO) para que una
                    // red mala no haga elegir en silencio una organizacion ajena.
                    fatal[0] = e;
                    return OrgSelector.Answer.UNKNOWN;
                }
            });
            // El fracaso real gana sobre "que elija el usuario": sin red se dice "sin conexion".
            if (fatal[0] instanceof AuthExpiredException) throw (AuthExpiredException) fatal[0];
            if (fatal[0] instanceof BlockedException) throw (BlockedException) fatal[0];
            if (fatal[0] instanceof UsageClient.RetryLaterException) {
                throw (UsageClient.RetryLaterException) fatal[0];
            }
            if (fatal[0] instanceof IOException) throw (IOException) fatal[0];
            if (choice.ambiguous) return keepOld(Snapshot.Problem.CHOOSE_ORG);
            if (choice.orgUuid == null) return keepOld(Snapshot.Problem.BAD_FORMAT);

            // Si la eleccion vino del manual, la sonda no corrio: hay que consultar.
            UsageModel model = fetched.get(choice.orgUuid);
            if (model == null) model = remote.usage(choice.orgUuid);

            // Si el usuario cerro sesion mientras se consultaba, no se escribe nada: la muestra
            // resucitaria el historico que el logout acaba de borrar.
            if (!session.hasSession()) return Snapshot.of(Snapshot.Problem.NO_SESSION);

            Instant now = clock.now();
            clearBackoff();
            return compute(model, now);
        } catch (AuthExpiredException e) {
            return keepOld(Snapshot.Problem.AUTH_EXPIRED);
        } catch (BlockedException e) {
            return keepOld(Snapshot.Problem.BLOCKED);
        } catch (UsageClient.RetryLaterException | IOException e) {
            // Sin red, DNS caido, 429 o 5xx: el ultimo dato sigue valiendo, con su hora.
            return keepOld(Snapshot.Problem.OFFLINE);
        } catch (UnrecognizedFormatException e) {
            return keepOld(Snapshot.Problem.BAD_FORMAT);
        }
    }

    /** Un 200 reinicia la cuenta: el siguiente fallo vuelve a esperar un minuto, no media hora. */
    private void clearBackoff() {
        prefs.edit().remove(KEY_ATTEMPT).remove(KEY_NEXT_ALLOWED).remove(KEY_LAST_PROBLEM).apply();
    }

    /**
     * Lo llaman el login y el selector de organizacion: cuando el usuario arregla el problema,
     * la espera acumulada ya no tiene sentido.
     */
    public static void clearBackoff(Context ctx) {
        ctx.getApplicationContext()
                .getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_ATTEMPT).remove(KEY_NEXT_ALLOWED).remove(KEY_LAST_PROBLEM)
                .apply();
    }

    private static Snapshot.Problem problemFromName(String name) {
        if (name == null) return Snapshot.Problem.OFFLINE;
        try {
            return Snapshot.Problem.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Snapshot.Problem.OFFLINE;
        }
    }

    /**
     * Lo ultimo que se pudo calcular, marcado con el problema de ahora, y se anota el backoff:
     * sin esto `Backoff` quedaria definido y probado pero nunca aplicado.
     */
    private Snapshot keepOld(Snapshot.Problem p) {
        // Solo cuentan los fallos que se arreglan esperando. Un 401 o una organizacion por
        // elegir no mejoran con el tiempo: los arregla el usuario, y hacerle esperar media hora
        // despues de volver a entrar seria absurdo.
        if (p == Snapshot.Problem.OFFLINE || p == Snapshot.Problem.BLOCKED
                || p == Snapshot.Problem.BAD_FORMAT) {
            int attempt = prefs.getInt(KEY_ATTEMPT, 0);
            prefs.edit()
                    .putInt(KEY_ATTEMPT, attempt + 1)
                    .putLong(KEY_NEXT_ALLOWED,
                            clock.now().getEpochSecond() + Backoff.seconds(attempt))
                    .putString(KEY_LAST_PROBLEM, p.name())
                    .apply();
        }
        Snapshot old = last();
        return old.hasData() ? old.withProblem(p) : Snapshot.of(p);
    }

    /**
     * Reconstruye el ultimo estado conocido sin tocar la red. Nunca null.
     *
     * Esto es lo que hace que "sin conexion" muestre el dato viejo con su hora en vez de nada
     * (Review Focus 1). Si devolviera un Snapshot sin modelo, el widget se quedaria en blanco.
     */
    public Snapshot last() {
        if (!session.hasSession()) return Snapshot.of(Snapshot.Problem.NO_SESSION);
        UsageModel model = meta.lastModel();
        Instant fetchedAt = meta.lastFetchInstant();
        // Hay sesion pero todavia no se ha consultado nunca. No es "sin conexion": es que
        // acaba de empezar. Decir OFFLINE aqui seria mentir en el caso mas comun del primer uso.
        if (model == null || fetchedAt == null) return Snapshot.of(Snapshot.Problem.LOADING);
        Instant now = clock.now();
        List<Sample> all = loadSamples(now);
        // `problem` va en null: `last()` describe lo que se sabe, no un fallo. La edad ya la
        // muestra `fetchedAt`.
        return build(model, all, now, fetchedAt, null);
    }

    private Snapshot compute(UsageModel model, Instant now) {
        // El mismo `now` construye la muestra y se pasa al almacen: el rechazo de `t` futuro no
        // tiene tolerancia suficiente para dos instantes capturados por separado.
        lastSampleStored = false;
        if (samples != null) {
            try {
                lastSampleStored = samples.append(
                        new Sample(now, model.weekly.percent, model.weekly.resetsAt), now);
            } catch (IOException ignored) {
                // Si no se pudo guardar la muestra, el dato de ahora igual se muestra.
            }
        }
        meta.remember(model, now);
        return build(model, loadSamples(now), now, now, null);
    }

    private List<Sample> loadSamples(Instant now) {
        if (samples == null) return Collections.emptyList();
        try {
            return samples.load(now);
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private static Snapshot build(UsageModel model, List<Sample> all, Instant now,
                                  Instant fetchedAt, Snapshot.Problem problem) {
        DayUsage day = History.compute(model.weekly, all, now, ZoneId.systemDefault());
        return new Snapshot(model, day,
                Projection.session(model.session.percent, model.session.resetsAt, now),
                Projection.weekly(model.weekly.percent, model.weekly.resetsAt, all, now),
                Colors.paceMark(model.weekly.resetsAt, now),
                fetchedAt, problem);
    }

    /**
     * El mismo User-Agent que usa el WebView del login: una sola huella hacia claude.ai.
     * Se guarda al iniciar sesion porque crear un WebView desde un JobService no es viable.
     * Sin respaldo a `http.agent`: si no esta guardado es que no hubo login, y una huella
     * distinta a la del WebView es justo lo que podria disparar un reto.
     */
    private static String userAgent(Context app) {
        return app.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                .getString(SettingsActivity.KEY_UA, "");
    }
}
