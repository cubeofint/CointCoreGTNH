package coint.events;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S3FPacketCustomPayload;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.netty.buffer.Unpooled;
import serverutils.ServerUtilitiesPermissions;
import serverutils.lib.data.Universe;
import serverutils.ranks.Rank;
import serverutils.ranks.Ranks;

@EventBusSubscriber
public final class TabRankSorter {

    private static final String CHANNEL = "SU|TabUD";
    private static final String DONOR_PREFIX_NODE = "serverutilities.chat.donor_prefix";
    private static final int UPDATE_INTERVAL_TICKS = 20;

    private static final Set<String> STAFF_RANKS = new HashSet<>();
    private static final Set<String> DONOR_RANKS = new HashSet<>();
    private static final Set<String> PROGRESS_RANKS = new HashSet<>();

    static {
        STAFF_RANKS.add("head");
        STAFF_RANKS.add("admin");
        STAFF_RANKS.add("hmoderator");
        STAFF_RANKS.add("moderator");
        STAFF_RANKS.add("helper");
        STAFF_RANKS.add("lmd");
        STAFF_RANKS.add("media");
        STAFF_RANKS.add("owner");
        STAFF_RANKS.add("mod");

        DONOR_RANKS.add("fusion");
        DONOR_RANKS.add("quantum");
        DONOR_RANKS.add("plutonium");
        DONOR_RANKS.add("uranium");

        PROGRESS_RANKS.add("stargateowner");
        PROGRESS_RANKS.add("uxv");
        PROGRESS_RANKS.add("umv");
        PROGRESS_RANKS.add("uiv");
        PROGRESS_RANKS.add("uev");
        PROGRESS_RANKS.add("uhv");
        PROGRESS_RANKS.add("uv");
        PROGRESS_RANKS.add("zpm");
        PROGRESS_RANKS.add("luv");
        PROGRESS_RANKS.add("iv");
        PROGRESS_RANKS.add("ev");
        PROGRESS_RANKS.add("hv");
        PROGRESS_RANKS.add("mv");
        PROGRESS_RANKS.add("lv");
        PROGRESS_RANKS.add("steam");
        PROGRESS_RANKS.add("stone");
        PROGRESS_RANKS.add("bravebro");
    }

