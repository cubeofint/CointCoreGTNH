package coint.cluster;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.UserListOps;
import net.minecraft.server.management.UserListOpsEntry;

import com.mojang.authlib.GameProfile;

import coint.CointConfig;
import coint.CointCore;
import coint.commands.temprank.TempRankManager;
import coint.epochsync.EpochRegistry;
import serverutils.ranks.PlayerRank;
import serverutils.ranks.Rank;
import serverutils.ranks.Ranks;

/** SU 2.3.1 adapter. Only called on the server thread; never reloads SU to apply a snapshot. */
public final class AccessRanks {

    private AccessRanks() {}

    static Set<String> localEpochs() {
        Set<String> ids = new LinkedHashSet<>();
        EpochRegistry.INST.epochs.values()
            .forEach(epoch -> ids.add(epoch.rankName));
        return ids;
    }

    static AccessSnapshot capture(String authority, boolean syncOp) {
        Ranks ranks = Ranks.INSTANCE;
        AccessSnapshot snapshot = new AccessSnapshot();
        snapshot.authority = authority;
        snapshot.epochRanks.addAll(localEpochs());
        ranks.ranks.forEach((id, rank) -> snapshot.definitions.put(id, captureRules(rank)));
        ranks.playerRanks.forEach((id, rank) -> {
            AccessSnapshot.Rules rules = captureRules(rank);
            rules.name = rank.profile.getName();
            replaceParent(rules, withoutEpochs(rank.getLocalPermission(Rank.NODE_PARENT), snapshot.epochRanks));
            snapshot.players.put(id.toString(), rules);
        });
        if (authority.equals(ClusterService.nodeId())) {
            TempRankManager.get()
                .getAllEntries()
                .forEach(entry -> {
                    if (entry.expiresAt > 0) {
                        snapshot.parentExpiry.computeIfAbsent(entry.playerUuid.toString(), id -> new LinkedHashMap<>())
                            .put(entry.rankId, entry.expiresAt);
                    }
                });
        }
        if (syncOp) {
            snapshot.operators = new LinkedHashMap<>();
            UserListOps ops = MinecraftServer.getServer()
                .getConfigurationManager()
                .func_152603_m();
            for (String name : ops.func_152685_a()) {
                GameProfile profile = ops.func_152700_a(name);
                UserListOpsEntry entry = (UserListOpsEntry) ops.func_152683_b(profile);
                snapshot.operators.put(
                    profile.getId()
                        .toString(),
                    new AccessSnapshot.Operator(profile.getName(), entry.func_152644_a()));
            }
        }
        snapshot.validate(authority);
        return snapshot;
    }

    private static AccessSnapshot.Rules captureRules(Rank rank) {
        AccessSnapshot.Rules rules = new AccessSnapshot.Rules();
        rules.comment = rank.comment;
        rank.permissions
            .forEach((node, entry) -> rules.permissions.put(node, new AccessSnapshot.Rule(entry.value, entry.comment)));
        return rules;
    }

