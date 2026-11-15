package coint.cluster;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import net.minecraft.network.NetworkManager;
import net.minecraft.util.ChatComponentText;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;
import com.mojang.authlib.GameProfile;

import coint.CointConfig;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Async admission before the player is created; pending handshakes and vanished players also hold leases. */
@EventBusSubscriber
public final class ClusterSessions {

    // Stop using a lease long before another node may reclaim it (120 seconds at the database).
    private static final long LOCAL_LEASE_NANOS = 60_000_000_000L;
    private static final long RETRY_NANOS = 500_000_000L;
    private static final long HANDOFF_WAIT_NANOS = 8_000_000_000L;
    private static final String UNAVAILABLE = "Не удалось проверить сессию в сети серверов. Повторите вход позже.";
    private static final Map<NetworkManager, Admission> connections = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Admission> releases = new java.util.HashMap<>();
    private static java.util.concurrent.ScheduledExecutorService watchdog;

    private ClusterSessions() {}

    /** Called on the server thread, once per login tick. No database work on this thread. */
    public static boolean ready(NetworkManager network, GameProfile profile) {
        if (!CointConfig.cluster.enabled) return true;
        if (!ClusterService.isRunning() || profile == null || profile.getId() == null) {
            disconnect(network, UNAVAILABLE);
            return false;
        }
        startWatchdog();
        Admission a = connections.computeIfAbsent(
            network,
            key -> new Admission(
                profile.getId()
                    .toString()));
        long now = System.nanoTime();
        if (a.owned && !a.expired && now < a.validUntil) return true;
        if (a.owned) {
            close(network, a, UNAVAILABLE);
            return false;
        }
        if (!a.inFlight && now >= a.nextAttempt) acquire(network, a);
        return false;
    }

    private static void acquire(NetworkManager network, Admission a) {
        long started = System.nanoTime();
        String node = ClusterService.nodeId();
        a.inFlight = true;
        boolean queued = ClusterService
            .query(db -> SessionStore.acquire(db.connection(), a.uuid, a.token, node), ok -> {
                a.inFlight = false;
                if (connections.get(network) != a || !network.isChannelOpen()) {
                    release(a);
                    return;
                }
                if (ok && System.nanoTime() - started < LOCAL_LEASE_NANOS) {
                    a.validUntil = started + LOCAL_LEASE_NANOS;
                    a.nextAttempt = System.nanoTime() + 10_000_000_000L;
                    a.owned = true;
                } else if (ok) {
                    close(network, a, UNAVAILABLE);
                } else if (System.nanoTime() - a.created >= HANDOFF_WAIT_NANOS) {
                    close(network, a, "Этот аккаунт уже подключён к сети серверов. Выйдите из другого клиента.");
                } else {
                    a.nextAttempt = System.nanoTime() + RETRY_NANOS;
                }
            }, error -> close(network, a, UNAVAILABLE));
        if (!queued) close(network, a, UNAVAILABLE);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        long now = System.nanoTime();
        for (Admission a : new ArrayList<>(releases.values())) {
            if (!a.releaseInFlight && now >= a.nextRelease) release(a);
        }
        for (Map.Entry<NetworkManager, Admission> e : new ArrayList<>(connections.entrySet())) {
            NetworkManager network = e.getKey();
            Admission a = e.getValue();
            if (!network.isChannelOpen()) {
                // Logout saves player data before removing the player from this list.
                if (stillInWorld(network)) continue;
                connections.remove(network);
                release(a);
            } else if (!ClusterService.isRunning() || a.expired || (a.owned && now >= a.validUntil)) {
                close(network, a, UNAVAILABLE);
            } else if (a.owned && !a.inFlight && now >= a.nextAttempt) {
                a.inFlight = true;
                long started = now;
                boolean queued = ClusterService
                    .query(db -> SessionStore.renew(db.connection(), a.uuid, a.token), ok -> {
                        a.inFlight = false;
                        if (connections.get(network) != a) return;
                        if (!ok || a.expired || System.nanoTime() - started >= LOCAL_LEASE_NANOS) {
                            close(network, a, UNAVAILABLE);
                        } else {
                            a.validUntil = started + LOCAL_LEASE_NANOS;
                            a.nextAttempt = System.nanoTime() + 10_000_000_000L;
                        }
                    }, error -> close(network, a, UNAVAILABLE));
                if (!queued) close(network, a, UNAVAILABLE);
            }
        }
    }

    private static void startWatchdog() {
        if (watchdog != null) return;
        watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "CointCore-SessionLease");
            thread.setDaemon(true);
            return thread;
        });
        watchdog.scheduleWithFixedDelay(() -> {
            for (Map.Entry<NetworkManager, Admission> e : connections.entrySet()) {
                Admission a = e.getValue();
                if (a.owned && System.nanoTime() >= a.validUntil) {
                    a.expired = true;
                    // Netty channel closure is thread-safe; never manipulate players from this worker.
                    e.getKey()
                        .closeChannel(new ChatComponentText(UNAVAILABLE));
                }
            }
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Also guards already queued packets when a stalled server thread resumes. */
    public static boolean mayProcess(NetworkManager network) {
        Admission a = connections.get(network);
        if (a == null || !a.owned) return true;
        if (!a.expired && System.nanoTime() < a.validUntil) return true;
        a.expired = true;
        network.closeChannel(new ChatComponentText(UNAVAILABLE));
        return false;
    }

    private static boolean stillInWorld(NetworkManager network) {
        net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return false;
        for (Object object : server.getConfigurationManager().playerEntityList) {
            net.minecraft.entity.player.EntityPlayerMP player = (net.minecraft.entity.player.EntityPlayerMP) object;
            if (player.playerNetServerHandler != null && player.playerNetServerHandler.netManager == network)
                return true;
        }
        return false;
    }

    private static void close(NetworkManager network, Admission a, String reason) {
        if (connections.get(network) != a) return;
        disconnect(network, reason);
        // Release on the next tick after closure, never while this connection is still playable.
    }

    private static void disconnect(NetworkManager network, String reason) {
        if (!network.isChannelOpen()) return;
        if (network.getNetHandler() instanceof net.minecraft.server.network.NetHandlerLoginServer login) {
            login.func_147322_a(reason);
        } else if (network.getNetHandler() instanceof net.minecraft.network.NetHandlerPlayServer play) {
            play.kickPlayerFromServer(reason);
        } else {
            network.closeChannel(new ChatComponentText(reason));
        }
    }

    /** ServerStopped only: worlds and player data have already been saved. */
    static void shutdown() {
        if (watchdog != null) watchdog.shutdownNow();
        watchdog = null;
        for (Admission a : connections.values()) release(a);
        for (Admission a : new ArrayList<>(releases.values())) release(a);
        connections.clear();
        releases.clear();
    }

    private static void release(Admission a) {
        releases.put(a.token, a);
        if (a.releaseInFlight) return;
        a.releaseInFlight = true;
        boolean queued = ClusterService.query(db -> {
            SessionStore.release(db.connection(), a.uuid, a.token);
            return true;
        }, ok -> releases.remove(a.token), error -> {
            a.releaseInFlight = false;
            a.nextRelease = System.nanoTime() + 1_000_000_000L;
        });
        if (!queued) a.releaseInFlight = false;
    }

    private static final class Admission {

        final String uuid, token = UUID.randomUUID()
            .toString();
        final long created = System.nanoTime();
        volatile boolean owned, expired;
        volatile long validUntil;
        boolean inFlight, releaseInFlight;
        long nextAttempt, nextRelease;

        Admission(String uuid) {
            this.uuid = uuid;
        }
    }
}
