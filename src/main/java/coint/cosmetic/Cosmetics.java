package coint.cosmetic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import coint.CointConfig;
import coint.cluster.ClusterAccess;
import coint.cluster.ClusterService;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

/** Authority-owned preferences, delivered with the access snapshot. Runtime rights are checked on every display. */
public final class Cosmetics {

    private static final Gson JSON = new Gson();
    private static final Path FILE = Paths.get("cointcore", "cosmetics.json");
    private static Map<String, CosmeticSelection> selections;
    private static boolean loadFailureLogged;

    private Cosmetics() {}

    public static void register() {
        for (String field : new String[] { "prefix", "prefix_color", "name_color", "chat_color" }) PermissionAPI
            .registerNode("cointcore.cosmetic." + field, DefaultPermissionLevel.NONE, "Cabinet cosmetic: " + field);
    }

    public static void reset() {
        selections = null;
        loadFailureLogged = false;
    }

    public static void validate(Map<String, CosmeticSelection> values) {
        if (values.size() > 10000) throw new IllegalArgumentException("Too many cosmetic selections");
        values.forEach((id, selection) -> {
            UUID.fromString(id);
            if (selection == null) throw new IllegalArgumentException("Missing selection");
            selection.validate();
        });
    }

    private static void load() {
        if (selections != null) return;
        try {
            Map<String, CosmeticSelection> loaded = Files.exists(FILE) ? JSON.fromJson(
                new String(Files.readAllBytes(FILE), StandardCharsets.UTF_8),
                new TypeToken<LinkedHashMap<String, CosmeticSelection>>() {}.getType()) : new LinkedHashMap<>();
            if (loaded == null) throw new IllegalArgumentException("Empty cosmetic file");
            validate(loaded);
            selections = loaded;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot read cosmetic preferences", e);
        }
    }

    public static Map<String, CosmeticSelection> snapshot() {
        load();
        return JSON
            .fromJson(JSON.toJson(selections), new TypeToken<LinkedHashMap<String, CosmeticSelection>>() {}.getType());
    }

    public static CosmeticSelection selection(UUID player) {
        load();
        CosmeticSelection value = selections.get(player.toString());
        return value == null ? new CosmeticSelection() : JSON.fromJson(JSON.toJson(value), CosmeticSelection.class);
    }

    public static void receive(Map<String, CosmeticSelection> values) {
        if (values == null) return;
        load();
        validate(values);
        if (JSON.toJson(selections)
            .equals(JSON.toJson(values))) return;
        save(values);
    }

    public static void set(UUID player, String field, String value) {
        if (ClusterAccess.enabled() && !ClusterService.nodeId()
            .equals(CointConfig.cluster.accessAuthorityNodeId))
            throw new IllegalArgumentException(
                "Use the access authority: " + CointConfig.cluster.accessAuthorityNodeId);
        Map<String, CosmeticSelection> next = snapshot();
        CosmeticSelection selection = next.computeIfAbsent(player.toString(), key -> new CosmeticSelection());
        if (value.equals("reset")) value = "";
        switch (field) {
            case "prefix":
                selection.prefix = value;
                break;
            case "prefix_color":
                selection.prefixColor = value;
                break;
            case "name_color":
                selection.nameColor = value;
                break;
            case "chat_color":
                selection.chatColor = value;
                break;
            default:
                throw new IllegalArgumentException("Unknown cosmetic field");
        }
        validate(next);
        save(next);
    }

    private static void save(Map<String, CosmeticSelection> values) {
        try {
            Files.createDirectories(FILE.getParent());
            Path temp = Files.createTempFile(FILE.getParent(), "cosmetics-", ".tmp");
            try {
                Files.write(
                    temp,
                    JSON.toJson(values)
                        .getBytes(StandardCharsets.UTF_8));
                Files.move(temp, FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
            selections = values;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save cosmetic preferences", e);
        }
    }

    private static boolean allowed(EntityPlayerMP player, String field) {
        return PermissionAPI.hasPermission(player, "cointcore.cosmetic." + field);
    }

    public static String formatName(EntityPlayerMP player, String base) {
        CosmeticSelection selected = selected(player);
        if (selected == null) return base;
        boolean custom = !selected.prefix.isEmpty() && allowed(player, "prefix");
        boolean recolor = !selected.prefixColor.isEmpty() && allowed(player, "prefix_color");
        String donor = donorPrefix(player);
        if (!donor.isEmpty() && base.contains(donor)) {
            if (custom) base = base.replace(donor, "");
            else if (recolor) base = base
                .replace(donor, "§" + selected.prefixColor + donor.replaceAll("(?i)§[0-9a-fk-or]", "") + "§r");
        }
        String plain = player.getGameProfile()
            .getName();
        int index = base.lastIndexOf(plain);
        if (index < 0) return base;
        String prefix = custom
            ? "§" + (!selected.prefixColor.isEmpty() && allowed(player, "prefix_color") ? selected.prefixColor : "f")
                + "["
                + selected.prefix
                + "]§r "
            : "";
        String color = !selected.nameColor.isEmpty() && allowed(player, "name_color") ? "§" + selected.nameColor : "";
        return base.substring(0, index) + prefix + color + plain + "§r" + base.substring(index + plain.length());
    }

    public static String chatColor(EntityPlayerMP player) {
        CosmeticSelection selected = selected(player);
        return selected != null && !selected.chatColor.isEmpty() && allowed(player, "chat_color")
            ? "§" + selected.chatColor
            : null;
    }

    private static String donorPrefix(EntityPlayerMP player) {
        if (serverutils.ranks.Ranks.INSTANCE == null) return "";
        serverutils.ranks.Rank best = null;
        int priority = 0;
        for (serverutils.ranks.Rank rank : serverutils.ranks.Ranks.INSTANCE.getPlayerRank(player)
            .getActualParents()) {
            int value = coint.commands.temprank.DonorQueue.priority(rank.getId());
            if (value > priority) {
                best = rank;
                priority = value;
            }
        }
        return best == null ? ""
            : best.getLocalPermission("serverutilities.chat.donor_prefix")
                .replace('&', '§');
    }

    private static CosmeticSelection selected(EntityPlayerMP player) {
        try {
            load();
            return selections.get(
                player.getUniqueID()
                    .toString());
        } catch (RuntimeException e) {
            if (!loadFailureLogged) {
                loadFailureLogged = true;
                coint.CointCore.LOG.error("[Cosmetics] Cannot load preferences; using rank formatting", e);
            }
            return null;
        }
    }
}
