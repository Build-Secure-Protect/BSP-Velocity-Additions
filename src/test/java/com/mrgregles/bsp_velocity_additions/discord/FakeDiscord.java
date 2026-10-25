package com.mrgregles.bsp_velocity_additions.discord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Records every send. Results are scripted in order; with none left, a send succeeds. */
final class FakeDiscord implements DiscordPort {

    /** One send: to a user ({@code dm}) or a channel, the id, the message. */
    record Send(boolean dm, long to, OutgoingMessage message) {
    }

    final List<Send> sends = new ArrayList<>();
    final Deque<Result> script = new ArrayDeque<>();

    FakeDiscord then(Result... results) {
        script.addAll(List.of(results));
        return this;
    }

    @Override
    public Result sendDirect(long discordUser, OutgoingMessage message) {
        sends.add(new Send(true, discordUser, message));
        return next();
    }

    @Override
    public Result postInChannel(long channelId, OutgoingMessage message) {
        sends.add(new Send(false, channelId, message));
        return next();
    }

    private Result next() {
        Result r = script.pollFirst();
        return r == null ? new Result.Sent() : r;
    }

    List<Send> dms() {
        return sends.stream().filter(Send::dm).toList();
    }

    List<Send> posts() {
        return sends.stream().filter(s -> !s.dm()).toList();
    }
}
