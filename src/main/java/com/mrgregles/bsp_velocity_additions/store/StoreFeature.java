package com.mrgregles.bsp_velocity_additions.store;

import com.mrgregles.bsp_velocity_additions.config.Messages;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The Fourthwall store (BSP-Server Shop request SH-R1, decisions D38 to D41), off unless {@code store.toml} says
 * {@code enabled = true} and all four secrets are set. Everything that touches the database, Mojang or Fourthwall runs
 * on one worker thread ("BSP Store"), so events, commands and reconciliation never race each other; the HTTP receiver
 * has two threads of its own and only stores events. The receiver opens once the database has been reached and its
 * schema checked; a schema newer than version 1 keeps the whole store off.
 */
public final class StoreFeature {

    private static final int MAX_RETRIES = 10;
    private static final Duration RETRY_AFTER = Duration.ofMinutes(2);

    private final ProxyServer proxy;
    private final Path dataDirectory;
    private final Messages messages;
    private final Logger logger;
    private final AtomicReference<StoreConfig> config;
    private final AtomicReference<RewardsConfig> rewards;
    private final Secrets secrets;
    private final ShopDatabase db;
    private final ScheduledThreadPoolExecutor worker;
    private final ExecutorService httpThreads;
    private final StoreService service;
    private final FourthwallApi api;
    private final ProfileLookup profiles;
    private final Map<String, Integer> retries = new ConcurrentHashMap<>();
    private volatile WebhookReceiver receiver;
    private volatile ScheduledFuture<?> connecting;
    private volatile boolean started;
    /** How often the database is checked; 30 s (tests use less). */
    long tickSeconds = 30;

