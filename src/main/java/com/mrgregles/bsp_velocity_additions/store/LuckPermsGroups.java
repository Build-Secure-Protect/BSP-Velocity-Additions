package com.mrgregles.bsp_velocity_additions.store;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BSP-Server Shop's lesson: LuckPerms runs {@code lp} in the background, so a command naming a group that does not
 * exist never fails its row. The groups named in {@code lp user ... parent add|remove|set <group>} commands are checked
 * against LuckPerms-Velocity (same database as the backends) when the rewards load; a missing one is a warning only.
 */
public final class LuckPermsGroups {

    private static final Pattern PARENT = Pattern.compile("\\blp\\s+user\\s+\\S+\\s+parent\\s+(?:add|remove|set|addtemp)\\s+([^\\s{}]+)",
            Pattern.CASE_INSENSITIVE);

    private LuckPermsGroups() {
    }

    /** Group names the rewards' commands use. */
    static Set<String> groupsIn(RewardsConfig rewards) {
        Set<String> out = new LinkedHashSet<>();
        for (RewardsConfig.Reward r : rewards.products().values()) {
            collect(r, out);
        }
        for (RewardsConfig.Reward r : rewards.memberships().values()) {
            collect(r, out);
        }
        return out;
    }

    private static void collect(RewardsConfig.Reward r, Set<String> out) {
        List<String> all = new ArrayList<>(r.grant());
        all.addAll(r.revoke());
        for (String c : all) {
            Matcher m = PARENT.matcher(c);
            while (m.find()) {
                out.add(m.group(1).toLowerCase(Locale.ROOT));
            }
        }
    }

    /** Warnings for groups {@code exists} does not know. */
    static List<String> check(RewardsConfig rewards, Predicate<String> exists) {
        List<String> out = new ArrayList<>();
        for (String g : groupsIn(rewards)) {
            if (!exists.test(g)) {
                out.add("LuckPerms has no group \"" + g + "\" (named in " + RewardsConfig.FILE_NAME
                        + "); its commands would do nothing. Create it (lp creategroup " + g + ") or fix the name.");
            }
        }
        return out;
    }

    /** Checks against LuckPerms-Velocity when it is installed; nothing otherwise. */
    public static List<String> check(RewardsConfig rewards) {
        try {
            Class.forName("net.luckperms.api.LuckPermsProvider");
            net.luckperms.api.model.group.GroupManager groups = net.luckperms.api.LuckPermsProvider.get().getGroupManager();
            return check(rewards, g -> groups.getGroup(g) != null);
        } catch (ClassNotFoundException | LinkageError | IllegalStateException e) {
            return groupsIn(rewards).isEmpty() ? List.of()
                    : List.of("LuckPerms-Velocity is not loaded, so the groups in " + RewardsConfig.FILE_NAME + " cannot be checked");
        }
    }
}
