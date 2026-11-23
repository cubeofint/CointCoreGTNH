package coint.migration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

/** Finish only the SU stage after a wallet save-verification failure. Never reinstall worlds, items or balances. */
final class MigrationWalletRecovery {

    private MigrationWalletRecovery() {}

    static final class VerifiedFile {

        final Path file;
        final BasicFileAttributes attributes;

        VerifiedFile(Path file) throws IOException {
            this.file = file;
            attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) throw new IOException("Необычный файл на цели: " + file);
        }

        void unchanged() throws IOException {
            BasicFileAttributes now = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!now.isRegularFile() || now.size() != attributes.size()
                || !now.lastModifiedTime()
                    .equals(attributes.lastModifiedTime())
                || !java.util.Objects.equals(now.fileKey(), attributes.fileKey()))
                throw new IOException("Файл изменился во время проверки: " + file);
        }
    }

    /** Expensive world checks run on the importer worker while the original migration freeze stays active. */
    static List<VerifiedFile> verifyFiles(MigrationImporter.Operation operation, Map<String, Object> manifest)
        throws IOException {
        if (operation.move == null || operation.move.joining()
            || operation.move.vendingSnapshot == null
            || !manifest.containsKey("vending"))
            throw new IOException("Восстановление поддерживает только перенос всей команды с кошельком");
        File source = MigrationStorage.child(operation.staging, MigrationImporter.str(manifest.get("worldFolder")));
        List<VerifiedFile> proof = new ArrayList<>();
        for (Map<String, Object> dim : MigrationImporter.list(manifest.get("personalDimensions"))) {
            if (dim.containsKey("missing")) throw new IOException("В архиве отсутствует персоналка");
            String folder = MigrationImporter.str(dim.get("folder"));
            File incoming = MigrationStorage.child(source, folder);
            try (Stream<Path> files = Files.walk(incoming.toPath())) {
                for (Path file : files.filter(Files::isRegularFile)
                    .collect(Collectors.toList())) {
                    if (!operation.current()) throw new IOException("Сессия восстановления завершена");
                    String relative = source.toPath()
                        .relativize(file)
                        .toString()
                        .replace(File.separatorChar, '/');
                    File target = MigrationStorage.child(operation.session.world, relative);
                    proof.add(
                        verifyFile(
                            file,
                            target.toPath(),
                            file.getFileName()
                                .toString()
                                .equals("personalspace_metadata.cfg")));
                }
            }
        }
        for (Map<String, Object> player : MigrationImporter.list(manifest.get("players"))) {
            String uuid = MigrationImporter.str(player.get("uuid"));
            String name = MigrationImporter.str(player.get("name"));
            for (File file : MigrationImporter.playerFiles(source, uuid, name)) {
                String relative = source.toPath()
                    .relativize(file.toPath())
                    .toString()
                    .replace(File.separatorChar, '/');
                // Player position is deliberately adjusted during an import; compare its NBT on the main thread.
                // GTNHLib files are verified through the live wallet API, never copied behind its cache.
                if (relative.replace(File.separatorChar, '/')
                    .equals("playerdata/" + uuid + ".dat")
                    || file.getParentFile()
                        .getName()
                        .equals("gtnhteams"))
                    continue;
                proof.add(
                    verifyFile(
                        file.toPath(),
                        MigrationStorage.child(operation.session.world, relative)
                            .toPath(),
                        false));
            }
        }
        return proof;
    }

    static VerifiedFile verifyFile(Path incoming, Path target, boolean metadata) throws IOException {
        VerifiedFile proof = new VerifiedFile(target);
        boolean same;
        if (metadata) {
            Map<String, String> expected = config(incoming), actual = config(target);
            Set<String> different = new java.util.TreeSet<>(expected.keySet());
            different.addAll(actual.keySet());
            different.removeIf(key -> java.util.Objects.equals(expected.get(key), actual.get(key)));
            if (!different.isEmpty())
                throw new IOException("Настройки персоналки отличаются от архива: " + target + "; поля: " + different);
            same = true;
        } else same = Files.size(incoming) == proof.attributes.size() && MigrationStorage.sha256(incoming)
            .equals(MigrationStorage.sha256(target));
        if (!same) throw new IOException(
            "Уже размещённый файл отличается от архива: " + target
                + ". Восстановление остановлено, повторного импорта не было.");
        proof.unchanged();
        return proof;
    }

    private static Map<String, String> config(Path file) throws IOException {
        // Forge's constructor may rename a malformed config and create a replacement. Parse only a temporary copy.
        Path directory = Files.createTempDirectory("coint-metadata-check-");
        try {
            Path snapshot = directory.resolve("metadata.cfg");
            Files.copy(file, snapshot);
            String hash = MigrationStorage.sha256(snapshot);
            Configuration config = new Configuration(snapshot.toFile());
            if (!hash.equals(MigrationStorage.sha256(snapshot)) || !config.hasKey("worldgen", "dimId"))
                throw new IOException("Не удалось прочитать настройки персоналки: " + file);
            return configValues(config, file);
        } finally {
            try (Stream<Path> files = Files.list(directory)) {
                for (Path entry : files.collect(Collectors.toList())) Files.delete(entry);
            }
            Files.delete(directory);
        }
    }

    private static Map<String, String> configValues(Configuration config, Path file) throws IOException {
        Map<String, String> values = new TreeMap<>();
        for (String category : config.getCategoryNames()) {
            for (Map.Entry<String, Property> entry : config.getCategory(category)
                .entrySet()) {
                Property value = entry.getValue();
                String key = category + "/" + entry.getKey();
                values.put(
                    key,
                    runtimeTime(key, value, file) ? value.getType() + ":<runtime>"
                        : value.getType() + ":"
                            + (value.isList() ? Arrays.toString(value.getStringList()) : value.getString()));
            }
        }
        return values;
    }

    /**
     * PersonalSpace 1.0.41 persists these five fields from WorldInfo on save; generation/visual settings stay strict.
     */
    private static boolean runtimeTime(String key, Property value, Path file) throws IOException {
        Property.Type type;
        switch (key) {
            case "time/worldTime":
                type = Property.Type.STRING;
                break;
            case "time/rainTime":
            case "time/thunderTime":
                type = Property.Type.INTEGER;
                break;
            case "time/raining":
            case "time/thundering":
                type = Property.Type.BOOLEAN;
                break;
            default:
                return false;
        }
        try {
            if (value.isList() || value.getType() != type) throw new IllegalArgumentException("type");
            String raw = value.getString();
            if (type == Property.Type.STRING) Long.parseLong(raw);
            else if (type == Property.Type.INTEGER) Integer.parseInt(raw);
            else if (!"true".equalsIgnoreCase(raw) && !"false".equalsIgnoreCase(raw))
                throw new IllegalArgumentException("boolean");
        } catch (IllegalArgumentException e) {
            throw new IOException("Некорректное поле времени/погоды " + key + ": " + file, e);
        }
        return true;
    }

    static String finish(MigrationImporter.Operation operation, Map<String, Object> manifest) {
        try {
            MigrationMove.checkWalletRecovery(operation.move);
            if (operation.verifiedFiles == null) throw new IOException("Нет проверки уже размещённых файлов");
            if (operation.verifiedChunks == null) throw new IOException("Нет проверки внешних чанков");
            for (VerifiedFile proof : operation.verifiedFiles) proof.unchanged();
            for (MigrationChunkRecovery proof : operation.verifiedChunks) proof.unchanged();
            File source = MigrationStorage.child(operation.staging, MigrationImporter.str(manifest.get("worldFolder")));
            Set<Integer> dims = new HashSet<>(operation.move.dimensions);
            Map<String, Object> team = MigrationImporter.map(manifest.get("team"));
            int[] entry = team == null ? null : MigrationImporter.ints(team.get("entry"));
            for (String uuid : operation.move.players) {
                File incoming = MigrationStorage.child(source, "playerdata/" + uuid + ".dat");
                MigrationImporter.placePlayer(incoming, dims, entry); // Only the temporary verification copy.
                Path target = MigrationStorage.child(operation.session.world, "playerdata/" + uuid + ".dat")
                    .toPath();
                if (!nbt(incoming.toPath()).equals(nbt(target)))
                    throw new IOException("Данные игрока отличаются от размещённого снимка: " + uuid);
            }
            // This verifies both the live and persisted balances; it never installs or adds any coins.
            MigrationVending.verifyImported(operation.move);
            File backup = MigrationStorage.child(
                MigrationStorage.localDirectory(),
                "wallet-recovery/" + operation.move.id + "-" + UUID.randomUUID());
            MigrationImporter.backupServerUtilities(operation.move, backup);
            Path pending = MigrationStorage.serverDirectory()
                .toPath()
                .resolve("cointcore/migration-pending.json");
            if (Files.isRegularFile(pending)) {
                Files.createDirectories(backup.toPath());
                Files.copy(
                    pending,
                    backup.toPath()
                        .resolve("migration-pending.json"),
                    StandardCopyOption.COPY_ATTRIBUTES);
            }
            operation.sender.addChatMessage(new ChatComponentText("§eРезервная копия SU: " + backup));
            MigrationImporter.finishTeam(operation, manifest, source, new ArrayList<>());
            MigrationVending.verifyImported(operation.move);
            return null;
        } catch (IOException | RuntimeException e) {
            return "Восстановление после проверки кошелька не завершено: " + e.getMessage();
        }
    }

    private static NBTTagCompound nbt(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return CompressedStreamTools.readCompressed(input);
        }
    }
}
