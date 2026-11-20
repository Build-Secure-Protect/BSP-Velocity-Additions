package com.mrgregles.bsp_velocity_additions.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.mariadb.jdbc.MariaDbDataSource;
import org.slf4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * The connection to the shared {@code bsp_shop} database. {@link #connect} first opens one plain connection to check
 * the schema; only then is the HikariCP pool built, so a database that is down or refuses the login gives one clear
 * line rather than a pool's stack traces. Never used on Velocity's event threads.
 */
public final class ShopDatabase implements AutoCloseable {

    public enum State { WAITING, CONNECTED, DOWN, INCOMPATIBLE, CLOSED }

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int SOCKET_TIMEOUT_MS = 30_000;

    private final DataSource raw;
    private final String where;
    private final String user;
    private final int poolSize;
    private final Logger logger;
    private volatile HikariDataSource pool;
    private volatile DataSource direct;
    private volatile State state = State.WAITING;
    private volatile String problem = "";
    private String logged = "";

    private ShopDatabase(DataSource raw, String where, String user, int poolSize, Logger logger) {
        this.raw = raw;
        this.where = where;
        this.user = user;
        this.poolSize = poolSize;
        this.logger = logger;
    }

    /**
     * The MariaDB driver writes a WARN line of its own for every refused login, beside this plugin's single line. It
     * picks its logger in {@code Loggers.init()}, which reads {@code mariadb.logging.disable}: the property is set only
     * while our relocated copy of the driver makes that choice, then put back, so other plugins' drivers are untouched.
     */
    public static synchronized void quietDriverLogs(Logger logger) {
        String key = "mariadb.logging.disable";
        String before = System.getProperty(key);
        try {
            System.setProperty(key, "true");
            org.mariadb.jdbc.util.log.Loggers.init();
        } catch (LinkageError | RuntimeException e) {
            logger.debug("Store: could not quieten the database driver's own log: {}", e.toString());
        } finally {
            if (before == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, before);
            }
        }
    }

    /**
     * B17: HikariCP logs its own WARN with a full stack trace for every connection it cannot make, which in an outage
     * floods the console beside this plugin's one line. Velocity logs through log4j, so the level of our relocated
     * Hikari package (only ours: LuckPerms has its own copy) is set to ERROR. Its real errors still show. Reflection,
     * because log4j-core belongs to the proxy, not the plugin.
     */
    public static void quietPoolLogs(Logger logger) {
        String hikari = HikariDataSource.class.getPackageName(); // the relocated name inside the jar
        try {
            Class<?> level = Class.forName("org.apache.logging.log4j.Level");
            Object error = level.getField("ERROR").get(null);
            Class.forName("org.apache.logging.log4j.core.config.Configurator")
                    .getMethod("setLevel", String.class, level).invoke(null, hikari, error);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            logger.debug("Store: could not quieten the connection pool's own log: {}", e.toString());
        }
    }

    public static ShopDatabase mariadb(StoreConfig.Database config, String password, Logger logger) throws SQLException {
        MariaDbDataSource source = new MariaDbDataSource();
        source.setUrl("jdbc:mariadb://" + config.host() + ":" + config.port() + "/" + config.name()
                + "?connectTimeout=" + CONNECT_TIMEOUT_MS + "&socketTimeout=" + SOCKET_TIMEOUT_MS);
        source.setUser(config.user());
        source.setPassword(password);
        return new ShopDatabase(source, config.where(), config.user(), config.poolSize(), logger);
    }

    /** Any data source, used as it is with no pool (the tests' in-memory databases). */
    public static ShopDatabase direct(DataSource source, Logger logger) {
        ShopDatabase db = new ShopDatabase(source, "test", "test", 1, logger);
        db.direct = source;
        return db;
    }

    public State state() {
        return state;
    }

    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    public String problem() {
        return problem;
    }

    public String where() {
        return where;
    }

    /** Connects and checks the schema. Logs one line per distinct problem. True when the database can be used. */
    public synchronized boolean connect() {
        if (state == State.CONNECTED) {
            return true;
        }
        if (state == State.INCOMPATIBLE || state == State.CLOSED) {
            return false;
        }
        int version;
        try (Connection c = raw.getConnection()) {
            version = ShopSchema.ensure(c);
        } catch (ShopSchema.TooNewException e) {
            state = State.INCOMPATIBLE;
            problem = e.getMessage();
            logger.error("Store: {}", e.getMessage());
            return false;
        } catch (SQLException | RuntimeException e) {
            failed(describe(e));
            return false;
        }
        if (direct == null && pool == null) {
            HikariConfig hc = new HikariConfig();
            hc.setDataSource(raw);
            hc.setPoolName("BSP Store");
            hc.setMaximumPoolSize(poolSize);
            hc.setMinimumIdle(1);
            hc.setConnectionTimeout(1_500); // a webhook is answered within Fourthwall's 2 s even when no connection comes
            hc.setValidationTimeout(3_000);
            hc.setInitializationFailTimeout(-1); // the plain connection above already proved the login
            hc.setKeepaliveTime(120_000);
            hc.setMaxLifetime(1_800_000);
            pool = new HikariDataSource(hc);
        }
        state = State.CONNECTED;
        problem = "";
        logged = "";
        logger.info("Store: connected to the database {} as {} (schema version {})", where, user, version);
        return true;
    }

    private void failed(String why) {
        state = State.DOWN;
        problem = why;
        if (!why.equals(logged)) {
            logged = why;
            logger.warn("Store: cannot use the database {} as {}: {}. Retrying every 30 s; the rest of the proxy carries on.",
                    where, user, why);
        }
    }

    /** The driver's message without its stack; a refused login is named as such (the password is never shown). */
    static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = String.valueOf(e.getMessage());
        if (msg.contains("Access denied")) {
            return "the login was refused (check BSP_SHOP_DB_PASSWORD and the user's allowed hosts)";
        }
        if (root instanceof java.net.ConnectException || root instanceof java.net.SocketTimeoutException) {
            return "no answer from the server (" + root.getClass().getSimpleName() + ")";
        }
        return msg.length() > 300 ? msg.substring(0, 300) + "..." : msg;
    }

    private Connection connection() throws SQLException {
        DataSource d = direct;
        if (d != null) {
            if (state != State.CONNECTED) {
                throw new SQLException("not connected to the database", "08003");
            }
            return d.getConnection();
        }
        HikariDataSource p = pool;
        if (p == null || state == State.INCOMPATIBLE || state == State.CLOSED) {
            throw new SQLException("not connected to the database" + (problem.isEmpty() ? "" : ": " + problem), "08003");
        }
        return p.getConnection();
    }

    /** Runs {@code work} with auto-commit on. */
    public <T> T call(Work<T> work) throws SQLException {
        try (Connection c = connection()) {
            return work.run(c);
        } catch (SQLException e) {
            noteLoss(e);
            throw e;
        }
    }

    /** Runs {@code work} in one transaction: committed when it returns, rolled back when it throws. */
    public <T> T transaction(Work<T> work) throws SQLException {
        try (Connection c = connection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // the connection is going anyway
                }
                throw e;
            } finally {
                try {
                    c.setAutoCommit(auto);
                } catch (SQLException ignored) {
                    // a broken connection is dropped by the pool
                }
            }
        } catch (SQLException e) {
            noteLoss(e);
            throw e;
        }
    }

    private void noteLoss(SQLException e) {
        String s = e.getSQLState();
        if (s != null && s.startsWith("08") && state == State.CONNECTED) {
            failed(describe(e));
            state = State.WAITING; // the pool reconnects by itself; connect() checks it again
        }
    }

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    @Override
    public void close() {
        state = State.CLOSED;
        HikariDataSource p = pool;
        pool = null;
        if (p != null) {
            p.close();
        }
    }
}
