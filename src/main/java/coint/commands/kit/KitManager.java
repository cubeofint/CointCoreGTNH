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
    /** Остаток выданных «покупных» получений для безлимитных наборов (maxClaims == -1). */
    public static final String TAG_KIT_BONUS_REMAINING = "cointcore_kit_bonus_remaining";

    private static final Map<String, KitDefinition> KITS = new LinkedHashMap<>();
    private static boolean loaded = false;

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
                loaded = true;
                return;
            }

            NBTTagCompound kitsTag = root.getCompoundTag(TAG_KITS);
            Set<String> keys = kitsTag.func_150296_c();
            for (String name : keys) {
                NBTTagCompound kitTag = kitsTag.getCompoundTag(name);
                KitDefinition kit = readKit(name, kitTag);
                KITS.put(name, kit);
            }
        } catch (IOException ignored) {
            // Ignore corrupt or missing data, will recreate on save.
        }

        loaded = true;
    }

    public static synchronized void save(MinecraftServer server) {
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
            CompressedStreamTools.write(root, file);
        } catch (IOException ignored) {
            // Ignore write errors.
        }
    }

    public static synchronized void putKit(MinecraftServer server, KitDefinition kit) {
        loadIfNeeded(server);
        KITS.put(kit.getName(), kit);
        save(server);
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
        if (KITS.remove(name) != null) {
            save(server);
        }
    }

    private static KitDefinition readKit(String name, NBTTagCompound kitTag) {
        List<ItemStack> items = new ArrayList<>();
        NBTTagList list = kitTag.getTagList(TAG_ITEMS, 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound itemTag = list.getCompoundTagAt(i);
            ItemStack stack = ItemStack.loadItemStackFromNBT(itemTag);
            if (stack != null) {
                items.add(stack);
            }
        }
        int maxClaims = kitTag.hasKey(TAG_MAX_CLAIMS) ? kitTag.getInteger(TAG_MAX_CLAIMS) : -1;
        return new KitDefinition(name, items, maxClaims);
    }

    private static NBTTagCompound writeKit(KitDefinition kit) {
        NBTTagCompound kitTag = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        for (ItemStack stack : kit.getItems()) {
            if (stack == null) {
                continue;
            }
            NBTTagCompound itemTag = new NBTTagCompound();
            stack.writeToNBT(itemTag);
            list.appendTag(itemTag);
        }
        kitTag.setTag(TAG_ITEMS, list);
        kitTag.setInteger(TAG_MAX_CLAIMS, kit.getMaxClaims());
        return kitTag;
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
        NBTTagCompound playerNBT = player.getPlayerNBT();
        NBTTagCompound persisted = playerNBT.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        NBTTagCompound countTag = persisted.getCompoundTag(TAG_KIT_CLAIM_COUNT);
        return countTag.hasKey(kitName) ? countTag.getInteger(kitName) : 0;
    }

    public static void setKitClaimCount(ForgePlayer player, String kitName, int amount) {
        if (player.isOnline()) {
            setKitClaimCount(player.getPlayer(), kitName, amount);
            return;
        }
        NBTTagCompound playerNBT = player.getPlayerNBT();
        NBTTagCompound persisted = playerNBT.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        NBTTagCompound countTag = persisted.getCompoundTag(TAG_KIT_CLAIM_COUNT);
        if (amount <= 0) {
            countTag.removeTag(kitName);
        } else {
            countTag.setInteger(kitName, amount);
        }
        persisted.setTag(TAG_KIT_CLAIM_COUNT, countTag);
        playerNBT.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
        player.setPlayerNBT(playerNBT);
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
        NBTTagCompound playerNBT = player.getPlayerNBT();
        NBTTagCompound persisted = playerNBT.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        NBTTagCompound tag = persisted.getCompoundTag(TAG_KIT_BONUS_REMAINING);
        return tag.hasKey(kitName) ? tag.getInteger(kitName) : 0;
    }

    public static void setKitBonusRemaining(ForgePlayer player, String kitName, int amount) {
        if (player.isOnline()) {
            setKitBonusRemaining(player.getPlayer(), kitName, amount);
            return;
        }
        NBTTagCompound playerNBT = player.getPlayerNBT();
        NBTTagCompound persisted = playerNBT.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        NBTTagCompound tag = persisted.getCompoundTag(TAG_KIT_BONUS_REMAINING);
        if (amount <= 0) {
            tag.removeTag(kitName);
        } else {
            tag.setInteger(kitName, amount);
        }
        persisted.setTag(TAG_KIT_BONUS_REMAINING, tag);
        playerNBT.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
        player.setPlayerNBT(playerNBT);
    }
}
