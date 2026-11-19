package coint.commands.kit;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;

import serverutils.lib.data.ForgePlayer;
import serverutils.lib.util.NBTUtils;

public final class KitManager {

    private static final String KIT_FILE = "cointcore/kits.dat";
    private static final String TAG_KITS = "kits";
    private static final String TAG_ITEMS = "items";
    private static final String TAG_MAX_CLAIMS = "maxClaims";
    public static final String TAG_KIT_CLAIM_COUNT = "cointcore_kit_claim_count";
    /** Purchased claims, independent of the kit's free/default claim limit. */
    public static final String TAG_KIT_BONUS_REMAINING = "cointcore_kit_bonus_remaining";

    /**
     * Корень custom entity data в {@code playerdata/*.dat} — тот же уровень, что {@link EntityPlayer#getEntityData()}.
     * Persisted-моды лежат в {@code ForgeData/PlayerPersisted}, а не в корневом {@code PlayerPersisted}.
     */
    private static final String TAG_FORGE_DATA = "ForgeData";

    private static final Map<String, KitDefinition> KITS = new LinkedHashMap<>();
    private static boolean loaded = false;
    private static String portableCatalog;
    private static String receivedCatalog;
    private static boolean replicaBackedUp;

    public static synchronized void reset() {
        KITS.clear();
        loaded = replicaBackedUp = false;
        portableCatalog = receivedCatalog = null;
    }

    private KitManager() {
        // Utility class
    }

