package coint.cluster;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.BanEntry;
import net.minecraft.server.management.BanList;
import net.minecraft.server.management.IPBanEntry;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.server.management.UserListBans;
import net.minecraft.server.management.UserListBansEntry;

import com.mojang.authlib.GameProfile;

import coint.CointConfig;
import coint.CointCore;
import coint.player.CointPlayer;
import coint.player.Mute;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;

/**
 * Bans, IP bans and mutes shared by all servers of the network. Whatever changes the vanilla ban lists here (/ban,
 * /tban, /ban-ip, /pardon, ...) is written to the cluster database, and what the other servers wrote is put into the
 * lists here, kicking whom it bans. Mutes are kept here in memory: once the network has a word on a player's mute, that
 * word counts, else his mute on this server.
 *
 * <p>
 * Server thread, except the hooks, which RCON may call from its own thread.
 */
public final class ClusterSanctions {

    static final String BAN = "ban", IPBAN = "ipban", MUTE = "mute";
    /** How far back a look starts again before the newest change seen, for rows committed late. */
    private static final long REREAD_MS = 5000;
    private static final long PRUNE_EVERY_MS = 60 * 60_000L;
    private static final int KEEP_DAYS = 30;

    /** Changes made here, not yet in the database. */
    private static final Queue<Row> outbox = new ConcurrentLinkedQueue<>();
    /** The network's word on mutes, active or not, by player. */
    private static final Map<UUID, Row> mutes = new ConcurrentHashMap<>();
    /** The newest change applied for each row, so a row read again is not applied again. */
    private static final Map<String, Long> seen = new HashMap<>();

    private static volatile boolean applying;
    private static volatile boolean synced;
    private static boolean pollInFlight, uploaded;
    private static long cursor = -1, nextPollAt, nextPruneAt;

    private ClusterSanctions() {}

    static void reset() {
        outbox.clear();
        mutes.clear();
        seen.clear();
        synced = uploaded = pollInFlight = false;
        cursor = -1;
        nextPollAt = 0;
    }

    private static boolean enabled() {
        return ClusterService.isRunning() && CointConfig.cluster.sanctionsEnabled;
    }

    // ---- reading and applying what the network says

    static void tick(long now) {
        if (!CointConfig.cluster.sanctionsEnabled || pollInFlight || now < nextPollAt) {
            return;
        }
        nextPollAt = now + CointConfig.cluster.sanctionsPollSeconds * 1000L;
        List<Row> writes = new ArrayList<>();
        Row row;
        while ((row = outbox.poll()) != null) {
            writes.add(row);
        }
        // What was banned here before the network knew of it goes in, unless the network already has a word on it.
        List<Row> upload = uploaded ? Collections.emptyList() : localBans();
        boolean prune = now >= nextPruneAt;
        if (prune) {
            nextPruneAt = now + PRUNE_EVERY_MS;
        }
        long from = cursor < 0 ? 0 : Math.max(0, cursor - REREAD_MS);
        String node = ClusterService.nodeId();
        pollInFlight = true;
        boolean queued = ClusterService.query(db -> {
            for (Row w : writes) {
                SanctionStore.write(db, w, node);
            }
            for (Row u : upload) {
                SanctionStore.write(db, u, node);
            }
            if (prune) {
                SanctionStore.prune(db, KEEP_DAYS);
            }
            return SanctionStore.since(db, from);
        }, rows -> {
            pollInFlight = false;
            if (!upload.isEmpty()) {
                CointCore.LOG.info("[Cluster] Shared {} bans of this server with the network", upload.size());
            }
            uploaded = true;
            apply(rows);
        }, error -> {
            pollInFlight = false;
            requeue(writes);
        });
        if (!queued) {
            pollInFlight = false;
            requeue(writes);
        }
    }

    private static void requeue(List<Row> writes) {
        List<Row> later = new ArrayList<>(outbox);
        outbox.clear();
        outbox.addAll(writes);
        outbox.addAll(later);
    }

