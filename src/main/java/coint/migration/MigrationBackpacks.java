package coint.migration;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.chunk.storage.RegionFile;

import coint.integration.BackpackTransfer;
import coint.trade.TradeItems;
import cpw.mods.fml.common.Loader;

/** Select only storage referenced by the snapshot, including personal slots and nested backpacks. */
final class MigrationBackpacks {

    static final String FILE = "cointcore-migration/backpacks.dat";

    private MigrationBackpacks() {}

    /** Server thread: snapshot personal slots through the mod API before the asynchronous archive worker. */
    static void players(File staging, String world, List<Map<String, Object>> players) throws IOException {
        if (!Loader.isModLoaded("Backpack")) return;
        for (Map<String, Object> player : players) {
            String id = BackpackTransfer.uuid(
                player.get("uuid")
                    .toString());
            NBTTagCompound data = BackpackTransfer.load("Player", id);
            // The open-container marker is a session state, not part of the player's inventory.
            data.removeTag("personalBackpackOpen");
            TradeItems.portable(data);
            write(MigrationStorage.child(staging, world + "/backpacks/player/" + id + ".dat"), data);
        }
    }

    /** The move has frozen the worlds and disconnected members; scan the immutable snapshot, not live chunks. */
    static void snapshot(File staging, String world) throws IOException {
        NBTTagCompound bags = new NBTTagCompound();
        try (java.util.stream.Stream<Path> paths = Files.walk(staging.toPath())) {
            for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                String relative = staging.toPath()
                    .relativize(path)
                    .toString()
                    .replace(File.separatorChar, '/');
                if (relative.endsWith(".mca")) {
                    RegionFile region = new RegionFile(path.toFile());
                    try {
                        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
                            try (DataInputStream input = region.getChunkDataInputStream(x, z)) {
                                if (input != null) BackpackTransfer.capture(CompressedStreamTools.read(input), bags);
                            }
                        }
                    } finally {
                        region.close();
                    }
                } else if ((relative.startsWith(world + "/playerdata/")
                    && (relative.endsWith(".dat") || relative.endsWith(".baub")))
                    || relative.startsWith(world + "/backpacks/player/")
                    || relative.equals(MigrationGlobalData.FILE)
                    || relative.startsWith("cointcore-migration/chunks/")) {
                        BackpackTransfer.capture(read(path.toFile()), bags);
                    }
            }
        }
        for (Object id : bags.func_150296_c()) TradeItems.portable(bags.getCompoundTag(id.toString()));
        BackpackTransfer.localBags(bags);
        write(MigrationStorage.child(staging, FILE), bags);
    }

    static NBTTagCompound read(File file) throws IOException {
        try (InputStream input = Files.newInputStream(file.toPath())) {
            return CompressedStreamTools.readCompressed(input);
        }
    }

    static void write(File file, NBTTagCompound data) throws IOException {
        Files.createDirectories(
            file.toPath()
                .getParent());
        try (OutputStream output = Files.newOutputStream(file.toPath())) {
            CompressedStreamTools.writeCompressed(data, output);
        }
    }

    private static NBTTagCompound incoming(File staging) throws IOException {
        return BackpackTransfer.localBags(read(MigrationStorage.child(staging, FILE)));
    }

    /** Before any world files are replaced. Reject an ID collision instead of overwriting somebody's items. */
    static void check(File staging, Map<String, Object> manifest) throws IOException {
        if (!manifest.containsKey("backpacks")) return; // Legacy archives did not include external storage.
        if (!(manifest.get("backpacks") instanceof Number version) || version.intValue() != 1)
            throw new IOException("Unsupported migration backpack format");
        BackpackTransfer.check(incoming(staging), false);
        String world = manifest.get("worldFolder")
            .toString();
        for (Map<String, Object> player : MigrationImporter.list(manifest.get("players"))) {
            String id = BackpackTransfer.uuid(
                player.get("uuid")
                    .toString());
            File source = MigrationStorage.child(staging, world + "/backpacks/player/" + id + ".dat");
            if (!source.isFile()) continue;
            if (!Loader.isModLoaded("Backpack")) throw new IOException("Backpack is missing on destination");
            NBTTagCompound data = read(source);
            if (!TradeItems.localize(data)) throw new IOException("Unknown personal backpack item: " + id);
        }
    }

    static void install(File staging, Map<String, Object> manifest, File backup) throws IOException {
        if (!manifest.containsKey("backpacks")) return;
        check(staging, manifest);
        NBTTagCompound bags = incoming(staging);
        BackpackTransfer.install(bags, false);
        String world = manifest.get("worldFolder")
            .toString();
        Set<String> changed = new HashSet<>();
        for (Map<String, Object> player : MigrationImporter.list(manifest.get("players"))) {
            String id = BackpackTransfer.uuid(
                player.get("uuid")
                    .toString());
            File source = MigrationStorage.child(staging, world + "/backpacks/player/" + id + ".dat");
            if (!source.isFile()) continue;
            NBTTagCompound before = BackpackTransfer.load("Player", id);
            write(MigrationStorage.child(backup, "backpacks/player/" + id + ".dat"), before);
            NBTTagCompound data = read(source);
            if (!TradeItems.localize(data)) throw new IOException("Unknown personal backpack: " + id);
            BackpackTransfer.save("Player", id, data);
            changed.add(id);
        }
        if (!changed.isEmpty()) {
            BackpackTransfer.flush();
            for (String id : changed) BackpackTransfer.verify("Player", id, BackpackTransfer.load("Player", id));
        }
    }

    static void retain(File staging, String world, MoveRecord move) throws IOException {
        if (move == null) return;
        File retained = MigrationStorage.child(MigrationStorage.localDirectory(), "move-snapshots/" + move.id);
        write(MigrationStorage.child(retained, FILE), read(MigrationStorage.child(staging, FILE)));
        for (String id : move.players) {
            String relative = "backpacks/player/" + BackpackTransfer.uuid(id) + ".dat";
            File source = MigrationStorage.child(staging, world + "/" + relative);
            if (source.isFile()) write(MigrationStorage.child(retained, relative), read(source));
        }
    }

    /** Only after confirmed import. Keep the old slot in the retirement backup; update the mod's cache too. */
    static void retire(MoveRecord move, File backup) throws IOException {
        File retained = MigrationStorage.child(MigrationStorage.localDirectory(), "move-snapshots/" + move.id);
        if (!MigrationStorage.child(retained, FILE)
            .isFile()) return; // An already exported legacy move.
        NBTTagCompound bags = incoming(retained);
        for (Object raw : bags.func_150296_c()) {
            String id = raw.toString();
            if (!bags.getCompoundTag(id)
                .equals(BackpackTransfer.load("Backpack", id)))
                throw new IOException("Backpack contents changed after export: " + id);
        }
        Set<String> changed = new HashSet<>();
        for (String id : move.players) {
            String relative = "backpacks/player/" + BackpackTransfer.uuid(id) + ".dat";
            File snapshot = MigrationStorage.child(retained, relative);
            if (!snapshot.isFile()) continue;
            NBTTagCompound expected = read(snapshot);
            if (!TradeItems.localize(expected)) throw new IOException("Unknown backpack item on source");
            NBTTagCompound current = BackpackTransfer.load("Player", id);
            current.removeTag("personalBackpackOpen");
            File copy = MigrationStorage.child(backup, relative);
            if (current.hasNoTags() && copy.isFile()) {
                changed.add(id); // A previous attempt may have updated the cache but failed to flush it.
                continue;
            }
            if (!expected.equals(current)) throw new IOException("Personal backpack changed after export: " + id);
            if (!copy.isFile()) write(copy, current);
            BackpackTransfer.save("Player", id, new NBTTagCompound());
            changed.add(id);
        }
        if (!changed.isEmpty()) {
            BackpackTransfer.flush();
            for (String id : changed) BackpackTransfer.verify("Player", id, new NBTTagCompound());
        }
    }
}