    private static int ticks;
    private static String lastState = "";
    private static Set<String> lastCustomNames = new HashSet<>();

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP player)) return;
        Snapshot snapshot = buildSnapshot();
        sendSnapshot(player, snapshot, new HashSet<>());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++ticks < UPDATE_INTERVAL_TICKS) return;
        ticks = 0;
        if (Ranks.INSTANCE == null) return;

        Snapshot snapshot = buildSnapshot();
        if (snapshot.state.equals(lastState)) return;

        Set<String> removedCustom = new HashSet<>(lastCustomNames);
        removedCustom.removeAll(snapshot.customNames);

        for (var forgePlayer : Universe.get().getOnlinePlayers()) {
            EntityPlayerMP viewer = forgePlayer.getPlayer();
            if (viewer != null) {
                sendSnapshot(viewer, snapshot, removedCustom);
            }
        }

        lastState = snapshot.state;
        lastCustomNames = new HashSet<>(snapshot.customNames);
    }

    private static Snapshot buildSnapshot() {
        List<PlayerEntry> entries = new ArrayList<>();
        if (Ranks.INSTANCE != null) {
            for (var forgePlayer : Universe.get().getOnlinePlayers()) {
                EntityPlayerMP player = forgePlayer.getPlayer();
                if (player != null) {
                    entries.add(createEntry(player));
                }
            }
        }

        entries.sort((a, b) -> {
            int compare = Integer.compare(b.category, a.category);
            if (compare != 0) return compare;

            if (a.category == 3) {
                compare = Integer.compare(b.staffPriority, a.staffPriority);
                if (compare != 0) return compare;
                compare = Integer.compare(b.progressPriority, a.progressPriority);
                if (compare != 0) return compare;
                compare = Integer.compare(b.donorPriority, a.donorPriority);
                if (compare != 0) return compare;
            } else if (a.category == 2) {
                compare = Integer.compare(b.donorPriority, a.donorPriority);
                if (compare != 0) return compare;
                compare = Integer.compare(b.progressPriority, a.progressPriority);
                if (compare != 0) return compare;
            } else {
                compare = Integer.compare(b.progressPriority, a.progressPriority);
                if (compare != 0) return compare;
                compare = Integer.compare(b.otherPriority, a.otherPriority);
                if (compare != 0) return compare;
            }

            return a.name.compareToIgnoreCase(b.name);
        });

        JsonObject sortPacket = new JsonObject();
        sortPacket.addProperty("action", "sort");
        JsonArray order = new JsonArray();
        for (PlayerEntry entry : entries) {
            order.add(new JsonPrimitive(entry.name));
        }
        sortPacket.add("order", order);

        JsonObject updatePacket = new JsonObject();
        updatePacket.addProperty("action", "update");
        JsonObject players = new JsonObject();
        Set<String> customNames = new HashSet<>();
        for (PlayerEntry entry : entries) {
            if (entry.primaryRank == null) continue;
            JsonObject playerData = new JsonObject();
            playerData.addProperty("displayName", buildDisplayName(entry));
            players.add(entry.name, playerData);
            customNames.add(entry.name);
        }
        updatePacket.add("players", players);

        String sortJson = sortPacket.toString();
        String updateJson = updatePacket.toString();
        return new Snapshot(sortJson, updateJson, customNames, sortJson + '\n' + updateJson);
    }

    private static PlayerEntry createEntry(EntityPlayerMP player) {
        String name = player.getCommandSenderName();
        int staffPriority = -1;
        int donorPriority = -1;
        int progressPriority = -1;
        int otherPriority = -1;
        Rank staffRank = null;
        Rank donorRank = null;
        Rank progressRank = null;

        try {
            for (Rank rank : Ranks.INSTANCE.getPlayerRank(player).getActualParents()) {
                String id = rank.getId();
                if (id == null) continue;

                String normalized = id.toLowerCase(Locale.ROOT);
                int priority = rank.getPriority();

                if (STAFF_RANKS.contains(normalized)) {
                    if (priority > staffPriority) {
                        staffPriority = priority;
                        staffRank = rank;
                    }
                    continue;
                }

                if (DONOR_RANKS.contains(normalized)) {
                    if (priority > donorPriority) {
                        donorPriority = priority;
                        donorRank = rank;
                    }
                    continue;
                }

                if (PROGRESS_RANKS.contains(normalized)) {
                    if (priority > progressPriority) {
                        progressPriority = priority;
                        progressRank = rank;
                    }
                    continue;
                }

                if (!rank.getLocalPermission(ServerUtilitiesPermissions.CHAT_NAME_FORMAT).isEmpty()) {
                    otherPriority = Math.max(otherPriority, priority);
                }
            }
        } catch (Exception ignored) {}

        int category;
        Rank primaryRank;
        if (staffPriority >= 0) {
            category = 3;
            primaryRank = staffRank;
        } else if (donorPriority >= 0) {
            category = 2;
            primaryRank = donorRank;
        } else {
            category = 1;
            primaryRank = null;
        }

        return new PlayerEntry(
            name,
            category,
            staffPriority,
            donorPriority,
            progressPriority,
            otherPriority,
            primaryRank,
            progressRank);
    }

    private static String buildDisplayName(PlayerEntry entry) {
        String prefix = getPrimaryPrefix(entry.primaryRank);
        String progress = getProgressPrefix(entry.progressRank);
        StringBuilder name = new StringBuilder();

        if (!prefix.isEmpty()) {
            name.append(prefix).append(' ');
        }

        if (!progress.isEmpty()) {
            name.append(progress).append(' ');
        }

        name.append("§r").append(entry.name).append("§r");

        return name.toString();
    }

    private static String getPrimaryPrefix(Rank rank) {
        if (rank == null) return "";

        String prefix = normalizeFormatting(rank.getLocalPermission(DONOR_PREFIX_NODE));
        if (!prefix.isEmpty()) return ensureReset(prefix);

        String id = rank.getId();
        if (id == null) return "";

        return switch (id.toLowerCase(Locale.ROOT)) {
            case "head" -> "§6§l[Куратор]§r";
            case "admin" -> "§4[A]§r";
            case "hmoderator" -> "§9[H. Mod]§r";
            case "moderator", "mod" -> "§9[Mod]§r";
            case "helper" -> "§b[Helper]§r";
            case "lmd" -> "§5[LMD]§r";
            case "media" -> "§a[Media]§r";
            case "owner" -> "§4§l[Owner]§r";
            case "fusion" -> "§c[§lF§r§c]§r";
            case "quantum" -> "§e[Q]§r";
            case "plutonium" -> "§b[P]§r";
            case "uranium" -> "§2[U]§r";
            default -> "§7[" + id + "]§r";
        };
    }

    private static String getProgressPrefix(Rank rank) {
        if (rank == null) return "";
        String format = normalizeFormatting(rank.getLocalPermission(ServerUtilitiesPermissions.CHAT_NAME_FORMAT));
        if (format.isEmpty()) return "";

        int nameIndex = format.indexOf("{name}");
        if (nameIndex >= 0) {
            format = format.substring(0, nameIndex);
        }

        return ensureReset(format.replace("<", "")
            .replace(">", "")
            .replaceAll(":\\s*$", "")
            .trim());
    }

    private static String normalizeFormatting(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.replace('&', '§').trim();
    }

    private static String ensureReset(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.endsWith("§r") ? value : value + "§r";
    }

    private static void sendSnapshot(EntityPlayerMP viewer, Snapshot snapshot, Set<String> removedCustom) {
        if (viewer == null || viewer.playerNetServerHandler == null) return;
        send(viewer, snapshot.sortJson);
        if (!snapshot.customNames.isEmpty()) {
            send(viewer, snapshot.updateJson);
        }
        if (!removedCustom.isEmpty()) {
            JsonObject removePacket = new JsonObject();
            removePacket.addProperty("action", "remove");
            JsonArray players = new JsonArray();
            for (String name : removedCustom) {
                players.add(new JsonPrimitive(name));
            }
            removePacket.add("players", players);
            send(viewer, removePacket.toString());
        }
    }

    private static void send(EntityPlayerMP viewer, String json) {
        if (viewer == null || viewer.playerNetServerHandler == null) return;
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        PacketBuffer buffer = new PacketBuffer(Unpooled.copiedBuffer(bytes));
        viewer.playerNetServerHandler.sendPacket(new S3FPacketCustomPayload(CHANNEL, buffer));
    }

    private static final class PlayerEntry {

        private final String name;
        private final int category;
        private final int staffPriority;
        private final int donorPriority;
        private final int progressPriority;
        private final int otherPriority;
        private final Rank primaryRank;
        private final Rank progressRank;

        private PlayerEntry(
            String name,
            int category,
            int staffPriority,
            int donorPriority,
            int progressPriority,
            int otherPriority,
            Rank primaryRank,
            Rank progressRank) {
            this.name = name;
            this.category = category;
            this.staffPriority = staffPriority;
            this.donorPriority = donorPriority;
            this.progressPriority = progressPriority;
            this.otherPriority = otherPriority;
            this.primaryRank = primaryRank;
            this.progressRank = progressRank;
        }
    }

    private static final class Snapshot {

        private final String sortJson;
        private final String updateJson;
        private final Set<String> customNames;
        private final String state;

        private Snapshot(String sortJson, String updateJson, Set<String> customNames, String state) {
            this.sortJson = sortJson;
            this.updateJson = updateJson;
            this.customNames = customNames;
            this.state = state;
        }
    }
}