    static void apply(AccessSnapshot snapshot, boolean syncOp) {
        Ranks ranks = Ranks.INSTANCE;
        String before = AccessSnapshot.JSON.toJson(capture(snapshot.authority, syncOp));
        Set<String> localEpochs = localEpochs();
        Set<String> allEpochs = new LinkedHashSet<>(localEpochs);
        allEpochs.addAll(snapshot.epochRanks);
        // Keep existing objects: RanksManager retains references to dynamically created epoch ranks.
        ranks.ranks.keySet()
            .removeIf(id -> !snapshot.definitions.containsKey(id) && !localEpochs.contains(id));
        snapshot.definitions.forEach((id, rules) -> {
            if (!localEpochs.contains(id)) {
                Rank rank = ranks.ranks.get(id);
                if (rank == null) {
                    rank = new Rank(ranks, id);
                    rank.add();
                }
                applyRules(rank, rules);
            }
        });
        // Remove stale global rules for players absent from authority, retaining locally owned epoch progress.
        for (Map.Entry<UUID, PlayerRank> entry : new ArrayList<>(ranks.playerRanks.entrySet())) {
            if (!snapshot.players.containsKey(
                entry.getKey()
                    .toString())) {
                applyPlayer(entry.getValue(), new AccessSnapshot.Rules(), allEpochs);
            }
        }
        snapshot.players.forEach((id, rules) -> {
            PlayerRank player = ranks.getPlayerRank(new GameProfile(UUID.fromString(id), rules.name));
            AccessSnapshot.Rules effective = AccessSnapshot.JSON
                .fromJson(AccessSnapshot.JSON.toJson(rules), AccessSnapshot.Rules.class);
            Map<String, Long> expiry = snapshot.parentExpiry.get(id);
            if (expiry != null) {
                Set<String> expired = new LinkedHashSet<>();
                expiry.forEach((parent, deadline) -> {
                    if (deadline <= System.currentTimeMillis()) {
                        expired.add(parent);
                    }
                });
                AccessSnapshot.Rule parents = effective.permissions.get(Rank.NODE_PARENT);
                if (parents != null) {
                    replaceParent(effective, withoutEpochs(parents.value, expired));
                }
            }
            applyPlayer(player, effective, allEpochs);
        });
        if (syncOp && snapshot.operators != null) {
            UserListOps ops = MinecraftServer.getServer()
                .getConfigurationManager()
                .func_152603_m();
            for (String name : ops.func_152685_a()) {
                GameProfile profile = ops.func_152700_a(name);
                if (!snapshot.operators.containsKey(
                    profile.getId()
                        .toString())) {
                    ops.func_152684_c(profile);
                }
            }
            snapshot.operators.forEach((id, op) -> {
                GameProfile profile = new GameProfile(UUID.fromString(id), op.name);
                UserListOpsEntry old = (UserListOpsEntry) ops.func_152683_b(profile);
                if (old == null || old.func_152644_a() != op.level) {
                    ops.func_152687_a(new UserListOpsEntry(profile, op.level));
                }
            });
        }
        ranks.clearCache();
        if (!before.equals(AccessSnapshot.JSON.toJson(capture(snapshot.authority, syncOp)))) {
            ranks.save();
        }
    }

    private static void applyPlayer(PlayerRank player, AccessSnapshot.Rules rules, Set<String> epochs) {
        Set<String> parents = new LinkedHashSet<>();
        for (String parent : player.getLocalPermission(Rank.NODE_PARENT)
            .split(",")) {
            if (epochs.contains(parent.trim())) {
                parents.add(parent.trim());
            }
        }
        AccessSnapshot.Rule remoteParents = rules.permissions.get(Rank.NODE_PARENT);
        if (remoteParents != null) {
            for (String parent : withoutEpochs(remoteParents.value, epochs).split(",")) {
                if (!parent.trim()
                    .isEmpty()) {
                    parents.add(parent.trim());
                }
            }
        }
        applyRules(player, rules);
        player.setPermission(Rank.NODE_PARENT, String.join(", ", parents));
        player.clearCache();
    }

    private static void applyRules(Rank rank, AccessSnapshot.Rules rules) {
        rank.permissions.clear();
        rank.comment = rules.comment;
        rules.permissions.forEach((node, rule) -> {
            Rank.Entry entry = rank.setPermission(node, rule.value);
            if (entry != null) {
                entry.comment = rule.comment;
            }
        });
        rank.clearCache();
    }

    private static String withoutEpochs(String value, Set<String> epochs) {
        Set<String> parents = new LinkedHashSet<>();
        for (String parent : value.split(",")) {
            String id = parent.trim();
            if (!id.isEmpty() && !epochs.contains(id)) {
                parents.add(id);
            }
        }
        return String.join(", ", parents);
    }

