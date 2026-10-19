package coint.cluster;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.CointCore;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

/**
 * Which server is each player's home, and who plays here as a guest. A guest is a player whose home is another server
 * (or who has none, on a server that doesn't give homes): {@link GuestGuard} keeps him from taking or changing
 * anything.
 *
 * <p>
 * The database says where home is. A copy is kept in {@code cointcore/cluster_homes.json}, so a known player is placed
 * right at login and while the database is away.
 */
@EventBusSubscriber
public final class ClusterHomes {

    /** Staff with it (OP by default, or a ServerUtilities rank) are never treated as guests. */
    public static final String BYPASS = "cointcore.cluster.guest_bypass";

    private static final File CACHE = new File("cointcore", "cluster_homes.json");
    private static final Gson GSON = new Gson();

    /** Home server by player uuid, as last known. */
    private static final Map<String, String> homes = new ConcurrentHashMap<>();
    /** Players online here as guests. */
    private static final Set<UUID> guests = ConcurrentHashMap.newKeySet();
    private static boolean loaded;

    private ClusterHomes() {}

    /** Registers the bypass permission; called once while the server starts. */
    public static void registerPermissions() {
        PermissionAPI.registerNode(
            BYPASS,
            DefaultPermissionLevel.OP,
            "Not held back as a guest on other servers of the network");
    }

    public static boolean isGuest(EntityPlayer player) {
        return player != null && !player.worldObj.isRemote && guests.contains(player.getUniqueID());
    }

    /** The home server of a player as last known, or null. */
    public static String homeOf(UUID uuid) {
        load();
        return homes.get(uuid.toString());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP player) || !ClusterService.isRunning()) {
            return;
        }
        load();
        String uuid = player.getUniqueID()
            .toString();
        // Placed at once from what is known; the database answers within moments.
        apply(player, homes.get(uuid), false);
        String name = player.getCommandSenderName();
        String assign = CointConfig.cluster.assignHomeOnFirstJoin ? ClusterService.nodeId() : null;
        ClusterService.query(db -> db.home(uuid, name, assign), home -> {
            if (home != null && !home.equals(homes.get(uuid))) {
                homes.put(uuid, home);
                save();
            }
            if (player.playerNetServerHandler != null && !player.isDead) {
                apply(player, home, true);
            }
        }, null);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        guests.remove(event.player.getUniqueID());
    }

    /** A home changed by command: the player, if online here, is placed again. */
    static void homeChanged(String uuid, String node) {
        homes.put(uuid, node);
        save();
        for (Object o : net.minecraft.server.MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) o;
            if (player.getUniqueID()
                .toString()
                .equals(uuid)) {
                apply(player, node, true);
            }
        }
    }

    private static void apply(EntityPlayerMP player, String home, boolean tell) {
        // No home known: a guest, unless this server gives homes (it is then being given right now).
        boolean visiting = home == null ? !CointConfig.cluster.assignHomeOnFirstJoin
            : !home.equalsIgnoreCase(ClusterService.nodeId());
        // Staff are never held back, wherever their home is.
        boolean guest = visiting && !PermissionAPI.hasPermission(player, BYPASS);
        if (visiting && !guest && tell) {
            player.addChatMessage(
                new ChatComponentText("§7Вы не дома, но у вас право " + BYPASS + ": ограничения гостя не действуют."));
        }
        boolean was = guests.contains(player.getUniqueID());
        if (guest) {
            guests.add(player.getUniqueID());
        } else {
            guests.remove(player.getUniqueID());
        }
        if (!tell || was == guest) {
            if (!tell && guest) {
                tellGuest(player, home);
            }
            return;
        }
        if (guest) {
            tellGuest(player, home);
        } else {
            player.addChatMessage(new ChatComponentText("§aВы дома на сервере " + ClusterService.displayName() + "."));
        }
    }

    private static void tellGuest(EntityPlayerMP player, String home) {
        player.addChatMessage(
            new ChatComponentText(
                "§eВы в гостях на сервере " + ClusterService.displayName()
                    + ": здесь нельзя ничего брать, ломать и ставить."
                    + (home != null ? " Вернуться домой: §f/server " + home : "")));
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!CACHE.isFile()) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(CACHE.toPath(), StandardCharsets.UTF_8)) {
            Map<String, String> read = GSON.fromJson(reader, new TypeToken<Map<String, String>>() {}.getType());
            if (read != null) {
                homes.putAll(read);
            }
        } catch (IOException | RuntimeException e) {
            CointCore.LOG.warn("[Cluster] Could not read {}: {}", CACHE, e.getMessage());
        }
    }

    private static synchronized void save() {
        Map<String, String> copy = Collections.unmodifiableMap(new HashMap<>(homes));
        try {
            File parent = CACHE.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("cannot create " + parent);
            }
            File tmp = new File(CACHE.getPath() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
                GSON.toJson(copy, writer);
            }
            Files.move(
                tmp.toPath(),
                CACHE.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            CointCore.LOG.warn("[Cluster] Could not write {}: {}", CACHE, e.getMessage());
        }
    }
}
