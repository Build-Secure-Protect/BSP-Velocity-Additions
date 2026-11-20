package com.mrgregles.bsp_velocity_additions.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code bsp_shop} schema, owned by BSP-Server Shop (version 1). {@code bsp_shop_schema.sql} is an exact copy of its
 * {@code schema.sql}. This class never migrates or alters a table: it only creates the tables when they are missing
 * (the proxy started before any backend ever did) and refuses a database whose version is newer than it understands.
 */
public final class ShopSchema {

    public static final int VERSION = 1;
    public static final String RESOURCE = "/bsp_shop_schema.sql";
    /** The named lock BSP-Server Shop takes around the same setup, so the version row is written once. */
    public static final String LOCK = "bsp_shop_schema";

    private ShopSchema() {
    }

    public static final class TooNewException extends SQLException {
        TooNewException(int found) {
            super("the bsp_shop database is at schema version " + found + ", newer than this BSP-Velocity Additions "
                    + "understands (" + VERSION + "); the store stays off until the plugin is updated");
        }
    }

    /** Checks the version, creating the tables and the version row first when they are missing. */
    public static int ensure(Connection c) throws SQLException {
        Integer found = readVersionOrNull(c);
        if (found != null) {
            return check(found);
        }
        boolean locked = lock(c);
        try {
            try (Statement st = c.createStatement()) {
                for (String sql : statements(load())) {
                    st.execute(sql);
                }
            }
            found = readVersionOrNull(c);
            if (found == null) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO shop_schema (version) VALUES (?)")) {
                    ps.setInt(1, VERSION);
                    ps.executeUpdate();
                }
                return VERSION;
            }
            return check(found);
        } finally {
            if (locked) {
                unlock(c);
            }
        }
    }

    private static int check(int found) throws SQLException {
        if (found > VERSION) {
            throw new TooNewException(found);
        }
        return found;
    }

    /** {@code SELECT MAX(version) FROM shop_schema}; null when the table is missing or empty. */
    static Integer readVersionOrNull(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT MAX(version) FROM shop_schema")) {
            if (rs.next()) {
                int v = rs.getInt(1);
                return rs.wasNull() ? null : v;
            }
            return null;
        } catch (SQLException e) {
            if (isMissingTable(e)) {
                return null;
            }
            throw e;
        }
    }

    private static boolean isMissingTable(SQLException e) {
        String state = e.getSQLState();
        // MariaDB: 42S02 (1146); H2: 42S02 / 42S04 / 42102 / 42104
        return state != null && (state.startsWith("42S0") || state.startsWith("4210")) || e.getErrorCode() == 1146;
    }

    private static boolean lock(Connection c) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT GET_LOCK('" + LOCK + "', 30)")) {
            return rs.next() && rs.getInt(1) == 1;
        } catch (SQLException e) {
            return false; // no named locks (the tests' in-memory database)
        }
    }

    private static void unlock(Connection c) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT RELEASE_LOCK('" + LOCK + "')")) {
            rs.next();
        } catch (SQLException e) {
            // the lock goes with the connection anyway
        }
    }

    public static String load() throws SQLException {
        try (InputStream in = ShopSchema.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new SQLException(RESOURCE + " is missing from the plugin jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SQLException("could not read " + RESOURCE + ": " + e.getMessage(), e);
        }
    }

    /** Splits the SQL into statements: "-- ..." comments removed, split on ";". */
    public static List<String> statements(String sql) {
        StringBuilder clean = new StringBuilder();
        for (String line : sql.split("\n", -1)) {
            int comment = line.indexOf("--");
            clean.append(comment < 0 ? line : line.substring(0, comment)).append('\n');
        }
        List<String> out = new ArrayList<>();
        for (String part : clean.toString().split(";")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }
}
