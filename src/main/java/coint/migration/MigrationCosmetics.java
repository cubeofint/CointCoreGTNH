package coint.migration;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.google.common.cache.Cache;

import coint.integration.BackpackTransfer;
import cpw.mods.fml.common.Loader;

/** CosmeticArmorReworked 1.0.7 stores real items outside vanilla player NBT, in UUID.cosarmor. */
final class MigrationCosmetics {

    static final String KEY = "cosmeticArmor";
    private static final String SIZE = "CosArmor.Inventory.Size", INVENTORY = "CosArmor.Inventory";

    private MigrationCosmetics() {}

    private static String path(String uuid) {
        return "playerdata/" + UUID.fromString(uuid) + ".cosarmor";
    }

    /** Players are offline: the mod's logout handler saves their slots and invalidates its cache. */
    static boolean snapshot(File staging, String world, List<Map<String, Object>> players) throws IOException {
        if (!Loader.isModLoaded("cosmeticarmorreworked")) return false;
        cache(); // Check the optional integration before publishing an archive that requires it.
        File source = MigrationStorage.session().world;
        for (Map<String, Object> player : players) {
            String relative = path(
                player.get("uuid")
                    .toString());
            NBTTagCompound data = readOrEmpty(MigrationStorage.child(source, relative));
            validate(data);
            // Explicit empty slots also replace equipment left by an earlier visit to the destination.
            MigrationBackpacks.write(MigrationStorage.child(staging, world + "/" + relative), data);
        }
        return true;
    }

    /** Run before replacing any world/player files. Legacy archives leave the destination's cosmetics alone. */
    static void check(File staging, Map<String, Object> manifest) throws IOException {
        if (!manifest.containsKey(KEY)) return;
        if (!(manifest.get(KEY) instanceof Number version) || version.doubleValue() != 1)
            throw new IOException("Unsupported migration cosmetic armor format");
        cache();
        String world = manifest.get("worldFolder")
            .toString();
        for (Map<String, Object> player : MigrationImporter.list(manifest.get("players"))) {
            File file = MigrationStorage.child(
                staging,
                world + "/"
                    + path(
                        player.get("uuid")
                            .toString()));
            if (!file.isFile()) throw new IOException("В архиве нет косметических слотов: " + file.getName());
            validate(MigrationBackpacks.read(file));
        }
    }

    /** Files have been installed while players are offline; do not let a previously cached visit overwrite them. */
    static void installed(Map<String, Object> manifest) throws IOException {
        if (!manifest.containsKey(KEY)) return;
        Cache<?, ?> inventories = cache();
        for (Map<String, Object> player : MigrationImporter.list(manifest.get("players"))) inventories.invalidate(
            UUID.fromString(
                player.get("uuid")
                    .toString()));
    }

    static void retain(File staging, String world, List<Map<String, Object>> players, MoveRecord move)
        throws IOException {
        if (move == null) return;
        File retained = MigrationStorage.child(MigrationStorage.localDirectory(), "move-snapshots/" + move.id);
        for (Map<String, Object> player : players) {
            String relative = path(
                player.get("uuid")
                    .toString());
            File source = MigrationStorage.child(staging, world + "/" + relative);
            if (source.isFile())
                MigrationBackpacks.write(MigrationStorage.child(retained, relative), MigrationBackpacks.read(source));
        }
    }

    /** Only after confirmed import. Never retire cosmetics omitted by an older export. */
    static void retire(MoveRecord move, File backup) throws IOException {
        File retained = MigrationStorage.child(MigrationStorage.localDirectory(), "move-snapshots/" + move.id);
        File source = MigrationStorage.session().world;
        for (String uuid : move.players) {
            String relative = path(uuid);
            File snapshot = MigrationStorage.child(retained, relative);
            if (!snapshot.isFile()) continue;
            Cache<?, ?> inventories = cache();
            File file = MigrationStorage.child(source, relative), copy = MigrationStorage.child(backup, relative);
            NBTTagCompound expected = MigrationBackpacks.read(snapshot);
            if (copy.isFile()) {
                if (file.exists() || !expected.equals(MigrationBackpacks.read(copy)))
                    throw new IOException("Конфликт резервной копии косметических слотов: " + uuid);
            } else {
                if (!expected.equals(readOrEmpty(file)))
                    throw new IOException("Косметические слоты изменились после экспорта: " + uuid);
                if (file.isFile()) {
                    Files.createDirectories(
                        copy.toPath()
                            .getParent());
                    Files.move(file.toPath(), copy.toPath(), StandardCopyOption.ATOMIC_MOVE);
                }
            }
            inventories.invalidate(UUID.fromString(uuid));
        }
    }

    private static NBTTagCompound readOrEmpty(File file) throws IOException {
        if (file.exists()) return MigrationBackpacks.read(file);
        NBTTagCompound empty = new NBTTagCompound();
        empty.setInteger(SIZE, 4);
        NBTTagList slots = new NBTTagList();
        for (int i = 0; i < 4; i++) {
            NBTTagCompound slot = new NBTTagCompound();
            slot.setByte("Slot", (byte) i);
            slot.setBoolean("isSkinArmor", false);
            slots.appendTag(slot);
        }
        empty.setTag(INVENTORY, slots);
        return empty;
    }

    private static void validate(NBTTagCompound data) throws IOException {
        if (!data.hasKey(SIZE, 3) || data.getInteger(SIZE) != 4
            || !(data.getTag(INVENTORY) instanceof NBTTagList slots)
            || slots.tagCount() != 4
            || slots.func_150303_d() != 10) throw new IOException("Неизвестный формат косметических слотов");
        boolean[] seen = new boolean[4];
        for (int i = 0; i < slots.tagCount(); i++) {
            NBTTagCompound slot = slots.getCompoundTagAt(i);
            int index = slot.getByte("Slot") & 255;
            if (!slot.hasKey("Slot", 1) || index >= 4 || seen[index] || !slot.hasKey("isSkinArmor", 1))
                throw new IOException("Повреждённый косметический слот");
            if ((slot.hasKey("id") || slot.hasKey("Count")) && ItemStack.loadItemStackFromNBT(slot) == null)
                throw new IOException("Неизвестный предмет в косметическом слоте");
            seen[index] = true;
        }
        BackpackTransfer.visit(data, tag -> {
            if (tag.hasKey("id", 2) && tag.hasKey("Count") && ItemStack.loadItemStackFromNBT(tag) == null)
                throw new IOException("Неизвестный предмет в косметических слотах");
        });
    }

    private static Cache<?, ?> cache() throws IOException {
        if (!Loader.isModLoaded("cosmeticarmorreworked"))
            throw new IOException("CosmeticArmorReworked отсутствует: перенос косметических слотов невозможен");
        try {
            Object manager = Class.forName("lain.mods.cos.CosmeticArmorReworked")
                .getField("invMan")
                .get(null);
            Field field = Class.forName("lain.mods.cos.InventoryManager")
                .getDeclaredField("cache");
            field.setAccessible(true);
            if (!(field.get(manager) instanceof Cache<?, ?>inventories))
                throw new IOException("Кэш CosmeticArmorReworked не готов");
            return inventories;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IOException("Не удалось обновить кэш CosmeticArmorReworked", e);
        }
    }
}