    StoreFeature(ProxyServer proxy, Path dataDirectory, Messages messages, Logger logger, StoreConfig config,
                         RewardsConfig rewards, Secrets secrets, ShopDatabase db) {
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
        this.messages = messages;
        this.logger = logger;
        this.config = new AtomicReference<>(config);
        this.rewards = new AtomicReference<>(rewards);
        this.secrets = secrets;
        this.db = db;
        this.worker = new ScheduledThreadPoolExecutor(1, daemon("BSP Store"));
        // B10: a retry waiting minutes must not hold up the proxy's shutdown
        this.worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.worker.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        this.httpThreads = Executors.newFixedThreadPool(2, daemon("BSP Store HTTP"));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        MojangProfiles.Sleeper sleep = Thread::sleep;
        ProfileLookup mojang = new MojangProfiles(http, config.mojang().profileUrl(), Duration.ofMinutes(config.mojang().cacheMinutes()),
                Clock.systemUTC(), sleep);
        this.api = new FourthwallApi(http, config.fourthwall().apiBaseUrl(), secrets.apiUsername(), secrets.apiPassword(), sleep, 200);
        StoreService.OrderFetcher fetcher = orderId -> {
            try {
                return Optional.of(Fourthwall.Order.parse(api.order(orderId)));
            } catch (FourthwallApi.ApiException | Fourthwall.BadPayload e) {
                logger.warn("Store: could not read order {} from Fourthwall: {}", orderId, e.getMessage());
                return Optional.empty();
            }
        };
        this.profiles = onlineFirst(mojang);
        this.service = new StoreService(db, profiles, this.rewards::get, this.config::get, fetcher, this::warnStaff,
                Clock.systemUTC(), logger);
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    /** Starts the store, or logs why it stays off. Never throws. */
    public static Optional<StoreFeature> start(Object plugin, ProxyServer proxy, Path dataDirectory, Function<String, String> env,
                                               Messages messages, Logger logger) {
        try {
            return startOrExplain(plugin, proxy, dataDirectory, env, messages, logger);
        } catch (RuntimeException | LinkageError e) {
            logger.error("Store: could not start; the rest of the plugin carries on", e);
            return Optional.empty();
        }
    }

    private static Optional<StoreFeature> startOrExplain(Object plugin, ProxyServer proxy, Path dataDirectory,
                                                         Function<String, String> env, Messages messages, Logger logger) {
        StoreConfig config = StoreConfig.load(dataDirectory, logger);
        Predicate<String> serverExists = exactServer(proxy);
        RewardsConfig rewards;
        try {
            rewards = RewardsConfig.load(dataDirectory, serverExists, logger);
        } catch (IOException e) {
            logger.error("Store: {}; the store stays off", e.getMessage());
            return Optional.empty();
        }
        if (!config.enabled()) {
            logger.info("Store: off (enabled = false in {})", StoreConfig.FILE_NAME);
            return Optional.empty();
        }
        Secrets secrets = Secrets.read(env);
        if (!secrets.missing().isEmpty()) {
            logger.warn("Store: off, missing environment variable(s) {} (set them as egg variables on the proxy's Startup page; "
                    + "see docs/STORE.md)", String.join(", ", secrets.missing()));
            return Optional.empty();
        }
        report(rewards, logger);
        ShopDatabase.quietDriverLogs(logger);
        ShopDatabase.quietPoolLogs(logger);
        ShopDatabase db;
        try {
            db = ShopDatabase.mariadb(config.database(), secrets.dbPassword(), logger);
        } catch (SQLException e) {
            logger.error("Store: the database settings in {} are not usable ({}); the store stays off", StoreConfig.FILE_NAME,
                    e.getMessage());
            return Optional.empty();
        }
        StoreFeature feature = new StoreFeature(proxy, dataDirectory, messages, logger, config, rewards, secrets, db);
        feature.register(plugin);
        feature.startConnecting();
        logger.info("Store: starting; connecting to the database {} (the webhook opens on {}:{}{} once it is reached){}",
                config.database().where(), config.http().bind(), config.http().port(), config.http().path(),
                config.fourthwall().captureOnly() ? ", CAPTURE ONLY: events are stored and not processed" : "");
        return Optional.of(feature);
    }

    static Predicate<String> exactServer(ProxyServer proxy) {
        return name -> {
            for (RegisteredServer s : proxy.getAllServers()) {
                if (s.getServerInfo().getName().equals(name)) {
                    return true;
                }
            }
            return false;
        };
    }

    private static void report(RewardsConfig rewards, Logger logger) {
        for (String problem : rewards.problems()) {
            logger.error("Store: {}: {}", RewardsConfig.FILE_NAME, problem);
        }
        logger.info("Store: {} product reward(s) and {} membership tier(s) in {}", rewards.products().size(),
                rewards.memberships().size(), RewardsConfig.FILE_NAME);
        for (String warning : LuckPermsGroups.check(rewards)) {
            logger.warn("Store: {}", warning);
        }
    }

    private void register(Object plugin) {
        FailedTries tries = new FailedTries(config.get().security().maxFailedClaimsPer10Min(), Clock.systemUTC());
        CommandMeta store = proxy.getCommandManager().metaBuilder("store").plugin(plugin).build();
        proxy.getCommandManager().register(store, new StoreCommand(service, worker, messages, config::get, tries, db::isConnected, logger));
        StoreAdminCommand.Controls controls = new StoreAdminCommand.Controls() {
            @Override
            public Reconciler.Summary resync() throws Exception {
                return reconcile();
            }

            @Override
            public RewardsConfig reload() throws Exception {
                return reloadConfig();
            }
        };
        CommandMeta admin = proxy.getCommandManager().metaBuilder("storeadmin").plugin(plugin).build();
        proxy.getCommandManager().register(admin, new StoreAdminCommand(proxy, service, profiles, controls, worker, messages,
                db::isConnected, logger));
    }

    /** Online players are taken as they are; anyone else is looked up at Mojang. */
    private ProfileLookup onlineFirst(ProfileLookup mojang) {
        return name -> {
            String n = name == null ? "" : name.trim();
            Optional<Player> online = proxy.getPlayer(n);
            if (online.isPresent() && online.get().getUsername().equalsIgnoreCase(n)) {
                try {
                    return Optional.of(new ProfileLookup.Profile(online.get().getUniqueId(), online.get().getUsername()));
                } catch (IllegalArgumentException e) {
                    return Optional.empty();
                }
            }
            return mojang.find(n);
        };
    }

    void startConnecting() {
        connecting = worker.scheduleWithFixedDelay(this::connectTick, 0, tickSeconds, TimeUnit.SECONDS);
    }

    boolean healthCheckRunning() {
        ScheduledFuture<?> f = connecting;
        return f != null && !f.isDone();
    }

    int receiverPort() {
        WebhookReceiver r = receiver;
        return r == null ? -1 : r.port();
    }

    ShopDatabase db() {
        return db;
    }

    StoreConfig config() {
        return config.get();
    }

    /**
     * Every {@link #tickSeconds}: connects, and keeps checking afterwards, so a database that was lost is used again
     * as soon as it is back (B7). The first success opens the receiver and starts the resync.
     */
    void connectTick() {
        try {
            if (db.state() == ShopDatabase.State.INCOMPATIBLE || db.state() == ShopDatabase.State.CLOSED) {
                stopConnecting();
                return;
            }
            if (!db.connect() || started) {
                return;
            }
            started = true;
            openReceiver();
            if (!config.get().fourthwall().captureOnly()) {
                processBacklog();
            }
            StoreConfig.Reconcile r = config.get().reconcile();
            if (r.enabled()) {
                worker.scheduleWithFixedDelay(this::scheduledReconcile, 2 * 60, r.intervalHours() * 3600L, TimeUnit.SECONDS);
                logger.info("Store: resync with Fourthwall in 2 min, then every {} h (orders from the last {} days)",
                        r.intervalHours(), r.orderLookbackDays());
            }
        } catch (RuntimeException | LinkageError e) {
            logger.error("Store: start-up step failed", e);
        }
    }

    private void stopConnecting() {
        ScheduledFuture<?> f = connecting;
        if (f != null) {
            f.cancel(false);
        }
    }

    private void openReceiver() {
        StoreConfig.Http http = config.get().http();
        try {
            receiver = WebhookReceiver.start(http, secrets.webhookSecret(), this::store, this::process, httpThreads, logger);
            logger.info("Store: webhook receiver listening on {}:{}{}", http.bind(), http.port(), http.path());
        } catch (IOException | RuntimeException | LinkageError e) {
            logger.error("Store: the webhook receiver could not listen on {}:{} ({}); webhooks are lost until the proxy restarts "
                    + "(the daily resync still finds orders)", http.bind(), http.port(), e.toString());
        }
    }

    /** Called on an HTTP thread: store the event; process it unless capturing or already processed. */
    private boolean store(Fourthwall.Envelope envelope, String body) throws SQLException {
        boolean capture = config.get().fourthwall().captureOnly();
        return db.call(c -> {
            if (ShopStore.insertEvent(c, envelope.id(), envelope.type(), envelope.testMode(), body)) {
                return !capture;
            }
            Optional<ShopStore.EventRow> stored = ShopStore.event(c, envelope.id());
            return !capture && stored.isPresent() && stored.get().processedAt() == null;
        });
    }

    private void process(String eventId) {
        try {
            worker.execute(() -> handle(eventId, service.processEvent(eventId)));
        } catch (RuntimeException e) {
            logger.debug("Store: worker stopped; event {} waits for the next start", eventId);
        }
    }

    private void handle(String eventId, StoreService.Result result) {
        if (result.outcome() != StoreService.Outcome.RETRY) {
            retries.remove(eventId);
            return;
        }
        int n = retries.merge(eventId, 1, Integer::sum);
        if (n > MAX_RETRIES) {
            retries.remove(eventId);
            logger.warn("Store: event {} still cannot be processed after {} tries ({}); the next start or resync tries again",
                    eventId, MAX_RETRIES, result.reason());
            return;
        }
        logger.info("Store: event {} will be tried again in {} min ({})", eventId, RETRY_AFTER.toMinutes(), result.reason());
        worker.schedule(() -> handle(eventId, service.processEvent(eventId)), RETRY_AFTER.toSeconds(), TimeUnit.SECONDS);
    }

    private void processBacklog() {
        try {
            List<String> waiting = db.call(c -> ShopStore.unprocessedEvents(c, 500));
            if (!waiting.isEmpty()) {
                logger.info("Store: processing {} stored event(s) not processed before", waiting.size());
            }
            for (String id : waiting) {
                handle(id, service.processEvent(id));
            }
        } catch (SQLException e) {
            logger.warn("Store: could not read waiting events: {}", ShopDatabase.describe(e));
        }
    }

    private void scheduledReconcile() {
        try {
            reconcile();
        } catch (Exception e) {
            logger.warn("Store resync failed: {}", e.getMessage());
        }
    }

    /** On the worker only. */
    Reconciler.Summary reconcile() throws FourthwallApi.ApiException {
        if (config.get().fourthwall().captureOnly()) {
            // B8: capture mode stores webhooks and does nothing else, so no rows from the resync either
            logger.info("Store resync skipped: captureOnly is on");
            return new Reconciler.Summary(0, 0, 0, 0, 0, 0, List.of());
        }
        Reconciler.Summary s = new Reconciler(api, db, service, Clock.systemUTC(), logger).run(config.get().reconcile().orderLookbackDays());
        logger.info(s.line());
        for (String id : s.retry()) {
            handle(id, new StoreService.Result(StoreService.Outcome.RETRY, "found by resync"));
        }
        return s;
    }

    /** {@code /storeadmin reload}: both files; settings that need a restart are named in the log. On the worker only. */
    RewardsConfig reloadConfig() throws IOException {
        StoreConfig before = config.get();
        StoreConfig next = StoreConfig.read(dataDirectory, logger); // B9: a broken file throws, the old settings stay
        RewardsConfig r = RewardsConfig.load(dataDirectory, exactServer(proxy), logger);
        rewards.set(r);
        config.set(next);
        report(r, logger);
        if (!next.http().equals(before.http()) || !next.database().equals(before.database()) || next.enabled() != before.enabled()
                || !next.mojang().equals(before.mojang()) || !next.reconcile().equals(before.reconcile())
                || !next.security().equals(before.security()) || !next.fourthwall().apiBaseUrl().equals(before.fourthwall().apiBaseUrl())) {
            logger.warn("Store: some changed settings ([http], [database], [mojang], [reconcile], [security], enabled, "
                    + "apiBaseUrl) take effect only after a proxy restart");
        }
        logger.info("Store: reloaded; captureOnly = {}, processTestEvents = {}", next.fourthwall().captureOnly(),
                next.fourthwall().processTestEvents());
        boolean nowProcessing = before.fourthwall().captureOnly() && !next.fourthwall().captureOnly();
        boolean nowTesting = !before.fourthwall().processTestEvents() && next.fourthwall().processTestEvents();
        if (started && !next.fourthwall().captureOnly() && (nowProcessing || nowTesting)) {
            processBacklog();
        }
        return r;
    }

    private void warnStaff(String text) {
        logger.warn("Store: {}", text);
        for (Player p : proxy.getAllPlayers()) {
            if (p.hasPermission(StoreAdminCommand.PERMISSION)) {
                p.sendMessage(messages.render(StoreAdminCommand.P + "staffWarning", Placeholder.unparsed("text", text)));
            }
        }
    }

    public void stop() {
        WebhookReceiver r = receiver;
        if (r != null) {
            r.close();
        }
        worker.shutdown();
        httpThreads.shutdown();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        db.close();
    }
}
