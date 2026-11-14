package coint.commands.temprank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Pure scheduling policy; staff and epoch ranks are deliberately outside this queue. */
public final class DonorQueue {

    private DonorQueue() {}

    public static int priority(String rank) {
        switch (rank) {
            case "uranium":
                return 1;
            case "plutonium":
                return 2;
            case "quantum":
                return 3;
            case "fusion":
                return 4;
            case "singularity":
                return 5;
            default:
                return 0;
        }
    }

    public static void settle(List<TempRankEntry> entries, UUID player, long now) {
        List<TempRankEntry> group = new ArrayList<>();
        for (TempRankEntry e : entries) if (e.playerUuid.equals(player) && priority(e.rankId) > 0) group.add(e);
        group.sort(
            Comparator.comparingInt((TempRankEntry e) -> priority(e.rankId))
                .reversed());
        long start = now;
        // A single running timer is the new format. Carry expiry across downtime into the next queued rank.
        long activeCount = group.stream()
            .filter(e -> e.pausedMillis == 0)
            .count();
        if (activeCount == 1) {
            TempRankEntry running = group.stream()
                .filter(e -> e.pausedMillis == 0)
                .findFirst()
                .get();
            if (running.expiresAt > 0 && running.expiresAt <= now) {
                start = running.expiresAt;
                group.remove(running);
                entries.remove(running);
            }
        }
        // Legacy simultaneous timers: preserve the remainder at conversion, without inventing elapsed time.
        for (TempRankEntry e : new ArrayList<>(group)) {
            if (e.pausedMillis == 0 && e.expiresAt > 0 && e.expiresAt <= now) {
                group.remove(e);
                entries.remove(e);
            }
        }
        while (!group.isEmpty()) {
            TempRankEntry top = group.get(0);
            if (top.pausedMillis != 0) {
                top.expiresAt = top.pausedMillis < 0 ? -1 : Math.addExact(start, top.pausedMillis);
                top.pausedMillis = 0;
            }
            if (top.expiresAt > 0 && top.expiresAt <= now) {
                start = top.expiresAt;
                group.remove(0);
                entries.remove(top);
                continue;
            }
            for (int i = 1; i < group.size(); i++) {
                TempRankEntry lower = group.get(i);
                if (lower.pausedMillis == 0) {
                    lower.pausedMillis = lower.expiresAt < 0 ? -1 : lower.expiresAt - now;
                    lower.expiresAt = 0;
                }
            }
            break;
        }
    }
}