    private static void replaceParent(AccessSnapshot.Rules rules, String parents) {
        AccessSnapshot.Rule old = rules.permissions.get(Rank.NODE_PARENT);
        if (parents.isEmpty()) {
            rules.permissions.remove(Rank.NODE_PARENT);
        } else {
            rules.permissions.put(Rank.NODE_PARENT, new AccessSnapshot.Rule(parents, old == null ? "" : old.comment));
        }
    }

    /**
     * SU 2.3.1 drops players.txt entries whose UUID is not in the local Universe, sometimes saving during reload.
     * Read before reload and restore missing UUIDs afterwards, including every local permission rule.
     */
    public static Recovery beforeReload(Ranks ranks) {
        Recovery recovery = new Recovery();
        if (CointConfig.cluster.accessAuthorityNodeId.trim()
            .isEmpty()) {
            return recovery;
        }
        File file = ranks.universe.server.getFile("serverutilities/server/players.txt");
        if (!file.isFile()) {
            return recovery;
        }
        try {
            AccessSnapshot.Rules current = null;
            String comment = "";
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String value = line.trim();
                if (value.isEmpty()) {
                    comment = "";
                } else if (value.startsWith("//")) {
                    comment = value.substring(2)
                        .trim();
                } else if (value.startsWith("[") && value.endsWith("]")) {
                    current = null;
                    String header = value.substring(1, value.length() - 1);
                    String[] tags = header.split(" is ", 2);
                    String[] parent = tags[0].split(" extends ", 2);
                    String id = parent[0].replaceAll("\\s", "");
                    if (id.length() == 32) {
                        id = id.substring(0, 8) + "-"
                            + id.substring(8, 12)
                            + "-"
                            + id.substring(12, 16)
                            + "-"
                            + id.substring(16, 20)
                            + "-"
                            + id.substring(20);
                    }
                    try {
                        UUID uuid = UUID.fromString(id);
                        current = new AccessSnapshot.Rules();
                        current.name = current.comment = comment;
                        recovery.players.put(uuid, current);
                        if (parent.length == 2) {
                            current.permissions.put(Rank.NODE_PARENT, new AccessSnapshot.Rule(parent[1].trim(), ""));
                        }
                        if (tags.length == 2) {
                            for (String tag : tags[1].split(",")) {
                                if (!tag.trim()
                                    .isEmpty()) {
                                    current.permissions.put(tag.trim(), new AccessSnapshot.Rule("true", ""));
                                }
                            }
                        }
                    } catch (IllegalArgumentException ignored) {
                        // A definition section, not a UUID assignment.
                    }
                    comment = "";
                } else if (current != null) {
                    int split = value.indexOf(':');
                    if (split > 0 && !value.substring(split + 1)
                        .trim()
                        .isEmpty()) {
                        current.permissions.put(
                            value.substring(0, split)
                                .trim(),
                            new AccessSnapshot.Rule(
                                value.substring(split + 1)
                                    .trim(),
                                comment));
                    }
                    comment = "";
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot preserve unloaded SU player assignments", e);
        }
        return recovery;
    }

    public static final class Recovery {

        private final Map<UUID, AccessSnapshot.Rules> players = new LinkedHashMap<>();

        public void restore(Ranks ranks) {
            if (!Ranks.isActive()) {
                return;
            }
            boolean changed = false;
            for (Map.Entry<UUID, AccessSnapshot.Rules> entry : players.entrySet()) {
                if (!ranks.playerRanks.containsKey(entry.getKey())) {
                    AccessSnapshot.Rules rules = entry.getValue();
                    applyRules(ranks.getPlayerRank(new GameProfile(entry.getKey(), rules.name)), rules);
                    changed = true;
                }
            }
            if (changed) {
                ranks.clearCache();
                ranks.save();
                CointCore.LOG.info("[ClusterAccess] Preserved unloaded player rules after SU reload");
            }
        }
    }
}
