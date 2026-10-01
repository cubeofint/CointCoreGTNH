package coint.worldtravel;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import betterquesting.api.utils.UuidConverter;
import coint.CointCore;
import coint.integration.serverutilities.CointSUPermissions;
import serverutils.lib.util.permission.PermissionAPI;

public final class WorldTravelManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();
    private static final Map<String, WorldTravelDestination> BY_ID = new HashMap<>();
    private static WorldTravelConfigData config = new WorldTravelConfigData();
    private static File configFile;

    private WorldTravelManager() {}

    public static synchronized void init(File configDirectory) {
        File dir = new File(configDirectory, "cointcore");
        if (!dir.exists() && !dir.mkdirs()) {
            CointCore.LOG.warn("[WorldTravel] Could not create config directory {}", dir);
        }
        configFile = new File(dir, "world_travel.json");
        reload();
    }

    public static synchronized boolean reload() {
        if (configFile == null) {
            return false;
        }

        if (!configFile.exists()) {
            config = createDefault();
            rebuildIndex();
            save();
            CointCore.LOG.info("[WorldTravel] Created {}", configFile);
            return true;
        }

        try (Reader reader = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
            WorldTravelConfigData loaded = GSON.fromJson(reader, WorldTravelConfigData.class);
            config = loaded == null ? new WorldTravelConfigData() : loaded;
            if (config.destinations == null) {
                config.destinations = new ArrayList<>();
            }
            if (config.title == null || config.title.trim()
                .isEmpty()) {
                config.title = "Путешествия";
            }
            rebuildIndex();
            CointCore.LOG.info("[WorldTravel] Loaded {} destinations", BY_ID.size());
            return true;
        } catch (Exception e) {
            CointCore.LOG.error("[WorldTravel] Failed to load {}", configFile, e);
            return false;
        }
    }

    private static void save() {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8)) {
            GSON.toJson(config, writer);
        } catch (Exception e) {
            CointCore.LOG.error("[WorldTravel] Failed to save {}", configFile, e);
        }
    }

    private static WorldTravelConfigData createDefault() {
        WorldTravelConfigData data = new WorldTravelConfigData();
        WorldTravelDestination example = new WorldTravelDestination();
        example.id = "example";
        example.name = "Пример точки";
        example.description = "Отключённый пример записи. Скопируйте и настройте его.";
        example.enabled = false;
        example.dimension = 0;
        example.x = 0.5D;
        example.y = 80.0D;
        example.z = 0.5D;
        example.requiredQuest = "";
        example.requirementText = "";
        example.protectDimension = false;
        data.destinations.add(example);
        return data;
    }

    private static void rebuildIndex() {
        BY_ID.clear();
        for (WorldTravelDestination destination : config.destinations) {
            if (destination == null || destination.id == null
                || destination.id.trim()
                    .isEmpty()) {
                continue;
            }
            String key = normalize(destination.id);
            if (BY_ID.containsKey(key)) {
                CointCore.LOG.warn("[WorldTravel] Duplicate destination id '{}', keeping first", destination.id);
                continue;
            }
            BY_ID.put(key, destination);
        }
    }

    public static String getTitle() {
        return config.title;
    }

    public static synchronized boolean createDestinationFromPlayer(EntityPlayerMP player, String id, String name) {
        String key = normalize(id);
        if (key.isEmpty() || BY_ID.containsKey(key)) {
            return false;
        }

        WorldTravelDestination destination = new WorldTravelDestination();
        destination.id = id.trim();
        destination.name = safe(name, destination.id);
        destination.description = "";
        destination.enabled = true;
        destination.order = getNextOrder();
        copyPlayerPosition(player, destination);
        destination.requiredQuest = "";
        destination.requirementText = "";
        destination.protectDimension = true;
        config.destinations.add(destination);
        rebuildIndex();
        save();
        return true;
    }

    public static synchronized boolean updateDestinationPositionFromPlayer(EntityPlayerMP player, String id) {
        WorldTravelDestination destination = BY_ID.get(normalize(id));
        if (destination == null) {
            return false;
        }
        copyPlayerPosition(player, destination);
        save();
        return true;
    }

    public static synchronized boolean removeDestination(String id) {
        String key = normalize(id);
        boolean removed = config.destinations
            .removeIf(destination -> destination != null && normalize(destination.id).equals(key));
        if (!removed) {
            return false;
        }
        rebuildIndex();
        save();
        return true;
    }

    public static synchronized boolean setDestinationQuest(String id, String quest) {
        WorldTravelDestination destination = BY_ID.get(normalize(id));
        if (destination == null) {
            return false;
        }
        destination.requiredQuest = quest == null ? "" : quest.trim();
        if (destination.requiredQuest.isEmpty()) {
            destination.requirementText = "";
        }
        save();
        return true;
    }

    public static synchronized boolean setDestinationRequirementText(String id, String text) {
        WorldTravelDestination destination = BY_ID.get(normalize(id));
        if (destination == null) {
            return false;
        }
        destination.requirementText = text == null ? "" : text.trim();
        save();
        return true;
    }

    public static synchronized boolean setDestinationEnabled(String id, boolean enabled) {
        WorldTravelDestination destination = BY_ID.get(normalize(id));
        if (destination == null) {
            return false;
        }
        destination.enabled = enabled;
        save();
        return true;
    }

    public static synchronized boolean setDestinationProtected(String id, boolean protectDimension) {
        WorldTravelDestination destination = BY_ID.get(normalize(id));
        if (destination == null) {
            return false;
        }
        destination.protectDimension = protectDimension;
        save();
        return true;
    }

    public static synchronized List<String> getDestinationIds() {
        List<WorldTravelDestination> destinations = new ArrayList<>(BY_ID.values());
        destinations.sort(
            Comparator.comparingInt((WorldTravelDestination d) -> d.order)
                .thenComparing(d -> d.id));
        List<String> ids = new ArrayList<>();
        for (WorldTravelDestination destination : destinations) {
            ids.add(destination.id);
        }
        return ids;
    }

    public static synchronized String getDestinationInfo(String id) {
        WorldTravelDestination destination = BY_ID.get(normalize(id));
        if (destination == null) {
            return null;
        }
        return destination.id + " | "
            + safe(destination.name, destination.id)
            + " | DIM "
            + destination.dimension
            + " | "
            + formatCoordinate(destination.x)
            + " "
            + formatCoordinate(destination.y)
            + " "
            + formatCoordinate(destination.z)
            + " | enabled="
            + destination.enabled
            + " | protect="
            + destination.protectDimension
            + " | quest="
            + (destination.requiredQuest == null || destination.requiredQuest.trim()
                .isEmpty() ? "none" : destination.requiredQuest.trim());
    }

    public static List<WorldTravelViewEntry> getViewEntries(EntityPlayerMP player) {
        List<WorldTravelDestination> destinations = new ArrayList<>();
        for (WorldTravelDestination destination : BY_ID.values()) {
            if (destination.enabled) {
                destinations.add(destination);
            }
        }
        destinations.sort(
            Comparator.comparingInt((WorldTravelDestination d) -> d.order)
                .thenComparing(d -> d.name));

        List<WorldTravelViewEntry> result = new ArrayList<>();
        for (WorldTravelDestination destination : destinations) {
            result.add(
                new WorldTravelViewEntry(
                    destination.id,
                    safe(destination.name, destination.id),
                    safe(destination.description, ""),
                    buildRequirementText(destination),
                    destination.dimension,
                    hasAccess(player, destination)));
        }
        return result;
    }

    public static boolean teleport(EntityPlayerMP player, String id) {
        WorldTravelDestination destination = BY_ID.get(normalize(id));
        if (destination == null || !destination.enabled) {
            player.addChatMessage(new ChatComponentText("§cТочка путешествия не найдена."));
            return false;
        }
        if (!hasAccess(player, destination)) {
            String requirement = buildRequirementText(destination);
            player.addChatMessage(
                new ChatComponentText(
                    requirement.isEmpty() ? "§cЭто направление пока недоступно." : "§cНедоступно: §f" + requirement));
            return false;
        }

        MinecraftServer server = MinecraftServer.getServer();
        WorldServer target = server.worldServerForDimension(destination.dimension);
        if (target == null) {
            try {
                DimensionManager.initDimension(destination.dimension);
                target = server.worldServerForDimension(destination.dimension);
            } catch (Throwable t) {
                CointCore.LOG.error("[WorldTravel] Failed to load dimension {}", destination.dimension, t);
            }
        }
        if (target == null) {
            player.addChatMessage(new ChatComponentText("§cМир сейчас недоступен."));
            return false;
        }

        if (player.dimension == destination.dimension) {
            player.playerNetServerHandler
                .setPlayerLocation(destination.x, destination.y, destination.z, destination.yaw, destination.pitch);
        } else {
            server.getConfigurationManager()
                .transferPlayerToDimension(player, destination.dimension, new FixedTeleporter(target, destination));
            player.playerNetServerHandler
                .setPlayerLocation(destination.x, destination.y, destination.z, destination.yaw, destination.pitch);
        }
        return true;
    }

    public static boolean canEnterDimension(EntityPlayerMP player, int dimension) {
        boolean protectedDimension = false;
        for (WorldTravelDestination destination : BY_ID.values()) {
            if (!destination.enabled || !destination.protectDimension || destination.dimension != dimension) {
                continue;
            }
            protectedDimension = true;
            if (hasAccess(player, destination)) {
                return true;
            }
        }
        return !protectedDimension || hasBypass(player);
    }

    public static String getDimensionRequirement(int dimension) {
        for (WorldTravelDestination destination : BY_ID.values()) {
            if (destination.enabled && destination.protectDimension && destination.dimension == dimension) {
                return buildRequirementText(destination);
            }
        }
        return "";
    }

    public static void returnToDimensionSpawn(EntityPlayerMP player, int dimension) {
        MinecraftServer server = MinecraftServer.getServer();
        WorldServer target = server.worldServerForDimension(dimension);
        if (target == null) {
            try {
                DimensionManager.initDimension(dimension);
                target = server.worldServerForDimension(dimension);
            } catch (Throwable ignored) {}
        }
        if (target == null) {
            target = server.worldServerForDimension(0);
            dimension = 0;
        }
        if (target == null) {
            return;
        }

        ChunkCoordinates spawn = target.getSpawnPoint();
        WorldTravelDestination temp = new WorldTravelDestination();
        temp.dimension = dimension;
        temp.x = spawn.posX + 0.5D;
        temp.y = spawn.posY + 1.0D;
        temp.z = spawn.posZ + 0.5D;

        if (player.dimension == dimension) {
            player.playerNetServerHandler.setPlayerLocation(temp.x, temp.y, temp.z, 0.0F, 0.0F);
        } else {
            server.getConfigurationManager()
                .transferPlayerToDimension(player, dimension, new FixedTeleporter(target, temp));
            player.playerNetServerHandler.setPlayerLocation(temp.x, temp.y, temp.z, 0.0F, 0.0F);
        }
    }

    private static boolean hasAccess(EntityPlayerMP player, WorldTravelDestination destination) {
        if (hasBypass(player)) {
            return true;
        }
        if (destination.requiredQuest == null || destination.requiredQuest.trim()
            .isEmpty()) {
            return true;
        }

        UUID quest = decodeQuestId(destination.requiredQuest);
        if (quest == null) {
            CointCore.LOG.error(
                "[WorldTravel] Destination '{}' has invalid requiredQuest '{}'",
                destination.id,
                destination.requiredQuest);
            return false;
        }
        return BetterQuestingAccess.isQuestComplete(player, quest);
    }

    public static boolean isValidQuestId(String value) {
        return decodeQuestId(value) != null;
    }

    private static UUID decodeQuestId(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {}
        try {
            return UuidConverter.decodeUuid(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static int getNextOrder() {
        int max = 0;
        for (WorldTravelDestination destination : config.destinations) {
            if (destination != null && destination.order > max) {
                max = destination.order;
            }
        }
        return max + 10;
    }

    private static void copyPlayerPosition(EntityPlayerMP player, WorldTravelDestination destination) {
        destination.dimension = player.dimension;
        destination.x = player.posX;
        destination.y = player.posY;
        destination.z = player.posZ;
        destination.yaw = player.rotationYaw;
        destination.pitch = player.rotationPitch;
    }

    private static String formatCoordinate(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static boolean hasBypass(EntityPlayerMP player) {
        return PermissionAPI.hasPermission(player, CointSUPermissions.WORLD_TRAVEL_BYPASS);
    }

    private static String buildRequirementText(WorldTravelDestination destination) {
        if (destination.requiredQuest == null || destination.requiredQuest.trim()
            .isEmpty()) {
            return "";
        }
        if (destination.requirementText != null && !destination.requirementText.trim()
            .isEmpty()) {
            return destination.requirementText.trim();
        }
        return "Требуется квест " + destination.requiredQuest.trim();
    }

    private static String normalize(String id) {
        return id == null ? ""
            : id.trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.trim()
            .isEmpty() ? fallback : value;
    }
}
