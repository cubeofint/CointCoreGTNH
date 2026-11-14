package coint.migration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraftforge.common.DimensionManager;

import com.google.gson.Gson;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.CointCore;
import coint.cluster.ClusterMoveHomes;
import coint.cluster.ClusterService;
import coint.commands.CommandMigrate;
import coint.player.TeamsManager;
import coint.supply.SupplyServerTasks;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import serverutils.data.ClaimedChunk;
import serverutils.data.ClaimedChunks;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;

/** One command, durable receipts at irreversible boundaries. Never retries an uncertain import. */
@EventBusSubscriber
public final class MigrationMove {

    private static final Gson JSON = new Gson();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "CointCore team move journal");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<String> handled = new HashSet<>();
    private static final Set<String> reportedErrors = new HashSet<>();
    private static long nextPoll, generation;
    private static boolean polling;
    private static String active, awaitingExport;

    private MigrationMove() {}

    public static void reset() {
        MigrationBatch.reset();
        MigrationConsent.reset();
        generation++;
        polling = false;
        active = awaitingExport = null;
        nextPoll = 0;
        handled.clear();
        reportedErrors.clear();
    }

    private static Path root() {
        return MigrationStorage.archives()
            .toPath()
            .resolve("team-moves");
    }

    private static Path directory(MoveRecord move) {
        return root().resolve(move.id);
    }

    private static boolean has(MoveRecord move, String name) {
        return Files.isRegularFile(directory(move).resolve(name));
    }

    private static String read(MoveRecord move, String name) throws IOException {
        return new String(Files.readAllBytes(directory(move).resolve(name)), StandardCharsets.UTF_8);
    }

    private static void put(MoveRecord move, String name, String text) throws IOException {
        Path directory = directory(move);
        Files.createDirectories(directory);
        Path temp = directory.resolve(name + ".tmp-" + UUID.randomUUID());
        Files.write(temp, text.getBytes(StandardCharsets.UTF_8));
        Files.move(temp, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE);
    }

    private static Path playerLock(String player) {
        return root().resolve("players/" + player + ".lock");
    }

    private static void reserve(MoveRecord move) throws IOException {
        Files.createDirectories(root().resolve("players"));
        List<Path> acquired = new ArrayList<>();
        try {
            List<String> players = new ArrayList<>(move.players);
            java.util.Collections.sort(players);
            for (String player : players) {
                Path lock = playerLock(player);
                Files.createFile(lock);
                acquired.add(lock);
                Files.write(lock, move.id.getBytes(StandardCharsets.UTF_8));
            }
            put(move, "request.json", JSON.toJson(move));
        } catch (IOException e) {
            for (Path lock : acquired) Files.deleteIfExists(lock);
            throw e;
        }
    }

    private static void unlock(MoveRecord move) throws IOException {
        for (String player : move.players) {
            Path lock = playerLock(player);
            if (Files.exists(lock)) {
                if (!move.id.equals(new String(Files.readAllBytes(lock), StandardCharsets.UTF_8)))
                    throw new IOException("Player lock changed: " + player);
                Files.delete(lock);
            }
        }
    }

    private static void offline(MoveRecord move) throws IOException {
        for (String uuid : move.players) if (MigrationImporter.online(UUID.fromString(uuid)) != null)
            throw new IOException("Вся команда должна быть офлайн: " + uuid);
    }

    public static String start(ICommandSender sender, ForgePlayer player, String target) {
        return start(sender, player, target, null, null);
    }

    static boolean busy() {
        return active != null || MigrationExporter.isRunning() || MigrationImporter.isRunning();
    }

    static String start(ICommandSender sender, ForgePlayer player, String target, String joinTeam, String joinOwner) {
        if (!CommandMigrate.allowed(sender)) return null;
        if (player == null) {
            tell(sender, "§cПеренос не начат: профиль игрока не найден");
            return null;
        }
        if (!CointConfig.cluster.migrationEnabled || !ClusterService.isRunning()) {
            tell(sender, "§cНужны cluster, migrationEnabled и общая migrationSharedDirectory.");
            return null;
        }
        if (active != null || MigrationExporter.isRunning() || MigrationImporter.isRunning()) {
            tell(sender, "§cНа этой ноде уже выполняется миграция.");
            return null;
        }
        if (!player.hasTeam() && joinTeam == null) {
            tell(sender, "§cУ игрока нет команды SU.");
            return null;
        }
        MoveRecord move = new MoveRecord();
        move.id = UUID.randomUUID()
            .toString();
        move.source = ClusterService.nodeId();
        move.target = target;
        move.actor = sender.getCommandSenderName();
        move.team = player.hasTeam() ? player.team.getId() : "unassigned";
        move.joinTeam = joinTeam;
        move.joinOwner = joinOwner;
        for (ForgePlayer member : move.joining() ? java.util.Collections.singletonList(player)
            : player.team.getMembers()) {
            move.players.add(
                member.getId()
                    .toString());
            move.names.add(member.getName());
        }
        if (!move.joining()) move.dimensions.addAll(MigrationExporter.findTeamDimensions(player.team));
        for (ClaimedChunk claim : move.joining() ? java.util.Collections.<ClaimedChunk>emptyList()
            : ClaimedChunks.instance.getTeamChunks(player.team, OptionalInt.empty(), true)) {
            WorldServer world = DimensionManager.getWorld(claim.getPos().dim);
            boolean forced = claim.isLoaded()
                || (world != null && net.minecraftforge.common.ForgeChunkManager.getPersistentChunksFor(world)
                    .containsKey(new net.minecraft.world.ChunkCoordIntPair(claim.getPos().posX, claim.getPos().posZ)));
            move.claims.add(new int[] { claim.getPos().dim, claim.getPos().posX, claim.getPos().posZ, forced ? 1 : 0 });
        }
        try {
            if (!move.joining()) {
                move.stations.addAll(MigrationStations.discover(player.team));
                for (MigrationStations.Station station : move.stations) {
                    if (move.dimensions.contains(station.id))
                        throw new IOException("Конфликт персоналки и станции " + station.id);
                    move.dimensions.add(station.id);
                }
            }
            move.validate();
            File sourceWorld = MigrationStorage.session().world;
            for (String uuid : move.players) if (!MigrationStorage.child(sourceWorld, "playerdata/" + uuid + ".dat")
                .isFile()) throw new IOException("Нет сохранённых данных игрока " + uuid);
            for (int dim : move.dimensions) if (MigrationStations.get(move, dim) == null
                && !MigrationStorage.child(sourceWorld, "PERSONAL_DIM_" + dim + "/personalspace_metadata.cfg")
                    .isFile())
                throw new IOException("Нет метаданных персоналки " + dim);
            offline(move);
            MigrationChunks.checkClaims(move, false);
            MigrationGlobalData.preflight(move);
            reserve(move);
        } catch (IOException | RuntimeException e) {
            tell(sender, "§cПеренос не начат: " + e.getMessage());
            return null;
        }
        active = move.id;
        long session = generation;
        ClusterMoveHomes.check(move, () -> {
            if (session != generation) return;
            try {
                put(move, "checked", "Destination and routing checked");
                active = null;
                tell(
                    sender,
                    "§eПеренос " + move.id
                        + " принят: "
                        + move.source
                        + " -> "
                        + move.target
                        + ". /migrate move-status "
                        + move.id);
            } catch (IOException e) {
                fail(move, e);
                active = null;
            }
        }, error -> {
            if (session != generation) return;
            fail(move, new IOException(error));
            active = null;
            tell(sender, "§cПеренос остановлен: " + error);
        });
        return move.id;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !CointConfig.cluster.migrationEnabled
            || !ClusterService.isRunning()
            || !Universe.loaded()
            || polling
            || System.currentTimeMillis() < nextPoll) return;
        nextPoll = System.currentTimeMillis() + 2000;
        polling = true;
        if (awaitingExport != null && !MigrationExporter.isRunning()) {
            active = null;
            awaitingExport = null;
        }
        long session = generation;
        String node = ClusterService.nodeId();
        Set<String> ignored = new HashSet<>(handled);
        IO.execute(() -> {
            List<MoveRecord> records = new ArrayList<>();
            String failure = null;
            try {
                File[] directories = root().toFile()
                    .listFiles(File::isDirectory);
                if (directories != null) for (File directory : directories) {
                    if (ignored.contains(directory.getName())) continue;
                    Path request = directory.toPath()
                        .resolve("request.json");
                    if (!Files.isRegularFile(request)) continue;
                    MoveRecord move = JSON
                        .fromJson(new String(Files.readAllBytes(request), StandardCharsets.UTF_8), MoveRecord.class);
                    move.validate();
                    if (!directory.getName()
                        .equals(move.id)) throw new IOException("Move directory mismatch");
                    if (node.equals(move.source) || node.equals(move.target)) records.add(move);
                }
            } catch (IOException | RuntimeException e) {
                failure = e.toString();
            }
            String error = failure;
            SupplyServerTasks.run(() -> {
                if (session != generation) return;
                polling = false;
                if (error != null) {
                    CointCore.LOG.error("[TeamMove] Cannot read journal: {}", error);
                    return;
                }
                for (MoveRecord move : records) {
                    if (handled.contains(move.id)) continue;
                    try {
                        process(move);
                    } catch (Exception e) {
                        fail(move, e);
                    }
                }
            });
        });
    }

    private static void process(MoveRecord move) throws Exception {
        boolean target = ClusterService.nodeId()
            .equals(move.target);
        if (has(move, "done")) {
            if (!read(move, "done").equals(move.target + ":" + archiveHash(move)))
                throw new IOException("Повреждена квитанция завершения");
            Path completed = MigrationStorage.serverDirectory()
                .toPath()
                .resolve("cointcore/finished-team-moves/" + move.id);
            if (Files.isRegularFile(completed)) {
                unlockCompleted(move);
                handled.add(move.id);
                return;
            }
            if (target) {
                MigrationGuard.release(move.id);
                MigrationChunks.activate(move);
            }
            ClusterMoveHomes.refresh(move);
            MigrationExporter.saveEverything(MinecraftServer.getServer());
            Files.createDirectories(completed.getParent());
            Files.write(completed, move.target.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            unlockCompleted(move);
            handled.add(move.id);
            CointCore.LOG.info("[TeamMove] Completed {}: {} -> {} ({})", move.id, move.source, move.target, move.names);
            notifyActor(
                move,
                "§aКоманда перенесена на " + move.target
                    + ": "
                    + move.names
                    + ". Прогрузка источника снята. ID: "
                    + move.id);
            return;
        }
        if (has(move, "error")) {
            if (!reportedErrors.add(move.id)) return;
            CointCore.LOG
                .error("[TeamMove] {} stopped: {}. Inspect /migrate move-status", move.id, read(move, "error"));
            notifyActor(move, "§cПеренос " + move.id + " остановлен: " + read(move, "error"));
            if (!has(move, "source-start") && !has(move, "importing")) unlock(move);
            return;
        }
        reportedErrors.remove(move.id);
        if (!target && !has(move, "source-start")
            && !has(move, "approved")
            && System.currentTimeMillis() - move.createdAt > 120000)
            throw new IOException(
                "Цель не подтвердила запрос за 2 минуты. Проверьте migrationEnabled и общую migrationSharedDirectory на обеих нодах.");
        if (!has(move, "checked") || active != null || MigrationExporter.isRunning() || MigrationImporter.isRunning())
            return;
        if (target && !has(move, "approved")) {
            offline(move);
            MigrationChunks.checkClaims(move, true);
            MigrationGlobalData.preflight(move);
            put(move, "approved", "Claims checked on " + move.target);
        } else if (!target && has(move, "approved")
            && !has(move, "source-start")
            && !has(move, "archive.json")
            && !has(move, "imported")) {
                offline(move);
                MigrationChunks.checkClaims(move, false);
                MigrationChunks.materialize(move);
                put(move, "source-start", "Source frozen before export");
                MigrationGuard.freeze(move);
                MigrationChunks.unforce(move);
            } else if (!target && has(move, "source-start")
                && !has(move, "exporting")
                && !has(move, "archive.json")
                && !has(move, "imported")) {
                    offline(move);
                    MigrationGuard.freeze(move);
                    MigrationChunks.unforce(move);
                    if (!MigrationChunks.drainSource(move)) return;
                    MigrationExporter.saveEverything(MinecraftServer.getServer());
                    put(move, "exporting", "Source worlds and external chunks unloaded before snapshot");
                    active = move.id;
                    awaitingExport = move.id;
                    ForgePlayer player = Universe.get()
                        .getPlayer(UUID.fromString(move.players.get(0)));
                    String error = MigrationExporter
                        .export(MinecraftServer.getServer(), player, archive -> archive(move, archive), move);
                    if (error != null) {
                        active = null;
                        throw new IOException(error);
                    }
                } else if (target && has(move, "archive.json")
                    && !has(move, "target-start")
                    && !has(move, "importing")
                    && !has(move, "imported")) {
                        offline(move);
                        MigrationChunks.checkClaims(move, true);
                        MigrationChunks.materialize(move);
                        put(move, "target-start", "Receiving worlds frozen before unpacking");
                        MigrationGuard.freeze(move);
                        MigrationChunks.unforce(move);
                    } else
                    if (target && has(move, "target-start") && !has(move, "importing") && !has(move, "imported")) {
                        offline(move);
                        MigrationChunks.checkClaims(move, true);
                        MigrationGuard.freeze(move);
                        MigrationChunks.unforce(move);
                        if (!MigrationChunks.drainSource(move)) return;
                        MigrationExporter.saveEverything(MinecraftServer.getServer());
                        Archive archive = JSON.fromJson(read(move, "archive.json"), Archive.class);
                        if (!MigrationStorage.validArchiveName(archive.name) || !archive.sha256.matches("[a-f0-9]{64}"))
                            throw new IOException("Invalid archive receipt");
                        put(move, "importing", "Import started once; never retry blindly after a crash");
                        active = move.id;
                        long session = generation;
                        String error = MigrationImporter
                            .start(MinecraftServer.getServer(), archive.name, archive.sha256, move.source, success -> {
                                if (session != generation) return;
                                active = null;
                                try {
                                    if (!success) throw new IOException(
                                        "Импорт не завершён; исходные данные сохранены, вход заблокирован");
                                    put(move, "imported", archive.sha256);
                                } catch (IOException e) {
                                    fail(move, e);
                                }
                            }, move);
                        if (error != null) {
                            active = null;
                            throw new IOException(error);
                        }
                    } else if (!target && has(move, "imported") && !has(move, "retired")) {
                        confirmedImport(move);
                        if (retire(move)) put(move, "retired", archiveHash(move));
                    } else if (target && has(move, "retired")) {
                        if (move.joining()) MigrationJoining.checkTarget(move);
                        confirmedImport(move);
                        if (!read(move, "retired").equals(archiveHash(move)))
                            throw new IOException("Повреждена квитанция отключения источника");
                        active = move.id;
                        long session = generation;
                        ClusterMoveHomes.commit(move, () -> {
                            if (session != generation) return;
                            active = null;
                            try {
                                put(move, "done", move.target + ":" + archiveHash(move));
                            } catch (IOException e) {
                                fail(move, e);
                            }
                        }, error -> {
                            if (session == generation) {
                                active = null;
                                fail(move, new IOException(error));
                            }
                        });
                    } else if (!target && has(move, "exporting") && !has(move, "archive.json")) {
                        throw new IOException(
                            "Экспорт прерван. Нужна проверка журнала, автоматическое повторение запрещено.");
                    } else if (target && has(move, "importing") && !has(move, "imported")) {
                        throw new IOException(
                            "Импорт прерван. Нужна проверка заменённых файлов, автоматическое повторение запрещено.");
                    }
    }

    private static void archive(MoveRecord move, String name) {
        awaitingExport = null;
        long session = generation;
        IO.execute(() -> {
            try {
                Archive archive = new Archive();
                archive.name = name;
                archive.sha256 = MigrationStorage.sha256(new File(MigrationStorage.archives(), name).toPath());
                SupplyServerTasks.run(() -> {
                    if (session != generation) return;
                    active = null;
                    try {
                        put(move, "archive.json", JSON.toJson(archive));
                    } catch (IOException e) {
                        fail(move, e);
                    }
                });
            } catch (IOException e) {
                SupplyServerTasks.run(() -> {
                    if (session == generation) {
                        active = null;
                        fail(move, e);
                    }
                });
            }
        });
    }

    private static boolean retire(MoveRecord move) throws Exception {
        offline(move);
        MigrationChunks.unforce(move);
        boolean unloaded = true;
        for (int dim : move.dimensions) {
            WorldServer world = DimensionManager.getWorld(dim);
            if (world != null) {
                if (!world.playerEntities.isEmpty()) throw new IOException("В исходной персоналке есть игроки: " + dim);
                DimensionManager.unloadWorld(dim);
                unloaded = false;
            }
        }
        if (!unloaded) return false;
        MigrationExporter.saveEverything(MinecraftServer.getServer());
        File root = MigrationStorage.session().world;
        File backup = MigrationStorage.child(MigrationStorage.localDirectory(), "retired/" + move.id);
        if (!MigrationGlobalData.unchanged(move)) throw new IOException(
            "Глобальные данные команды изменились после снимка. Источник не очищен; нужна сверка данных, повторная выдача/покупка во время переноса не потеряна.");
        if (!new File(backup, MigrationGlobalData.FILE).isFile()) MigrationGlobalData.write(backup, move);
        MigrationGlobalData.apply(MigrationGlobalData.read(backup), move, true);
        RegionFileCache.clearRegionFileReferences();
        for (int dim : move.dimensions) {
            if (MigrationStations.get(move, dim) != null) {
                MigrationStations.forget(dim);
                archiveFile(root, backup, MigrationStations.folder(dim));
                archiveFile(root, backup, MigrationStations.metadata(dim));
                archiveFile(root, backup, MigrationStations.metadata(dim) + "_old");
                continue;
            }
            archiveFile(root, backup, "PERSONAL_DIM_" + dim);
            if (DimensionManager.isDimensionRegistered(dim)) DimensionManager.unregisterDimension(dim);
            TeamsManager.get()
                .retirePDim(dim);
        }
        for (int i = 0; i < move.players.size(); i++) {
            String uuid = move.players.get(i), name = move.names.get(i);
            archiveFile(root, backup, "playerdata/" + uuid + ".dat");
            archiveFile(root, backup, "playerdata/" + uuid + ".dat_old");
            File[] files = new File(root, "playerdata").listFiles(
                (dir, n) -> n.equalsIgnoreCase(name + ".thaum") || n.equalsIgnoreCase(name + ".thaumback")
                    || n.equalsIgnoreCase(name + ".baub")
                    || n.equalsIgnoreCase(name + ".baubback"));
            if (files != null) for (File file : files) archiveFile(root, backup, "playerdata/" + file.getName());
            ForgePlayer player = Universe.get()
                .getPlayer(UUID.fromString(uuid));
            if (player != null) {
                player.clearCache();
                player.markDirty();
            }
        }
        ForgeTeam team = Universe.get()
            .getTeam(move.team);
        if (move.joining()) MigrationJoining.retireMember(move);
        else if (team != null && team.isValid()) TeamsManager.get()
            .removeDimBind(team);
        for (int[] c : move.claims) if (move.dimensions.contains(c[0]))
            ClaimedChunks.instance.removeChunk(new serverutils.lib.math.ChunkDimPos(c[1], c[2], c[0]));
        MigrationExporter.saveEverything(MinecraftServer.getServer());
        return true;
    }

    private static void archiveFile(File root, File backup, String relative) throws IOException {
        Path from = MigrationStorage.child(root, relative)
            .toPath(),
            to = MigrationStorage.child(backup, relative)
                .toPath();
        if (!Files.exists(from)) return;
        if (Files.exists(to)) throw new IOException("Архив источника уже содержит другой файл: " + relative);
        Files.createDirectories(to.getParent());
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    }

    static void validateManifest(MoveRecord move, Map<String, Object> manifest) throws IOException {
        Map<String, Object> team = MigrationImporter.map(manifest.get("team"));
        Set<String> players = new HashSet<>(), claims = new HashSet<>(), exportedClaims = new HashSet<>(),
            external = new HashSet<>(), exportedExternal = new HashSet<>();
        for (Map<String, Object> player : MigrationImporter.list(manifest.get("players")))
            players.add(MigrationImporter.str(player.get("uuid")));
        Set<Integer> dims = new HashSet<>();
        for (Map<String, Object> dim : MigrationImporter.list(manifest.get("personalDimensions"))) {
            if (dim.containsKey("missing")) throw new IOException("В архиве отсутствует персоналка");
            dims.add((int) MigrationImporter.num(dim.get("dimension")));
        }
        for (int[] c : move.claims) {
            claims.add(Arrays.toString(c));
            if (!move.dimensions.contains(c[0])) external.add(Arrays.toString(c));
        }
        if (move.joining()) {
            if (team != null || !players.equals(new HashSet<>(move.players))
                || !dims.isEmpty()
                || !MigrationChunks.external(manifest)
                    .isEmpty()
                || !move.joinTeam.equals(MigrationImporter.str(manifest.get("joinTeam")))
                || !move.joinOwner.equals(MigrationImporter.str(manifest.get("joinOwner"))))
                throw new IOException("Архив не соответствует персональному переносу");
            return;
        }
        if (team == null) throw new IOException("В архиве нет команды");
        if (team.get("claims") instanceof List<?>list)
            for (Object c : list) exportedClaims.add(Arrays.toString(MigrationImporter.ints(c)));
        for (int[] c : MigrationChunks.external(manifest)) exportedExternal.add(Arrays.toString(c));
        if (!move.team.equals(MigrationImporter.str(team.get("id"))) || !players.equals(new HashSet<>(move.players))
            || !dims.equals(new HashSet<>(move.dimensions))
            || !claims.equals(exportedClaims)
            || !external.equals(exportedExternal))
            throw new IOException("Архив не соответствует команде/чанкам запроса переноса");
    }

    public static void status(ICommandSender sender, String id) {
        try {
            if (!UUID.fromString(id)
                .toString()
                .equals(id)) throw new IOException("Invalid ID");
            MoveRecord move = JSON.fromJson(
                new String(Files.readAllBytes(root().resolve(id + "/request.json")), StandardCharsets.UTF_8),
                MoveRecord.class);
            move.validate();
            tell(sender, "§e" + id + " " + move.source + " -> " + move.target + " " + move.names);
            for (String step : new String[] { "checked", "approved", "source-start", "exporting", "archive.json",
                "target-start", "importing", "imported", "retired", "done", "error" })
                if (has(move, step)) tell(sender, "§7" + step + ": " + read(move, step));
        } catch (IOException | RuntimeException e) {
            tell(sender, "§cЖурнал недоступен: " + e.getMessage());
        }
    }

    private static void fail(MoveRecord move, Exception error) {
        CointCore.LOG.error("[TeamMove] {} stopped; source data retained", move.id, error);
        try {
            if (!has(move, "error")) put(move, "error", error.toString());
        } catch (IOException e) {
            CointCore.LOG.error("[TeamMove] Cannot persist error {}", move.id, e);
        }
    }

    private static void unlockCompleted(MoveRecord move) throws IOException {
        for (String player : move.players) {
            Path lock = playerLock(player);
            if (Files.isRegularFile(lock)
                && move.id.equals(new String(Files.readAllBytes(lock), StandardCharsets.UTF_8))) Files.delete(lock);
        }
    }

    private static void tell(ICommandSender sender, String text) {
        if (!(sender instanceof net.minecraft.entity.player.EntityPlayerMP) && text.startsWith("§c"))
            CointCore.LOG.warn("[TeamMove] {}", text);
        sender.addChatMessage(new net.minecraft.util.ChatComponentText(text));
    }

    private static void notifyActor(MoveRecord move, String text) {
        net.minecraft.entity.player.EntityPlayerMP player = MinecraftServer.getServer()
            .getConfigurationManager()
            .func_152612_a(move.actor);
        if (player != null) tell(player, text);
    }

    public static void resume(ICommandSender sender, String id) {
        try {
            if (!UUID.fromString(id)
                .toString()
                .equals(id)) throw new IOException("Invalid ID");
            MoveRecord move = JSON.fromJson(
                new String(Files.readAllBytes(root().resolve(id + "/request.json")), StandardCharsets.UTF_8),
                MoveRecord.class);
            move.validate();
            if (!ClusterService.nodeId()
                .equals(move.source)
                && !ClusterService.nodeId()
                    .equals(move.target))
                throw new IOException("Не та нода");
            if (!has(move, "imported")) throw new IOException(
                "Нет подтверждённого импорта. Нужна ручная проверка файлов, повторный импорт небезопасен.");
            if (has(move, "done")) throw new IOException("Перенос уже завершён");
            confirmedImport(move);
            Files.deleteIfExists(directory(move).resolve("error"));
            reportedErrors.remove(id);
            tell(sender, "§eПродолжаю отключение источника и смену домашнего сервера. Повторного импорта не будет.");
        } catch (IOException | RuntimeException e) {
            tell(sender, "§cПродолжение невозможно: " + e.getMessage());
        }
    }

    /** Explicit console attestation after independent inspection; never repeats the file import. */
    public static void finishReviewedImport(ICommandSender sender, String id) {
        if (sender != MinecraftServer.getServer()) {
            tell(sender, "§cТолько из консоли целевого сервера после проверки файлов импорта.");
            return;
        }
        try {
            if (!CointConfig.cluster.migrationEnabled || !ClusterService.isRunning())
                throw new IOException("Кластер и миграция должны быть включены");
            if (!UUID.fromString(id)
                .toString()
                .equals(id)) throw new IOException("Invalid ID");
            if (active != null || MigrationImporter.isRunning() || MigrationExporter.isRunning())
                throw new IOException("Другая операция ещё выполняется");
            MoveRecord move = JSON.fromJson(
                new String(Files.readAllBytes(root().resolve(id + "/request.json")), StandardCharsets.UTF_8),
                MoveRecord.class);
            move.validate();
            if (!id.equals(move.id)) throw new IOException("Move ID mismatch");
            checkReviewedImport(move);
            String hash = archiveHash(move);
            Archive archive = JSON.fromJson(read(move, "archive.json"), Archive.class);
            Path zip = MigrationStorage.archives()
                .toPath()
                .resolve(archive.name);
            active = id;
            long session = generation;
            tell(sender, "§eПроверяю SHA-256 архива. Файлы мира повторно импортироваться не будут.");
            IO.execute(() -> {
                String failure = null;
                try {
                    if (!hash.equals(MigrationStorage.sha256(zip)))
                        throw new IOException("SHA-256 архива не совпадает");
                } catch (IOException | RuntimeException e) {
                    failure = e.toString();
                }
                String error = failure;
                SupplyServerTasks.run(() -> {
                    if (session != generation) return;
                    try {
                        if (error != null) throw new IOException(error);
                        checkReviewedImport(move);
                        if (!hash.equals(archiveHash(move))) throw new IOException("Квитанция архива изменилась");
                        MigrationPending.finishReviewedEpochs(move);
                        MigrationExporter.saveEverything(MinecraftServer.getServer());
                        if (!has(move, "reviewed-import")) put(
                            move,
                            "reviewed-import",
                            "Console confirmed independent file review; epochs repaired and target saved. SHA-256="
                                + hash);
                        put(move, "imported", hash);
                        // Keep error and all freezes until the administrator explicitly resumes the source.
                        tell(sender, "§aИмпорт сохранён и подтверждён. На исходной ноде: migrate move-resume " + id);
                    } catch (Exception e) {
                        CointCore.LOG.error("[TeamMove] Reviewed import recovery failed for {}", id, e);
                        tell(sender, "§cПодтверждение импорта не выполнено: " + e.getMessage());
                    } finally {
                        active = null;
                    }
                });
            });
        } catch (IOException | RuntimeException e) {
            tell(sender, "§cВосстановление невозможно: " + e.getMessage());
        }
    }

    private static void checkReviewedImport(MoveRecord move) throws IOException {
        if (!ClusterService.nodeId()
            .equals(move.target)) throw new IOException("Нужна целевая нода");
        for (String step : new String[] { "source-start", "target-start", "approved", "importing", "error" })
            if (!has(move, step)) throw new IOException("Отсутствует этап " + step);
        for (String step : new String[] { "imported", "retired", "done" })
            if (has(move, step)) throw new IOException("Этап уже выполнен: " + step);
        offline(move);
        Path freeze = MigrationStorage.serverDirectory()
            .toPath()
            .resolve("cointcore/migration-freezes/" + move.id + ".json");
        MoveRecord local = JSON
            .fromJson(new String(Files.readAllBytes(freeze), StandardCharsets.UTF_8), MoveRecord.class);
        if (!JSON.toJson(move)
            .equals(JSON.toJson(local))) throw new IOException("Локальная заморозка не совпадает");
        for (String player : move.players)
            if (!move.id.equals(new String(Files.readAllBytes(playerLock(player)), StandardCharsets.UTF_8)))
                throw new IOException("Блокировка участника не совпадает");
        for (int dim : move.dimensions) if (!MigrationGuard.dimension(dim) || DimensionManager.getWorld(dim) != null)
            throw new IOException("Персоналка должна оставаться замороженной и выгруженной: " + dim);
        for (int[] c : move.claims) if (!move.dimensions.contains(c[0]))
            throw new IOException("Этот режим восстановления не поддерживает внешние чанки");
    }

    private static String archiveHash(MoveRecord move) throws IOException {
        Archive archive = JSON.fromJson(read(move, "archive.json"), Archive.class);
        if (archive == null || !MigrationStorage.validArchiveName(archive.name)
            || archive.sha256 == null
            || !archive.sha256.matches("[a-f0-9]{64}")) throw new IOException("Повреждена квитанция архива");
        return archive.sha256;
    }

    private static void confirmedImport(MoveRecord move) throws IOException {
        if (!read(move, "imported").equals(archiveHash(move)))
            throw new IOException("Нет корректной квитанции успешного импорта");
    }

    private static final class Archive {

        String name, sha256;
    }
}