    public static synchronized void loadIfNeeded(MinecraftServer server) {
        if (loaded) {
            return;
        }

        KITS.clear();
        File file = server.getFile(KIT_FILE);
        if (!file.exists()) {
            loaded = true;
            return;
        }

        try {
            NBTTagCompound root = CompressedStreamTools.read(file);
            if (root == null) {
                throw new IOException("Empty kit file");
            }

            NBTTagCompound kitsTag = root.getCompoundTag(TAG_KITS);
            Set<String> keys = kitsTag.func_150296_c();
            for (String name : keys) {
                NBTTagCompound kitTag = kitsTag.getCompoundTag(name);
                KitDefinition kit = readKit(name, kitTag);
                KITS.put(name, kit);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read kits; refusing to publish an empty catalog", e);
        }

        loaded = true;
    }

    public static synchronized void save(MinecraftServer server) {
        portableCatalog = null;
        File file = server.getFile(KIT_FILE);
        // noinspection ResultOfMethodCallIgnored
        file.getParentFile()
            .mkdirs();

        NBTTagCompound root = new NBTTagCompound();
        NBTTagCompound kitsTag = new NBTTagCompound();
        for (Map.Entry<String, KitDefinition> entry : KITS.entrySet()) {
            kitsTag.setTag(entry.getKey(), writeKit(entry.getValue()));
        }
        root.setTag(TAG_KITS, kitsTag);

        try {
            atomicWrite(root, file);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save kits", e);
        }
    }

    public static synchronized void putKit(MinecraftServer server, KitDefinition kit) {
        loadIfNeeded(server);
        KitDefinition old = KITS.put(kit.getName(), kit);
        try {
            save(server);
        } catch (RuntimeException e) {
            if (old == null) KITS.remove(kit.getName());
            else KITS.put(kit.getName(), old);
            throw e;
        }
    }

    public static synchronized KitDefinition getKit(MinecraftServer server, String name) {
        loadIfNeeded(server);
        return KITS.get(name);
    }

    public static synchronized Collection<String> getKitNames(MinecraftServer server) {
        loadIfNeeded(server);
        return new ArrayList<>(KITS.keySet());
    }

    public static synchronized void removeKit(MinecraftServer server, String name) {
        loadIfNeeded(server);
        KitDefinition old = KITS.remove(name);
        if (old != null) {
            try {
                save(server);
            } catch (RuntimeException e) {
                KITS.put(name, old);
                throw e;
            }
        }
    }

    private static KitDefinition readKit(String name, NBTTagCompound kitTag) {
        List<ItemStack> items = new ArrayList<>();
        NBTTagList list = kitTag.getTagList(TAG_ITEMS, 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound itemTag = list.getCompoundTagAt(i);
            ItemStack stack = ItemStack.loadItemStackFromNBT(itemTag);
            if (stack == null || stack.getItem() == null || stack.stackSize <= 0)
                throw new IllegalArgumentException("Invalid kit item: " + name + " slot " + i);
            items.add(stack);
        }
        int maxClaims = kitTag.hasKey(TAG_MAX_CLAIMS) ? kitTag.getInteger(TAG_MAX_CLAIMS) : -1;
        return new KitDefinition(name, items, maxClaims, kitTag.getString("requiredRank"));
    }

    private static NBTTagCompound writeKit(KitDefinition kit) {
        NBTTagCompound kitTag = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        for (ItemStack stack : kit.getItems()) {
            if (stack == null) {
                continue;
            }
            try {
                coint.util.NbtAuditLimit.check(stack.getTagCompound());
            } catch (IOException e) {
                throw new IllegalArgumentException("Kit item NBT exceeds safety limits", e);
            }
            NBTTagCompound itemTag = new NBTTagCompound();
            stack.writeToNBT(itemTag);
            list.appendTag(itemTag);
        }
        kitTag.setTag(TAG_ITEMS, list);
        kitTag.setInteger(TAG_MAX_CLAIMS, kit.getMaxClaims());
        kitTag.setString("requiredRank", kit.getRequiredRank());
        return kitTag;
    }

    /** Paid individual purchases remain claimable regardless of a free kit's minimum privilege. */
    public static boolean rankAllows(EntityPlayer player, KitDefinition kit) {
        String required = kit.getRequiredRank();
        if (required.isEmpty()) return true;
        for (serverutils.ranks.Rank rank : serverutils.ranks.Ranks.INSTANCE.getPlayerRank(player)
            .getActualParents()) {
            if (rank.getId()
                .equals(required)) return true;
            int minimum = coint.commands.temprank.DonorQueue.priority(required);
            if (minimum > 0 && coint.commands.temprank.DonorQueue.priority(rank.getId()) >= minimum) return true;
        }
        return false;
    }

    /** Portable catalog only: player counters and purchase receipts never enter this snapshot. */
    public static synchronized String catalog(MinecraftServer server) {
        loadIfNeeded(server);
        if (portableCatalog != null) return portableCatalog;
        try {
            NBTTagCompound root = new NBTTagCompound();
            for (KitDefinition kit : KITS.values()) root.setTag(kit.getName(), writeKit(kit));
            coint.util.NbtAuditLimit.check(root);
            // ItemStack.writeToNBT attaches its live tag. Portable item IDs must never mutate kit templates.
            root = (NBTTagCompound) root.copy();
            coint.trade.TradeItems.portable(root);
            byte[] bytes = CompressedStreamTools.compress(root);
            if (bytes.length > 4 * 1024 * 1024) throw new IOException("Kit catalog exceeds 4 MiB compressed");
            portableCatalog = java.util.Base64.getEncoder()
                .encodeToString(bytes);
            return portableCatalog;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot capture kit catalog", e);
        }
    }

    /** Validate all items before atomically replacing definitions; preserve the old file once per session. */
    public static synchronized void receiveCatalog(MinecraftServer server, String payload) {
        if (payload == null || payload.equals(receivedCatalog)) return;
        try {
            if (payload.length() > 6 * 1024 * 1024) throw new IOException("Kit catalog too large");
            byte[] bytes = java.util.Base64.getDecoder()
                .decode(payload);
            NBTTagCompound root = CompressedStreamTools
                .func_152457_a(bytes, new net.minecraft.nbt.NBTSizeTracker(coint.util.NbtAuditLimit.MAX_BYTES));
            coint.util.NbtAuditLimit.check(root);
            if (!coint.trade.TradeItems.localize(root)) throw new IOException("Unknown item in kit catalog");
            Map<String, KitDefinition> next = new LinkedHashMap<>();
            for (String name : (Set<String>) root.func_150296_c()) {
                if (!name.matches("[a-z0-9_-]{1,32}") || !root.hasKey(name, 10))
                    throw new IOException("Invalid kit name/type");
                NBTTagCompound tag = root.getCompoundTag(name);
                String rank = tag.getString("requiredRank");
                if (!rank.isEmpty() && !serverutils.ranks.Ranks.isValidName(rank))
                    throw new IOException("Invalid kit rank");
                KitDefinition kit = readKit(name, tag);
                if (kit.getMaxClaims() == 0 || kit.getMaxClaims() < -1
                    || kit.getItems()
                        .size()
                        != tag.getTagList(TAG_ITEMS, 10)
                            .tagCount())
                    throw new IOException("Invalid kit definition: " + name);
                for (ItemStack item : kit.getItems())
                    if (item.stackSize <= 0) throw new IOException("Invalid kit item count");
                next.put(name, kit);
            }
            NBTTagCompound local = new NBTTagCompound(), definitions = new NBTTagCompound();
            next.forEach((name, kit) -> definitions.setTag(name, writeKit(kit)));
            local.setTag(TAG_KITS, definitions);
            File file = server.getFile(KIT_FILE);
            if (!replicaBackedUp && file.isFile()) {
                java.nio.file.Files.copy(
                    file.toPath(),
                    file.toPath()
                        .resolveSibling("kits.dat.before-cluster-" + java.util.UUID.randomUUID()));
                replicaBackedUp = true;
            }
            atomicWrite(local, file);
            KITS.clear();
            KITS.putAll(next);
            loaded = true;
            receivedCatalog = payload;
            portableCatalog = null;
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Kit replica rejected; previous definitions retained", e);
        }
    }

    private static void atomicWrite(NBTTagCompound root, File file) throws IOException {
        java.nio.file.Path target = file.toPath();
        java.nio.file.Files.createDirectories(target.getParent());
        java.nio.file.Path temporary = java.nio.file.Files.createTempFile(target.getParent(), "kits-", ".tmp");
        try {
            CompressedStreamTools.write(root, temporary.toFile());
            java.nio.file.Files.move(
                temporary,
                target,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            java.nio.file.Files.deleteIfExists(temporary);
        }
    }

    public static int getKitClaimCount(EntityPlayer player, String kitName) {
        NBTTagCompound persisted = NBTUtils.getPersistedData(player, true);
        NBTTagCompound countTag = persisted.getCompoundTag(TAG_KIT_CLAIM_COUNT);
        return countTag.hasKey(kitName) ? countTag.getInteger(kitName) : 0;
    }

    public static void setKitClaimCount(EntityPlayer player, String kitName, int amount) {
        NBTTagCompound persisted = NBTUtils.getPersistedData(player, true);
        NBTTagCompound countTag = persisted.getCompoundTag(TAG_KIT_CLAIM_COUNT);
        if (amount <= 0) {
            countTag.removeTag(kitName);
        } else {
            countTag.setInteger(kitName, amount);
        }
        persisted.setTag(TAG_KIT_CLAIM_COUNT, countTag);
    }

    public static int getKitClaimCount(ForgePlayer player, String kitName) {
        if (player.isOnline()) {
            return getKitClaimCount(player.getPlayer(), kitName);
        }
        NBTTagCompound persisted = getOfflinePersisted(player, false);
        NBTTagCompound countTag = persisted.getCompoundTag(TAG_KIT_CLAIM_COUNT);
        return countTag.hasKey(kitName) ? countTag.getInteger(kitName) : 0;
    }

    public static void setKitClaimCount(ForgePlayer player, String kitName, int amount) {
        if (player.isOnline()) {
            setKitClaimCount(player.getPlayer(), kitName, amount);
            return;
        }
        NBTTagCompound playerNBT = player.getPlayerNBT();
        NBTTagCompound persisted = prepareOfflinePersisted(playerNBT);
        NBTTagCompound countTag = persisted.getCompoundTag(TAG_KIT_CLAIM_COUNT);
        if (amount <= 0) {
            countTag.removeTag(kitName);
        } else {
            countTag.setInteger(kitName, amount);
        }
        persisted.setTag(TAG_KIT_CLAIM_COUNT, countTag);
        writeOfflinePersisted(player, playerNBT, persisted);
    }

    public static int getKitBonusRemaining(EntityPlayer player, String kitName) {
        NBTTagCompound persisted = NBTUtils.getPersistedData(player, true);
        NBTTagCompound tag = persisted.getCompoundTag(TAG_KIT_BONUS_REMAINING);
        return tag.hasKey(kitName) ? tag.getInteger(kitName) : 0;
    }

    public static void setKitBonusRemaining(EntityPlayer player, String kitName, int amount) {
        NBTTagCompound persisted = NBTUtils.getPersistedData(player, true);
        NBTTagCompound tag = persisted.getCompoundTag(TAG_KIT_BONUS_REMAINING);
        if (amount <= 0) {
            tag.removeTag(kitName);
        } else {
            tag.setInteger(kitName, amount);
        }
        persisted.setTag(TAG_KIT_BONUS_REMAINING, tag);
    }

    public static int getKitBonusRemaining(ForgePlayer player, String kitName) {
        if (player.isOnline()) {
            return getKitBonusRemaining(player.getPlayer(), kitName);
        }
        NBTTagCompound persisted = getOfflinePersisted(player, false);
        NBTTagCompound tag = persisted.getCompoundTag(TAG_KIT_BONUS_REMAINING);
        return tag.hasKey(kitName) ? tag.getInteger(kitName) : 0;
    }

    public static void setKitBonusRemaining(ForgePlayer player, String kitName, int amount) {
        if (player.isOnline()) {
            setKitBonusRemaining(player.getPlayer(), kitName, amount);
            return;
        }
        NBTTagCompound playerNBT = player.getPlayerNBT();
        NBTTagCompound persisted = prepareOfflinePersisted(playerNBT);
        NBTTagCompound tag = persisted.getCompoundTag(TAG_KIT_BONUS_REMAINING);
        if (amount <= 0) {
            tag.removeTag(kitName);
        } else {
            tag.setInteger(kitName, amount);
        }
        persisted.setTag(TAG_KIT_BONUS_REMAINING, tag);
        writeOfflinePersisted(player, playerNBT, persisted);
    }

    /**
     * Persisted NBT для оффлайн-игрока: {@code ForgeData/PlayerPersisted}, как {@link NBTUtils#getPersistedData}.
     * При чтении переносит kit-счётчики из ошибочной корневой {@code PlayerPersisted} (до фикса).
     */
    private static NBTTagCompound getOfflinePersisted(ForgePlayer player, boolean createIfMissing) {
        NBTTagCompound playerNBT = player.getPlayerNBT();
        NBTTagCompound persisted = getOfflinePersistedFromNbt(playerNBT, createIfMissing);
        if (migrateLegacyPersisted(playerNBT, persisted)) {
            writeOfflinePersisted(player, playerNBT, persisted);
        }
        return persisted;
    }

    private static NBTTagCompound prepareOfflinePersisted(NBTTagCompound playerNBT) {
        NBTTagCompound persisted = getOfflinePersistedFromNbt(playerNBT, true);
        migrateLegacyPersisted(playerNBT, persisted);
        return persisted;
    }

    private static NBTTagCompound getOfflinePersistedFromNbt(NBTTagCompound playerNBT, boolean createIfMissing) {
        NBTTagCompound forgeData = playerNBT.getCompoundTag(TAG_FORGE_DATA);
        NBTTagCompound persisted = forgeData.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        if (createIfMissing) {
            forgeData.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
            playerNBT.setTag(TAG_FORGE_DATA, forgeData);
        }
        return persisted;
    }

    private static void writeOfflinePersisted(ForgePlayer player, NBTTagCompound playerNBT, NBTTagCompound persisted) {
        NBTTagCompound forgeData = playerNBT.getCompoundTag(TAG_FORGE_DATA);
        forgeData.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
        playerNBT.setTag(TAG_FORGE_DATA, forgeData);
        if (playerNBT.hasKey(EntityPlayer.PERSISTED_NBT_TAG)) {
            playerNBT.removeTag(EntityPlayer.PERSISTED_NBT_TAG);
        }
        player.setPlayerNBT(playerNBT);
    }

    /** Перенос kit-тегов из корневой PlayerPersisted (старый баг) в ForgeData/PlayerPersisted. */
    private static boolean migrateLegacyPersisted(NBTTagCompound playerNBT, NBTTagCompound persisted) {
        if (!playerNBT.hasKey(EntityPlayer.PERSISTED_NBT_TAG)) {
            return false;
        }
        NBTTagCompound legacy = playerNBT.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        boolean changed = mergeKitCounters(legacy, persisted, TAG_KIT_CLAIM_COUNT);
        changed |= mergeKitCounters(legacy, persisted, TAG_KIT_BONUS_REMAINING);
        if (legacy.hasNoTags()) {
            playerNBT.removeTag(EntityPlayer.PERSISTED_NBT_TAG);
            changed = true;
        }
        return changed;
    }

    private static boolean mergeKitCounters(NBTTagCompound legacy, NBTTagCompound persisted, String tagKey) {
        if (!legacy.hasKey(tagKey)) {
            return false;
        }
        NBTTagCompound legacySub = legacy.getCompoundTag(tagKey);
        NBTTagCompound persistedSub = persisted.getCompoundTag(tagKey);
        boolean changed = false;
        for (String kitName : legacySub.func_150296_c()) {
            if (!persistedSub.hasKey(kitName)) {
                persistedSub.setInteger(kitName, legacySub.getInteger(kitName));
                changed = true;
            }
        }
        if (changed) {
            persisted.setTag(tagKey, persistedSub);
        }
        legacy.removeTag(tagKey);
        return changed;
    }
}