    private static void apply(List<Row> rows) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || !enabled()) {
            return;
        }
        ServerConfigurationManager scm = server.getConfigurationManager();
        long now = System.currentTimeMillis();
        for (Row row : rows) {
            cursor = Math.max(cursor, row.updatedMs);
            String key = row.kind + ":" + row.target;
            Long before = seen.put(key, row.updatedMs);
            if (before != null && before == row.updatedMs) {
                continue;
            }
            boolean active = row.isActive(now);
            try {
                switch (row.kind) {
                    case BAN -> applyBan(scm, row, active);
                    case IPBAN -> applyIpBan(scm, row, active);
                    case MUTE -> mutes.put(UUID.fromString(row.target), row);
                    default -> {}
                }
            } catch (IllegalArgumentException e) {
                CointCore.LOG.warn("[Cluster] Bad sanction row {}: {}", key, e.getMessage());
            }
        }
        if (cursor < 0) {
            cursor = 0;
        }
        synced = true;
    }

    private static void applyBan(ServerConfigurationManager scm, Row row, boolean active) {
        UserListBans bans = scm.func_152608_h();
        GameProfile profile = new GameProfile(UUID.fromString(row.target), row.name);
        // Read through the list: that drops run-out entries first.
        Object current = bans.func_152683_b(profile);
        applying = true;
        try {
            if (!active) {
                if (current != null) {
                    bans.func_152684_c(profile);
                    CointCore.LOG.info("[Cluster] {} pardoned on {}", row.name, row.nodeId);
                }
                return;
            }
            if (current instanceof BanEntry entry && same(entry, row)) {
                return;
            }
            bans.func_152687_a(
                new UserListBansEntry(profile, new Date(row.createdMs), row.issuer, row.expiresDate(), row.reason));
            CointCore.LOG.info("[Cluster] {} banned on {}: {}", row.name, row.nodeId, row.reason);
        } finally {
            applying = false;
        }
        for (EntityPlayerMP player : players(scm)) {
            if (player.getUniqueID()
                .equals(profile.getId())) {
                player.playerNetServerHandler.kickPlayerFromServer(kickMessage("Вы забанены на сервере!", row));
            }
        }
    }

    private static void applyIpBan(ServerConfigurationManager scm, Row row, boolean active) {
        BanList bans = scm.getBannedIPs();
        Object current = bans.func_152683_b(row.target);
        applying = true;
        try {
            if (!active) {
                if (current != null) {
                    bans.func_152684_c(row.target);
                    CointCore.LOG.info("[Cluster] IP {} pardoned on {}", row.target, row.nodeId);
                }
                return;
            }
            if (current instanceof BanEntry entry && same(entry, row)) {
                return;
            }
            bans.func_152687_a(
                new IPBanEntry(row.target, new Date(row.createdMs), row.issuer, row.expiresDate(), row.reason));
            CointCore.LOG.info("[Cluster] IP {} banned on {}: {}", row.target, row.nodeId, row.reason);
        } finally {
            applying = false;
        }
        for (EntityPlayerMP player : players(scm)) {
            if (row.target.equals(player.getPlayerIP())) {
                player.playerNetServerHandler
                    .kickPlayerFromServer(kickMessage("Ваш IP-адрес забанен на сервере!", row));
            }
        }
    }

    /** The ban files keep whole seconds, so ends are compared in seconds. */
    private static boolean same(BanEntry entry, Row row) {
        Date end = entry.getBanEndDate();
        Long endSeconds = end == null ? null : end.getTime() / 1000;
        Long rowSeconds = row.expiresMs == null ? null : row.expiresMs / 1000;
        return Objects.equals(entry.getBanReason(), row.reason) && Objects.equals(endSeconds, rowSeconds);
    }

    private static String kickMessage(String head, Row row) {
        String s = head + "\nПричина: " + row.reason;
        if (row.expiresMs != null) {
            s += "\nБан закончится "
                + new SimpleDateFormat("yyyy-MM-dd 'в' HH:mm:ss МСК").format(new Date(row.expiresMs));
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static List<EntityPlayerMP> players(ServerConfigurationManager scm) {
        return new ArrayList<>((List<EntityPlayerMP>) scm.playerEntityList);
    }

    private static List<Row> localBans() {
        List<Row> rows = new ArrayList<>();
        ServerConfigurationManager scm = MinecraftServer.getServer()
            .getConfigurationManager();
        long now = System.currentTimeMillis();
        for (String kind : new String[] { BAN, IPBAN }) {
            Object list = BAN.equals(kind) ? scm.func_152608_h() : scm.getBannedIPs();
            for (Object entry : ((ClusterBanList) list).cointcore$entries()) {
                Row row = rowOf(kind, entry, false, true);
                if (row != null && row.isActive(now)) {
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    // ---- what happens here, for the network

    /** A ban list here got an entry (any command or mod). Called by a mixin. */
    public static void onLocalAdded(Object list, Object entry) {
        if (applying || !enabled()) {
            return;
        }
        Row row = rowOf(kindOf(list), entry, false, false);
        if (row != null) {
            send(row);
        }
    }

    /** An entry left a ban list here. Called by a mixin. */
    public static void onLocalRemoved(Object list, Object key) {
        if (applying || !enabled()) {
            return;
        }
        String kind = kindOf(list);
        if (kind == null || key == null) {
            return;
        }
        String target, name = "";
        if (key instanceof GameProfile profile) {
            if (profile.getId() == null) {
                return;
            }
            target = profile.getId()
                .toString();
            name = profile.getName();
        } else {
            target = key.toString();
        }
        send(new Row(kind, target, name, "", "", System.currentTimeMillis(), null, true, false));
    }

    public static void publishMute(UUID id, String name, String issuer, String reason, long expiresAt) {
        if (enabled()) {
            send(
                new Row(
                    MUTE,
                    id.toString(),
                    name,
                    issuer,
                    reason,
                    System.currentTimeMillis(),
                    expiresAt,
                    false,
                    false));
        }
    }

    public static void publishUnmute(UUID id, String name) {
        if (enabled()) {
            send(new Row(MUTE, id.toString(), name, "", "", System.currentTimeMillis(), null, true, false));
        }
    }

    /** True when mutes are shared: a player not found here can still be muted by name. */
    public static boolean sharesMutes() {
        return enabled();
    }

    /** Called on login: a mute this server had before mutes were shared goes to the network. */
    static void onLogin(EntityPlayerMP player) {
        if (!enabled() || !synced || mutes.containsKey(player.getUniqueID())) {
            return;
        }
        try {
            Mute mute = CointPlayer.get(player)
                .getMute();
            if (mute != null && !mute.isExpired()) {
                outbox.add(
                    new Row(
                        MUTE,
                        player.getUniqueID()
                            .toString(),
                        player.getCommandSenderName(),
                        mute.muter,
                        mute.reason,
                        System.currentTimeMillis(),
                        mute.expiresAt,
                        false,
                        true));
            }
        } catch (RuntimeException e) {
            // No data of him here: nothing to share.
        }
    }

    private static void send(Row row) {
        if (MUTE.equals(row.kind)) {
            mutes.put(UUID.fromString(row.target), row);
        }
        outbox.add(row);
        nextPollAt = 0;
    }

    private static String kindOf(Object list) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) {
            return null;
        }
        ServerConfigurationManager scm = server.getConfigurationManager();
        if (list == scm.func_152608_h()) {
            return BAN;
        }
        if (list == scm.getBannedIPs()) {
            return IPBAN;
        }
        return null;
    }

    private static Row rowOf(String kind, Object entry, boolean removed, boolean onlyIfMissing) {
        if (kind == null || !(entry instanceof BanEntry ban) || !(entry instanceof ClusterBanEntry view)) {
            return null;
        }
        Object key = view.cointcore$key();
        String target, name = "";
        if (key instanceof GameProfile profile && profile.getId() != null) {
            target = profile.getId()
                .toString();
            name = profile.getName();
        } else if (key instanceof String ip) {
            target = ip;
        } else {
            return null;
        }
        Date created = view.cointcore$created();
        Date end = ban.getBanEndDate();
        return new Row(
            kind,
            target,
            name,
            view.cointcore$source(),
            ban.getBanReason(),
            created == null ? System.currentTimeMillis() : created.getTime(),
            end == null ? null : end.getTime(),
            removed,
            onlyIfMissing);
    }

    // ---- mutes

    /** True when the network has a word on this player's mute: then {@link #mute} counts, not the one here. */
    public static boolean knowsMute(UUID id) {
        return enabled() && synced && mutes.containsKey(id);
    }

    /** The network's mute of this player, or null when he is not muted. */
    public static Mute mute(UUID id) {
        Row row = mutes.get(id);
        if (row == null || !row.isActive(System.currentTimeMillis())) {
            return null;
        }
        Mute mute = new Mute();
        mute.muter = row.issuer;
        mute.reason = row.reason;
        mute.expiresAt = row.expiresMs == null ? Long.MAX_VALUE : row.expiresMs;
        return mute;
    }

    // ---- players by name

    /** The profile of a player by name, also of one who never played on this server. */
    public static GameProfile profileFor(String name) {
        ForgePlayer player = Universe.get()
            .getPlayer(name);
        if (player != null) {
            return player.profile;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (!server.isServerInOnlineMode()) {
            return new GameProfile(
                UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)),
                name);
        }
        return server.func_152358_ax()
            .func_152655_a(name);
    }

    /** One row of {@code cluster_sanctions}. */
    static final class Row {

        final String kind, target, name, issuer, reason;
        final long createdMs;
        final Long expiresMs;
        final boolean removed;
        /** Written only when the network has no row for it yet. */
        final boolean onlyIfMissing;
        String nodeId = "";
        long updatedMs;

        Row(String kind, String target, String name, String issuer, String reason, long createdMs, Long expiresMs,
            boolean removed, boolean onlyIfMissing) {
            this.kind = kind;
            this.target = target;
            this.name = name == null ? "" : name;
            this.issuer = issuer == null ? "" : issuer;
            this.reason = reason == null ? "" : reason;
            // The ban files keep whole seconds: rows do too, so a list read back matches its row.
            this.createdMs = createdMs / 1000 * 1000;
            this.expiresMs = expiresMs == null ? null : expiresMs / 1000 * 1000;
            this.removed = removed;
            this.onlyIfMissing = onlyIfMissing;
        }

        boolean isActive(long now) {
            return !removed && (expiresMs == null || expiresMs > now);
        }

        Date expiresDate() {
            return expiresMs == null ? null : new Date(expiresMs);
        }
    }
}
