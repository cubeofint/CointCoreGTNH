package coint.migration;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Known world clocks may advance/reset on load. Never relax region, player or inventory comparisons. */
final class MigrationRuntimeData {

    private MigrationRuntimeData() {}

    static boolean same(Path incoming, Path target, String dimensionRelativePath) throws IOException {
        boolean multipart = dimensionRelativePath.equals("multipart.dat");
        boolean villages = dimensionRelativePath.equals("data/villages.dat");
        if (!multipart && !villages) return false;
        NBTTagCompound expected = read(incoming), actual = read(target);
        if (expected.equals(actual)) return true;
        if (multipart) {
            // ForgeMultipart 1.7.15 TickScheduler.saveTag writes only this long; part data/ticks live in chunks.
            if (!expected.hasKey("schedTime", 4) || !actual.hasKey("schedTime", 4)
                || expected.func_150296_c()
                    .size() != 1
                || actual.func_150296_c()
                    .size() != 1)
                throw new IOException("Неизвестные данные планировщика multipart: " + target);
            expected.removeTag("schedTime");
            actual.removeTag("schedTime");
        } else {
            if (!expected.hasKey("data", 10) || !actual.hasKey("data", 10))
                throw new IOException("Нет данных списка деревень: " + target);
            NBTTagCompound first = expected.getCompoundTag("data"), second = actual.getCompoundTag("data");
            if (!first.hasKey("Tick", 3) || !second.hasKey("Tick", 3)
                || !(first.getTag("Villages") instanceof NBTTagList a)
                || !(second.getTag("Villages") instanceof NBTTagList b)
                || a.tagCount() != 0
                || b.tagCount() != 0)
                throw new IOException("Изменённый счётчик допустим только для пустого списка деревень: " + target);
            // VillageCollection.tick increments this even without villages; no per-village timestamps to shift.
            first.removeTag("Tick");
            second.removeTag("Tick");
        }
        // Keep every other root/data tag, type, array and list strict. Mutate only these temporary NBT objects.
        return expected.equals(actual);
    }

    private static NBTTagCompound read(Path file) throws IOException {
        try (InputStream raw = Files.newInputStream(file);
            DataInputStream input = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
            NBTTagCompound data = CompressedStreamTools.func_152456_a(input, new NBTSizeTracker(8 * 1024 * 1024));
            if (input.read() != -1) throw new IOException("Лишние данные после NBT: " + file);
            return data;
        }
    }
}
