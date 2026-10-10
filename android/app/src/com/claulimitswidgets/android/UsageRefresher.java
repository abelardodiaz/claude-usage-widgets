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
import java.security.GeneralSecurityException;
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

    /** De donde sale la cookie. En produccion es `SessionStore.load`. */
    interface Cookies { String load() throws GeneralSecurityException, IOException; }

    /** Cuanto vale la lista de organizaciones recordada antes de volver a pedirla a la red. */
    private static final long ORGS_TTL_SECONDS = 6 * 3600;

    private final SessionStore session;
    private final Cookies cookieSource;
    private final SampleStore samples;      // null si no se pudo abrir el directorio
    private final SnapshotStore meta;
    private final SharedPreferences prefs;
    private final RemoteFactory remoteFactory;
    private final Clock clock;

    /** Epoca de sesion al empezar el refresco en curso (ver Session.EPOCH). Bajo LOCK. */
    private long epoch;

    public UsageRefresher(Context ctx) {
        this(new SessionStore(app(ctx)), openSamples(app(ctx)), Session.snapshotFor(app(ctx)),
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
        this(session, session::load, samples, meta, prefs, remoteFactory, clock);
    }

    UsageRefresher(SessionStore session, Cookies cookieSource, SampleStore samples,
                   SnapshotStore meta, SharedPreferences prefs, RemoteFactory remoteFactory,
                   Clock clock) {
        this.session = session;
        this.cookieSource = cookieSource;
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

    /** Almacen de modelo/orgs/hora que usa este refrescador. Lo ata la prueba de la ruta. */
    SnapshotStore meta() { return meta; }

    /**
     * Un solo refresco a la vez. `runNow` (toque, login) y el JobService pueden coincidir, y
     * `SampleStore.append` es leer-y-reescribir: sin esto, dos a la vez se pisarian.
     */
    static final Object LOCK = new Object();

    public Snapshot refresh() {
        synchronized (LOCK) {
            epoch = Session.EPOCH.get();
            try {
                return refreshLocked();
            } catch (RuntimeException e) {
                // Algo inesperado (p. ej. del analizador). No se cita el mensaje: podria llevar
                // un trozo de la respuesta. Cuenta para el backoff, para no insistir en bucle.
                return safeKeepOld(Snapshot.Problem.BAD_FORMAT);
            }
        }
    }

    private static final String KEY_ATTEMPT = "backoff_attempt";
    private static final String KEY_NEXT_ALLOWED = "backoff_next_allowed_at";
    private static final String KEY_LAST_PROBLEM = "backoff_last_problem";

    /**
     * Mientras no se cierre sesion se puede escribir. Si el usuario cerro sesion (la epoca cambio o
     * la sesion ya no existe), NINGUNA escritura de este refresco debe ocurrir: resucitaria datos
     * de cuenta (uuid, muestras, claves de espera) que el logout acaba de borrar.
     */
    private boolean alive() {
        return Session.EPOCH.get() == epoch && session.hasSession();
    }

    private Snapshot refreshLocked() {
        String cookies;
        try {
            cookies = cookieSource.load();
        } catch (GeneralSecurityException | IOException e) {
            // El Keystore puede fallar de forma transitoria (SessionStore lo propaga a proposito
            // sin borrar nada). No es "sin sesion": se conserva el dato y se reintenta. Decir
            // NO_SESSION aqui podria llevar a quien reaccione a eso a cerrar sesion de verdad.
            return keepOld(Snapshot.Problem.OFFLINE);
        }
        // Mismo criterio que minimalCookies: un `sessionKey=` vacio no es una sesion.
        if (cookies == null || !UsageClient.minimalCookies(cookies).contains("sessionKey=")) {
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
            Instant at = clock.now();

            // Con una pista basta: no se llama a /organizations en cada refresco. Pero la lista
            // recordada caduca, y se revalida tambien si con ella no hay eleccion (abajo).
            List<UsageClient.Org> known = meta.knownOrgs();
            boolean cached = (manual != null || lastActive != null) && !known.isEmpty()
                    && orgsFresh(at);
            List<UsageClient.Org> orgs = cached ? known : fetchOrgs(remote, at);

            Selection sel = select(remote, orgs, manual, lastActive);
            sel.rethrow();   // el fracaso real gana sobre "que elija el usuario"
            if (cached && (sel.choice.ambiguous || sel.choice.orgUuid == null)) {
                // La cache puede estar vieja (el usuario salio de una organizacion o entro en
                // otra): sin esto el widget se quedaria en BAD_FORMAT sin salida. Una vez por ciclo.
                orgs = fetchOrgs(remote, at);
                sel = select(remote, orgs, manual, lastActive);
                sel.rethrow();
            }
            OrgSelector.Choice choice = sel.choice;
            if (choice.ambiguous) return keepOld(Snapshot.Problem.CHOOSE_ORG);
            if (choice.orgUuid == null) return keepOld(Snapshot.Problem.BAD_FORMAT);

            // Si la eleccion vino del manual, la sonda no corrio: hay que consultar.
            UsageModel model = sel.fetched.get(choice.orgUuid);
            if (model == null) model = remote.usage(choice.orgUuid);

            // Si el usuario cerro sesion mientras se consultaba, no se escribe nada: la muestra
            // resucitaria el historico que el logout acaba de borrar.
            if (!alive()) return Snapshot.of(Snapshot.Problem.NO_SESSION);

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

    private boolean orgsFresh(Instant at) {
        Instant f = meta.orgsFetchedAt();
        if (f == null || f.isAfter(at)) return false;   // sin hora o reloj atrasado: no se fia
        return at.getEpochSecond() - f.getEpochSecond() < ORGS_TTL_SECONDS;
    }

    private List<UsageClient.Org> fetchOrgs(Remote remote, Instant at) throws IOException,
            AuthExpiredException, BlockedException, UsageClient.RetryLaterException,
            UnrecognizedFormatException {
        List<UsageClient.Org> orgs = remote.organizations();
        if (alive()) meta.rememberOrgs(orgs, at);   // Ajustes los necesita aunque el resto falle
        return orgs;
    }

    /** Resultado de aplicar D2: la eleccion, los modelos ya consultados y el fracaso, si lo hubo. */
    private static final class Selection {
        OrgSelector.Choice choice;
        final Map<String, UsageModel> fetched = new HashMap<>();
        Exception fatal;

        void rethrow() throws IOException, AuthExpiredException, BlockedException,
                UsageClient.RetryLaterException {
            if (fatal instanceof AuthExpiredException) throw (AuthExpiredException) fatal;
            if (fatal instanceof BlockedException) throw (BlockedException) fatal;
            if (fatal instanceof UsageClient.RetryLaterException) {
                throw (UsageClient.RetryLaterException) fatal;
            }
            if (fatal instanceof IOException) throw (IOException) fatal;
        }
    }

    private static Selection select(Remote remote, List<UsageClient.Org> orgs, String manual,
                                    String lastActive) {
        // `OrgSelector` recibe solo uuids: con "uuid|nombre" el uuid no pasaria la validacion.
        List<String> uuids = new ArrayList<>();
        for (UsageClient.Org o : orgs) uuids.add(o.uuid);
        // La sonda guarda el modelo por organizacion: la consulta que decide cual es la
        // misma que se muestra, en vez de tirarla y repetirla.
        final Selection sel = new Selection();
        sel.choice = OrgSelector.choose(uuids, manual, lastActive, uuid -> {
            try {
                sel.fetched.put(uuid, remote.usage(uuid));
                return OrgSelector.Answer.RESPONDS;
            } catch (UnrecognizedFormatException e) {
                // El servidor contesto y esta organizacion no sirve: se prueba la siguiente.
                return OrgSelector.Answer.NO;
            } catch (AuthExpiredException | BlockedException
                    | UsageClient.RetryLaterException | IOException e) {
                // Fallo del intento entero, NO un descarte: UNKNOWN (nunca NO) para que una
                // red mala no haga elegir en silencio una organizacion ajena.
                //
                // CONTRADICCION CONOCIDA entre contrato e implementacion: `Answer.NO` dice que
                // un 403 de ESA organizacion es "no sirve", pero `UsageClient.check` lanza el
                // mismo BlockedException para ese 403 y para el reto de Cloudflare
                // (cf-mitigated / HTML), y desde aqui no se pueden distinguir. Se elige el lado
                // seguro: se trata como bloqueo global. Coste: en una cuenta con varias
                // organizaciones donde una da 403, el dueno ve BLOCKED y no la que si funciona.
                // Para cerrarlo hace falta un tipo distinto en UsageClient (403 por organizacion
                // vs reto); queda para la 4.3 o la F5.
                sel.fatal = e;
                return OrgSelector.Answer.UNKNOWN;
            }
        });
        return sel;
    }

    /** Un 200 reinicia la cuenta: el siguiente fallo vuelve a esperar un minuto, no media hora. */
    private void clearBackoff() {
        wipeBackoff(prefs.edit()).apply();
    }

    private static SharedPreferences.Editor wipeBackoff(SharedPreferences.Editor e) {
        return e.remove(KEY_ATTEMPT).remove(KEY_NEXT_ALLOWED).remove(KEY_LAST_PROBLEM);
    }

    /**
     * Lo llaman el login y el selector de organizacion: cuando el usuario arregla el problema,
     * la espera acumulada ya no tiene sentido.
     */
    public static void clearBackoff(Context ctx) {
        wipeBackoff(app(ctx).getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                .edit()).apply();
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
    /** `refresh()` no lanza ni siquiera en el camino de recuperacion. */
    private Snapshot safeKeepOld(Snapshot.Problem p) {
        try {
            return keepOld(p);
        } catch (RuntimeException e) {
            return Snapshot.of(p);
        }
    }

    private Snapshot keepOld(Snapshot.Problem p) {
        // Solo cuentan los fallos que se arreglan esperando. Un 401 o una organizacion por
        // elegir no mejoran con el tiempo: los arregla el usuario, y hacerle esperar media hora
        // despues de volver a entrar seria absurdo.
        // Y solo si la sesion sigue viva: tras un logout, estas claves resucitarian en un archivo
        // de preferencias recien borrado.
        if ((p == Snapshot.Problem.OFFLINE || p == Snapshot.Problem.BLOCKED
                || p == Snapshot.Problem.BAD_FORMAT) && alive()) {
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
        try {
            return lastUnsafe();
        } catch (RuntimeException e) {
            // Un modelo guardado que el nucleo no digiere no puede dejar al widget sin pintar,
            // ni hacer que cada refresco siguiente lance.
            return Snapshot.of(Snapshot.Problem.BAD_FORMAT);
        }
    }

    private Snapshot lastUnsafe() {
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
        if (samples != null) {
            try {
                // `false` = el almacen descarto la muestra. No hay nada util que hacer: el
                // Snapshot se calcula con lo que SI hay en disco, asi que un descarte nunca
                // pinta un numero falso. Las pruebas comprueban el disco, no este valor.
                samples.append(new Sample(now, model.weekly.percent, model.weekly.resetsAt), now);
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
