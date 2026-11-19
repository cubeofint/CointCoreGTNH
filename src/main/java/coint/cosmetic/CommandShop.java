package coint.cosmetic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;

import coint.cluster.DonationDelivery;
import coint.commands.kit.KitManager;
import coint.commands.temprank.TempRankManager;
import serverutils.ranks.Rank;
import serverutils.ranks.Ranks;

/** Versioned RCON-only API. A purchase can be replayed after a timeout without extending it again. */
public final class CommandShop extends CommandBase {

    private static final Gson JSON = new Gson();
    private static final String[] FIELDS = { "prefix", "prefix_color", "name_color", "chat_color" };

    @Override
    public String getCommandName() {
        return "cointshop";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "cointshop <catalog|status|cosmetic|purchase|kitpurchase> <base64-json>";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return !(sender instanceof EntityPlayer);
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!canCommandSenderUseCommand(sender)) throw new CommandException("Используйте личный кабинет");
        JsonObject result;
        try {
            if (!TempRankManager.get()
                .ready()) throw new IllegalStateException("Server is still starting");
            if (args.length != 2 || args[1].length() > 16000) throw new IllegalArgumentException("Invalid API request");
            if (TempRankManager.get()
                .onServerThread()) result = execute(args);
            else {
                String[] request = args.clone();
                java.util.concurrent.CompletableFuture<JsonObject> future = new java.util.concurrent.CompletableFuture<>();
                coint.supply.SupplyServerTasks.run(() -> {
                    if (future.isCancelled()) return;
                    try {
                        future.complete(execute(request));
                    } catch (Exception error) {
                        future.completeExceptionally(error);
                    }
                });
                try {
                    result = future.get(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception error) {
                    future.cancel(false);
                    throw error;
                }
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread()
                .interrupt();
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            result = new JsonObject();
            result.addProperty("api", 1);
            result.addProperty(
                "error",
                cause.getMessage() == null ? cause.getClass()
                    .getSimpleName() : cause.getMessage());
        }
        sender.addChatMessage(new ChatComponentText("COINTSHOP " + JSON.toJson(result)));
    }

    private static JsonObject execute(String[] args) throws Exception {
        TempRankManager.requireAuthority();
        if (args.length != 2 || args[1].length() > 16000) throw new IllegalArgumentException("Invalid API request");
        byte[] bytes = Base64.getDecoder()
            .decode(args[1]);
        JsonObject request = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8))
            .getAsJsonObject();
        String name = request.get("name")
            .getAsString();
        if (!name.matches("[A-Za-z0-9_.]{1,16}")) throw new IllegalArgumentException("Invalid player name");
        UUID uuid = UUID.fromString(
            request.get("uuid")
                .getAsString());
        serverutils.lib.data.ForgePlayer known = coint.player.PlayerLookup.exact(name);
        if (known != null && !known.getId()
            .equals(uuid)) throw new IllegalArgumentException("Player identity mismatch");
        JsonObject result;
        switch (args[0]) {
            case "kitpurchase":
                if (!DonationDelivery.enabled())
                    throw new IllegalArgumentException("Cluster donation delivery is disabled");
                String kitKey = request.get("key")
                    .getAsString();
                int kitAmount = request.get("amount")
                    .getAsInt();
                String kitReceipt = request.get("receipt")
                    .getAsString();
                if (!kitKey.matches("[a-z0-9_-]{1,64}") || kitAmount < 1
                    || kitAmount > 100
                    || KitManager.getKit(MinecraftServer.getServer(), kitKey) == null)
                    throw new IllegalArgumentException("Invalid standalone kit purchase");
                TempRankManager.get()
                    .kitReceipt(kitReceipt, fingerprint(bytes));
                DonationDelivery.add(name, kitKey, kitAmount, kitReceipt);
                result = new JsonObject();
                result.addProperty("receipt", kitReceipt);
                result.addProperty("accepted", true);
                break;
            case "catalog":
                result = new JsonObject();
                result.add("kits", JSON.toJsonTree(KitManager.getKitNames(MinecraftServer.getServer())));
                java.util.List<String> availableRanks = new java.util.ArrayList<>();
                for (String donor : new String[] { "thorium", "uranium", "plutonium", "quantum", "fusion",
                    "singularity" })
                    if (Ranks.INSTANCE != null && Ranks.INSTANCE.getRank(donor) != null) availableRanks.add(donor);
                result.add("ranks", JSON.toJsonTree(availableRanks));
                result.addProperty("deliveryEnabled", DonationDelivery.enabled());
                break;
            case "status":
                result = status(uuid, name);
                break;
            case "cosmetic":
                TempRankManager.get()
                    .checkExpired();
                String field = request.get("field")
                    .getAsString();
                if (!allowed(new GameProfile(uuid, name), field))
                    throw new IllegalArgumentException("Нет права на это оформление");
                Cosmetics.set(
                    uuid,
                    field,
                    request.get("value")
                        .getAsString());
                result = status(uuid, name);
                break;
            case "purchase":
                if (!DonationDelivery.enabled())
                    throw new IllegalArgumentException("Cluster donation delivery is disabled");
                String receipt = request.get("receipt")
                    .getAsString();
                int months = request.get("months")
                    .getAsInt();
                if (months < 1 || months > 36
                    || request.getAsJsonArray("kits")
                        .size() > 64)
                    throw new IllegalArgumentException("Invalid purchase limits");
                java.util.Map<String, Integer> keys = new java.util.LinkedHashMap<>();
                for (JsonElement kit : request.getAsJsonArray("kits")) {
                    String key = kit.getAsJsonObject()
                        .get("key")
                        .getAsString();
                    int amount = kit.getAsJsonObject()
                        .get("amount")
                        .getAsInt();
                    if (!key.matches("[a-z0-9_-]{1,64}") || keys.containsKey(key)
                        || amount < 1
                        || amount > 36
                        || KitManager.getKit(MinecraftServer.getServer(), key) == null)
                        throw new IllegalArgumentException("Unknown or duplicate kit: " + key);
                    keys.put(key, amount);
                }
                TempRankManager.get()
                    .purchase(
                        receipt,
                        fingerprint(bytes),
                        name,
                        request.get("rank")
                            .getAsString(),
                        Math.multiplyExact(months, 30L * 86400000));
                for (Map.Entry<String, Integer> kit : keys.entrySet())
                    DonationDelivery.add(name, kit.getKey(), kit.getValue(), receipt);
                result = new JsonObject();
                result.addProperty("receipt", receipt);
                result.addProperty("accepted", true);
                break;
            default:
                throw new IllegalArgumentException("Unknown API action");
        }
        result.addProperty("api", 1);
        result.addProperty("uuid", uuid.toString());
        return result;
    }

    private static boolean allowed(GameProfile player, String field) {
        if (!java.util.Arrays.asList(FIELDS)
            .contains(field) || Ranks.INSTANCE == null) return false;
        Rank rank = Ranks.INSTANCE.getPlayerRank(player);
        String node = "cointcore.cosmetic." + field;
        return "true".equals(rank.getPermission(node, node, true));
    }

    private static String fingerprint(byte[] bytes) throws Exception {
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) hash.append(String.format("%02x", b & 255));
        return hash.toString();
    }

    private static JsonObject status(UUID uuid, String name) {
        JsonObject result = new JsonObject(), rights = new JsonObject();
        GameProfile player = new GameProfile(uuid, name);
        for (String field : FIELDS) rights.addProperty(field, allowed(player, field));
        result.add("allowed", rights);
        result.add("selection", JSON.toJsonTree(Cosmetics.selection(uuid)));
        com.google.gson.JsonArray ranks = new com.google.gson.JsonArray();
        for (coint.commands.temprank.TempRankEntry entry : TempRankManager.get()
            .getAllEntries()) {
            if ((!entry.playerUuid.equals(uuid) && !(entry.pendingIdentity && entry.playerName.equalsIgnoreCase(name)))
                || entry.isExpired()) continue;
            JsonObject row = new JsonObject();
            row.addProperty("rank", entry.rankId);
            row.addProperty("expiresAt", entry.expiresAt);
            row.addProperty("pausedMillis", entry.pausedMillis);
            ranks.add(row);
        }
        result.add("ranks", ranks);
        return result;
    }
}
