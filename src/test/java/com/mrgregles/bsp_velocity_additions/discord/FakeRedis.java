package com.mrgregles.bsp_velocity_additions.discord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Redis in a map: values, their expiry in seconds, every command in order, and a switch that makes it unreachable. */
final class FakeRedis implements KeyValueStore {

    final Map<String, String> values = new HashMap<>();
    final Map<String, Long> expirySeconds = new HashMap<>();
    final List<String> commands = new ArrayList<>();
    boolean down;

    @Override
    public Optional<String> getDel(String key) throws IOException {
        check("GETDEL " + key);
        expirySeconds.remove(key);
        return Optional.ofNullable(values.remove(key));
    }

    @Override
    public void set(String key, String value) throws IOException {
        check("SET " + key);
        values.put(key, value);
        expirySeconds.remove(key);
    }

    @Override
    public void setExpiring(String key, String value, long seconds) throws IOException {
        check("SET " + key + " EX " + seconds);
        values.put(key, value);
        expirySeconds.put(key, seconds);
    }

    @Override
    public void delete(String key) throws IOException {
        check("DEL " + key);
        values.remove(key);
        expirySeconds.remove(key);
    }

    private void check(String command) throws IOException {
        if (down) {
            throw new IOException("Connection refused");
        }
        commands.add(command);
    }
}
