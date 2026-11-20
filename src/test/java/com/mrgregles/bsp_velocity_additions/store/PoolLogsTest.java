package com.mrgregles.bsp_velocity_additions.store;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B17: HikariCP's own warnings and stack traces are hidden (the plugin logs one line of its own), and nothing else's
 * logging is touched. Checked with the network's velocity.jar, whose log4j the plugin logs into on the live proxy.
 */
class PoolLogsTest {

    @Test
    void thePoolsOwnWarningsAreHiddenAndOtherLoggersAreLeftAlone() throws Exception {
        Path velocity = Path.of("server", "velocity.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(velocity), "no local copy of the proxy jar");
        List<URL> urls = new ArrayList<>();
        urls.add(velocity.toUri().toURL());
        urls.add(Path.of("build", "classes", "java", "main").toUri().toURL());
        // where this test run found the pool and the driver (Gradle's worker does not list them in java.class.path)
        urls.add(com.zaxxer.hikari.HikariDataSource.class.getProtectionDomain().getCodeSource().getLocation());
        urls.add(org.mariadb.jdbc.Driver.class.getProtectionDomain().getCodeSource().getLocation());
        try (URLClassLoader proxy = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            Class<?> factory = proxy.loadClass("org.slf4j.LoggerFactory");
            Object plugin = factory.getMethod("getLogger", String.class).invoke(null, "bsp_velocity_additions");
            Class<?> slf4j = proxy.loadClass("org.slf4j.Logger");
            Class<?> db = proxy.loadClass(ShopDatabase.class.getName());

            db.getMethod("quietPoolLogs", slf4j).invoke(null, plugin);

            // log4j keeps one context per class loader: read the one the plugin's classes (and the shaded pool) use
            Class<?> logManager = proxy.loadClass("org.apache.logging.log4j.LogManager");
            Object context = logManager.getMethod("getContext", ClassLoader.class, boolean.class).invoke(null, proxy, false);
            Class<?> level = proxy.loadClass("org.apache.logging.log4j.Level");
            Object warn = level.getField("WARN").get(null);
            Object error = level.getField("ERROR").get(null);
            java.lang.reflect.Method getLogger = context.getClass().getMethod("getLogger", String.class);
            Object pool = getLogger.invoke(context, "com.zaxxer.hikari.pool.PoolBase");
            Object other = getLogger.invoke(context, "me.lucko.luckperms.lib.hikari.pool.PoolBase");
            Object ours = getLogger.invoke(context, "bsp_velocity_additions");
            java.lang.reflect.Method enabled = proxy.loadClass("org.apache.logging.log4j.Logger").getMethod("isEnabled", level);
            assertFalse((boolean) enabled.invoke(pool, warn), "the plugin's pool warnings are hidden");
            assertTrue((boolean) enabled.invoke(pool, error), "its real errors still show");
            assertTrue((boolean) enabled.invoke(other, warn), "LuckPerms' own pool is untouched");
            assertTrue((boolean) enabled.invoke(ours, warn), "the plugin's own lines still show");
        }
    }
}
