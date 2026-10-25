package com.mrgregles.bsp_velocity_additions.discord;

import java.io.IOException;
import java.util.Optional;

/**
 * The Redis commands the bot needs on {@code bsp_core:discord:*} keys. Blocking; the bot calls them on its own thread,
 * never on the proxy's. {@link RespRedis} is the real one; the tests use a fake.
 */
public interface KeyValueStore {

    /** {@code GETDEL}: the value, removed in the same step so a link code works once. */
    Optional<String> getDel(String key) throws IOException;

    /** {@code SET key value}. */
    void set(String key, String value) throws IOException;

    /** {@code SET key value EX seconds}. */
    void setExpiring(String key, String value, long seconds) throws IOException;

    /** {@code DEL key}. */
    void delete(String key) throws IOException;
}
