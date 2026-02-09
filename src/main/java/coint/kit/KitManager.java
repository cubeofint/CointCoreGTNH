package coint.kit;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;

public final class KitManager {

    private static final String KIT_FILE = "cointcore/kits.dat";
    private static final String TAG_KITS = "kits";
    private static final String TAG_ITEMS = "items";
    private static final String TAG_COOLDOWN = "cooldownTicks";

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
            @SuppressWarnings("unchecked")
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
        long cooldownTicks = kitTag.getLong(TAG_COOLDOWN);
        List<ItemStack> items = new ArrayList<>();
        NBTTagList list = kitTag.getTagList(TAG_ITEMS, 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound itemTag = list.getCompoundTagAt(i);
            ItemStack stack = ItemStack.loadItemStackFromNBT(itemTag);
            if (stack != null) {
                items.add(stack);
            }
        }
        return new KitDefinition(name, items, cooldownTicks);
    }

    private static NBTTagCompound writeKit(KitDefinition kit) {
        NBTTagCompound kitTag = new NBTTagCompound();
        kitTag.setLong(TAG_COOLDOWN, kit.getCooldownTicks());
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
        return kitTag;
    }
}
