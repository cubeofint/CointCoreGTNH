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
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

public final class KitManager {

    private static final String KIT_FILE = "cointcore/kits.dat";
    private static final String TAG_KITS = "kits";
    private static final String TAG_ITEMS = "items";
    private static final String TAG_COOLDOWN_TICKS = "cooldownTicks";

    public static final String TAG_KIT_BALANCE = "cointcore_kit_balance";

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

        // Автоматически регистрируем право доступа при создании набора
        PermissionAPI.registerNode(
            "cointcore.kit." + kit.getName(),
            DefaultPermissionLevel.NONE,
            "CointCore kit: " + kit.getName());
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
        long cooldownTicks = kitTag.hasKey(TAG_COOLDOWN_TICKS) ? kitTag.getLong(TAG_COOLDOWN_TICKS) : 0L;
        return new KitDefinition(name, items, cooldownTicks);
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
        kitTag.setLong(TAG_COOLDOWN_TICKS, kit.getCooldownTicks());
        return kitTag;
    }

    public static int getKitBalance(EntityPlayer player, String kitName) {
        NBTTagCompound persisted = NBTUtils.getPersistedData(player, true);
        NBTTagCompound balanceTag = persisted.getCompoundTag(TAG_KIT_BALANCE);
        return balanceTag.hasKey(kitName) ? balanceTag.getInteger(kitName) : -1;
    }

    public static void setKitBalance(EntityPlayer player, String kitName, int amount) {
        NBTTagCompound persisted = NBTUtils.getPersistedData(player, true);
        NBTTagCompound balanceTag = persisted.getCompoundTag(TAG_KIT_BALANCE);
        if (amount < 0) {
            balanceTag.removeTag(kitName);
        } else {
            balanceTag.setInteger(kitName, amount);
        }
        persisted.setTag(TAG_KIT_BALANCE, balanceTag);
    }

    /** @deprecated Используйте вариант с ForgePlayer */
    @Deprecated
    public static void addKitBalance(EntityPlayer player, String kitName, int amount) {
        int current = getKitBalance(player, kitName);
        setKitBalance(player, kitName, current < 0 ? amount : current + amount);
    }

    /** @deprecated Устаревший метод, оставлен для обратной совместимости */
    @Deprecated
    @SuppressWarnings("unused")
    public static boolean consumeKitUse(EntityPlayer player, String kitName) {
        int balance = getKitBalance(player, kitName);
        if (balance == 0) {
            return false;
        }
        if (balance > 0) {
            setKitBalance(player, kitName, balance - 1);
        }
        return true;
    }

    public static int getKitBalance(ForgePlayer player, String kitName) {
        if (player.isOnline()) {
            return getKitBalance(player.getPlayer(), kitName);
        }
        NBTTagCompound playerNBT = player.getPlayerNBT();
        NBTTagCompound persisted = playerNBT.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        NBTTagCompound balanceTag = persisted.getCompoundTag(TAG_KIT_BALANCE);
        return balanceTag.hasKey(kitName) ? balanceTag.getInteger(kitName) : -1;
    }

    public static void setKitBalance(ForgePlayer player, String kitName, int amount) {
        if (player.isOnline()) {
            setKitBalance(player.getPlayer(), kitName, amount);
            return;
        }
        NBTTagCompound playerNBT = player.getPlayerNBT();
        NBTTagCompound persisted = playerNBT.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        NBTTagCompound balanceTag = persisted.getCompoundTag(TAG_KIT_BALANCE);
        if (amount < 0) {
            balanceTag.removeTag(kitName);
        } else {
            balanceTag.setInteger(kitName, amount);
        }
        persisted.setTag(TAG_KIT_BALANCE, balanceTag);
        playerNBT.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
        player.setPlayerNBT(playerNBT);
    }

    /** @deprecated Устаревший метод, оставлен для обратной совместимости */
    @Deprecated
    @SuppressWarnings("unused")
    public static void addKitBalance(ForgePlayer player, String kitName, int amount) {
        int current = getKitBalance(player, kitName);
        setKitBalance(player, kitName, current < 0 ? amount : current + amount);
    }
}
