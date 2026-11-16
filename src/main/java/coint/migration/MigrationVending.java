package coint.migration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.world.WorldEvent;

import com.google.gson.JsonParser;
import com.gtnewhorizon.gtnhlib.teams.ITeamData;
import com.gtnewhorizon.gtnhlib.teams.Team;
import com.gtnewhorizon.gtnhlib.teams.TeamDataRegistry;
import com.gtnewhorizon.gtnhlib.teams.TeamDataSaver;
import com.gtnewhorizon.gtnhlib.teams.TeamManager;
import com.gtnewhorizon.gtnhlib.util.NBTJson;

import cpw.mods.fml.common.Loader;

/** Vending wallets live in GTNHLib teams, not in a player's file or the vending machine tile. */
final class MigrationVending {

    static final String FILE = "cointcore-migration/vending.dat";
    private static final String VM = "VM";

    private MigrationVending() {}

    private static Set<UUID> players(List<Map<String, Object>> players) {
        Set<UUID> ids = new HashSet<>();
        for (Map<String, Object> player : players) ids.add(
            UUID.fromString(
                player.get("uuid")
                    .toString()));
        return ids;
    }

    private static NBTTagList ids(Collection<UUID> ids) {
        NBTTagList list = new NBTTagList();
        ids.stream()
            .map(UUID::toString)
            .sorted()
            .forEach(id -> list.appendTag(new NBTTagString(id)));
        return list;
    }

    private static Set<UUID> ids(NBTTagCompound tag, String key) {
        Set<UUID> ids = new HashSet<>();
        NBTTagList list = tag.getTagList(key, 8);
        for (int i = 0; i < list.tagCount(); i++) ids.add(UUID.fromString(list.getStringTagAt(i)));
        return ids;
    }

    private static NBTTagCompound data(ITeamData data) throws IOException {
        if (data == null) throw new IOException("Не зарегистрированы данные кошелька VendingMachine");
        NBTTagCompound tag = new NBTTagCompound();
        data.writeToNBT(tag);
        return (NBTTagCompound) canonical(tag);
    }

    private static NBTTagCompound meaningful(String key, NBTTagCompound data) {
        NBTTagCompound copy = (NBTTagCompound) data.copy();
        if (!VM.equals(key)) return copy;
        if (copy.getCompoundTag("wallet")
            .hasNoTags()) copy.removeTag("wallet");
        NBTTagList personal = copy.getTagList("players", 10), keep = new NBTTagList();
        for (int i = 0; i < personal.tagCount(); i++) {
            NBTTagCompound player = (NBTTagCompound) personal.getCompoundTagAt(i)
                .copy();
            player.removeTag("uuid");
            if (player.getCompoundTag("wallet")
                .hasNoTags()) player.removeTag("wallet");
            if (player.getTagList("notificationQueue", 8)
                .tagCount() == 0) player.removeTag("notificationQueue");
            if (!player.hasNoTags()) keep.appendTag(personal.getCompoundTagAt(i));
        }
        if (keep.tagCount() == 0) copy.removeTag("players");
        else copy.setTag("players", keep);
        if (copy.getTagList("tradeHistory", 10)
            .tagCount() == 0) copy.removeTag("tradeHistory");
        return copy;
    }

    private static NBTBase canonical(NBTBase value) {
        if (value instanceof NBTTagCompound tag) {
            NBTTagCompound copy = new NBTTagCompound();
            for (Object key : tag.func_150296_c()) copy.setTag(key.toString(), canonical(tag.getTag(key.toString())));
            return copy;
        }
        if (value instanceof NBTTagList list) {
            List<NBTBase> values = new ArrayList<>();
            NBTTagList pending = (NBTTagList) list.copy();
            for (int i = pending.tagCount() - 1; i >= 0; i--) values.add(canonical(pending.removeTag(i)));
            values.sort(Comparator.comparing(Object::toString));
            NBTTagList copy = new NBTTagList();
            for (NBTBase item : values) copy.appendTag(item);
            return copy;
        }
        return value.copy();
    }

    private static NBTTagCompound team(Team team, boolean allData) throws IOException {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(
            "UUID",
            team.getTeamId()
                .toString());
        tag.setString("TeamName", team.getTeamName());
        tag.setTag("Members", ids(team.getMembers()));
        tag.setTag("Owners", ids(team.getOwners()));
        tag.setTag("Officers", ids(team.getOfficers()));
        NBTTagCompound values = new NBTTagCompound();
        for (Map.Entry<String, ITeamData> entry : team.getAllDataEntries()) {
            if (VM.equals(entry.getKey())) values.setTag(entry.getKey(), data(entry.getValue()));
            else if (allData && entry.getValue() != null) {
                // Other mods may use ordered NBT lists: preserve their backup byte-for-byte in meaning.
                NBTTagCompound raw = new NBTTagCompound();
                entry.getValue()
                    .writeToNBT(raw);
                values.setTag(entry.getKey(), raw);
            }
        }
        if (!values.hasKey(VM, 10)) throw new IOException("Нет кошелька VendingMachine у команды " + team.getTeamId());
        tag.setTag("TeamData", values);
        return tag;
    }

    static void preflight(MoveRecord move) throws IOException {
        if (move.vendingSnapshot != null && move.target.equals(coint.cluster.ClusterService.nodeId())) {
            if (!Loader.isModLoaded("vendingmachine")) throw new IOException("На цели нет VendingMachine");
            Set<UUID> players = new HashSet<>(
                move.players.stream()
                    .map(UUID::fromString)
                    .collect(Collectors.toList()));
            legacy(players);
            checkIncoming(decode(move.vendingSnapshot), players);
        }
        if (Loader.isModLoaded("vendingmachine")) capture(
            new HashSet<>(
                move.players.stream()
                    .map(UUID::fromString)
                    .collect(Collectors.toList())));
    }

    static void prepare(MoveRecord move) throws IOException {
        if (Loader.isModLoaded("vendingmachine")) move.vendingSnapshot = Base64.getEncoder()
            .encodeToString(
                CompressedStreamTools.compress(
                    capture(
                        new HashSet<>(
                            move.players.stream()
                                .map(UUID::fromString)
                                .collect(Collectors.toList())))));
    }

    private static NBTTagCompound decode(String encoded) throws IOException {
        try {
            return CompressedStreamTools.func_152457_a(
                Base64.getDecoder()
                    .decode(encoded),
                new NBTSizeTracker(2097152L));
        } catch (IllegalArgumentException e) {
            throw new IOException("Повреждён снимок VendingMachine", e);
        }
    }

    private static void legacy(Set<UUID> players) throws IOException {
        try {
            File root = (File) Class.forName("com.cubefury.vendingmachine.VMConfig")
                .getField("world_dir")
                .get(null);
            if (root == null) throw new IOException("VendingMachine ещё не загрузил кошельки");
            for (UUID id : players) if (new File(root, "tradeState/" + id + ".json").isFile()) throw new IOException(
                "Старый кошелёк VendingMachine " + id
                    + ": игрок должен сначала войти на эту ноду для обновления данных");
        } catch (ReflectiveOperationException e) {
            throw new IOException("Не поддерживается API кошельков VendingMachine", e);
        }
    }

    static NBTTagCompound capture(Set<UUID> players) throws IOException {
        legacy(players);
        NBTTagCompound result = new NBTTagCompound();
        result.setInteger("Format", 1);
        NBTTagList groups = new NBTTagList();
        for (Team team : TeamManager.getTeamMap()
            .values()) {
            if (Collections.disjoint(team.getMembers(), players)) continue;
            if (!players.containsAll(team.getMembers())) throw new IOException(
                "Общий кошелёк VendingMachine включает игроков вне переносимой команды: " + team.getTeamName());
            NBTTagCompound tag = team(team, false);
            validate(tag, players);
            groups.appendTag(tag);
        }
        result.setTag("Groups", groups);
        return (NBTTagCompound) canonical(result);
    }

    static void snapshot(File staging, List<Map<String, Object>> players, MoveRecord move) throws IOException {
        if (move != null && move.vendingSnapshot == null) return; // In-flight moves created by older builds keep old
                                                                  // behaviour.
        if (!Loader.isModLoaded("vendingmachine")) return;
        write(MigrationStorage.child(staging, FILE), capture(players(players)));
        if (move != null) {
            File retained = MigrationStorage.child(retained(move), FILE);
            NBTTagCompound snapshot = read(MigrationStorage.child(staging, FILE));
            if (move.vendingSnapshot != null && !snapshot.equals(decode(move.vendingSnapshot)))
                throw new IOException("Кошелёк VendingMachine изменился после предварительной проверки");
            if (retained.isFile() && !snapshot.equals(read(retained)))
                throw new IOException("Снимок VendingMachine этого переноса уже существует и отличается");
            if (!retained.isFile()) write(retained, snapshot);
        }
    }

    private static File retained(MoveRecord move) throws IOException {
        return MigrationStorage.child(MigrationStorage.localDirectory(), "move-snapshots/" + move.id);
    }

    private static void validate(NBTTagCompound group, Set<UUID> players) throws IOException {
        try {
            UUID.fromString(group.getString("UUID"));
            Set<UUID> members = ids(group, "Members");
            if (members.isEmpty() || !players.containsAll(members)
                || !members.containsAll(ids(group, "Owners"))
                || !members.containsAll(ids(group, "Officers")))
                throw new IOException("Чужая команда VendingMachine в архиве");
            if (!group.getCompoundTag("TeamData")
                .hasKey(VM, 10)) throw new IOException("Нет данных кошелька VendingMachine в архиве");
            NBTTagCompound vm = group.getCompoundTag("TeamData")
                .getCompoundTag(VM);
            NBTTagList personal = vm.getTagList("players", 10);
            Set<UUID> seen = new HashSet<>();
            wallet(vm.getCompoundTag("wallet"));
            for (int i = 0; i < personal.tagCount(); i++) {
                NBTTagCompound player = personal.getCompoundTagAt(i);
                UUID id = UUID.fromString(player.getString("uuid"));
                if (!members.contains(id) || !seen.add(id))
                    throw new IOException("Чужой/повторный личный кошелёк в архиве");
                wallet(player.getCompoundTag("wallet"));
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Повреждены владельцы кошелька VendingMachine", e);
        }
    }

    private static void wallet(NBTTagCompound wallet) throws IOException {
        Set<String> seen = new HashSet<>();
        NBTTagList coins = wallet.getTagList("currency", 10);
        for (int i = 0; i < coins.tagCount(); i++) {
            NBTTagCompound coin = coins.getCompoundTagAt(i);
            String currency = coin.getString("currency");
            if (currency.isEmpty() || !seen.add(currency) || !coin.hasKey("amount", 3) || coin.getInteger("amount") < 0)
                throw new IOException("Повреждён баланс VendingMachine");
        }
    }

    private static List<Team> targets(Set<UUID> members) {
        List<Team> teams = new ArrayList<>();
        for (Team team : TeamManager.getTeamMap()
            .values()) if (!Collections.disjoint(team.getMembers(), members)) teams.add(team);
        teams.sort(
            Comparator.comparing(
                t -> t.getTeamId()
                    .toString()));
        return teams;
    }

    static void check(File staging, Map<String, Object> manifest) throws IOException {
        File file = MigrationStorage.child(staging, FILE);
        if (!file.isFile()) {
            if (manifest.containsKey("vending")) throw new IOException("В архиве отсутствуют кошельки VendingMachine");
            return; // Old exports cannot supply missing balances, and must not clear them on the source.
        }
        if (!Loader.isModLoaded("vendingmachine")) throw new IOException("На цели нет VendingMachine");
        Set<UUID> players = players(MigrationImporter.list(manifest.get("players")));
        legacy(players);
        NBTTagCompound snapshot = read(file);
        checkIncoming(snapshot, players);
    }

    private static void checkIncoming(NBTTagCompound snapshot, Set<UUID> players) throws IOException {
        if (snapshot.getInteger("Format") != 1 || !snapshot.hasKey("Groups", 9))
            throw new IOException("Неизвестный формат кошельков VendingMachine");
        Set<UUID> assigned = new HashSet<>(), teamIds = new HashSet<>();
        NBTTagList groups = snapshot.getTagList("Groups", 10);
        for (int i = 0; i < groups.tagCount(); i++) {
            NBTTagCompound group = groups.getCompoundTagAt(i);
            validate(group, players);
            Set<UUID> members = ids(group, "Members");
            if (!Collections.disjoint(assigned, members) || !teamIds.add(UUID.fromString(group.getString("UUID"))))
                throw new IOException("Повторная команда/игрок VendingMachine в архиве");
            assigned.addAll(members);
            List<Team> existing = targets(members);
            Team byId = TeamManager.getTeamById(UUID.fromString(group.getString("UUID")));
            if (byId != null && !existing.contains(byId))
                throw new IOException("UUID команды VendingMachine занят на цели");
            for (Team team : existing) {
                if (!members.containsAll(team.getMembers())) throw new IOException(
                    "На цели кошелёк VendingMachine общий с другой командой: " + team.getTeamName());
                boolean sameGroup = existing.size() == 1 && members.equals(team.getMembers());
                for (Map.Entry<String, ITeamData> entry : team.getAllDataEntries()) {
                    if (sameGroup && !VM.equals(entry.getKey())) continue;
                    NBTTagCompound current = data(entry.getValue());
                    NBTTagCompound empty = data(TeamDataRegistry.construct(entry.getKey()));
                    // Equal amounts alone never prove that money was already imported.
                    if (!meaningful(entry.getKey(), current).equals(meaningful(entry.getKey(), empty)))
                        throw new IOException(
                            "На цели уже есть данные " + entry.getKey()
                                + " команды "
                                + team.getTeamName()
                                + ". Кошелёк не перезаписан.");
                }
            }
        }
    }

    static void install(File staging, Map<String, Object> manifest, File backup) throws IOException {
        check(staging, manifest);
        File file = MigrationStorage.child(staging, FILE);
        if (!file.isFile()) return;
        Set<UUID> players = players(MigrationImporter.list(manifest.get("players")));
        NBTTagCompound before = new NBTTagCompound();
        NBTTagList oldGroups = new NBTTagList();
        for (Team team : targets(players)) oldGroups.appendTag(team(team, true));
        before.setTag("Groups", oldGroups);
        File copy = MigrationStorage.child(backup, FILE);
        if (!copy.isFile()) write(copy, before);
        NBTTagList groups = read(file).getTagList("Groups", 10);
        List<Team> changed = new ArrayList<>();
        Set<UUID> removed = new HashSet<>();
        for (int i = 0; i < groups.tagCount(); i++) {
            NBTTagCompound group = groups.getCompoundTagAt(i);
            List<Team> existing = targets(ids(group, "Members"));
            Team target;
            if (existing.isEmpty()) {
                target = new Team(group.getString("TeamName"), UUID.fromString(group.getString("UUID")), false);
                try {
                    java.lang.reflect.Method init = Team.class.getDeclaredMethod("initializeData", String[].class);
                    init.setAccessible(true);
                    init.invoke(
                        target,
                        (Object) TeamDataRegistry.getRegisteredKeys()
                            .toArray(new String[0]));
                } catch (ReflectiveOperationException e) {
                    throw new IOException("Не поддерживается API команд GTNHLib", e);
                }
                for (UUID id : ids(group, "Owners")) target.addOwner(id);
                for (UUID id : ids(group, "Officers")) target.addOfficer(id);
                for (UUID id : ids(group, "Members")) target.addMember(id);
                if (!TeamManager.addTeamDeduplicated(target)) throw new IOException("Команда GTNHLib уже существует");
            } else {
                target = existing.get(0);
                for (int j = 1; j < existing.size(); j++) {
                    removed.add(
                        existing.get(j)
                            .getTeamId());
                    TeamManager.mergeTeams(target, existing.get(j));
                }
                for (UUID id : new HashSet<>(target.getMembers())) target.removeMember(id);
                for (UUID id : ids(group, "Owners")) target.addOwner(id);
                for (UUID id : ids(group, "Officers")) target.addOfficer(id);
                for (UUID id : ids(group, "Members")) target.addMember(id);
            }
            target.getData(VM)
                .readFromNBT(
                    (NBTTagCompound) group.getCompoundTag("TeamData")
                        .getCompoundTag(VM)
                        .copy());
            target.markDirty();
            changed.add(target);
        }
        flush(changed, removed);
    }

    static void retire(MoveRecord move, File backup) throws IOException {
        File file = MigrationStorage.child(retained(move), FILE);
        if (!file.isFile()) return;
        Set<UUID> players = new HashSet<>(
            move.players.stream()
                .map(UUID::fromString)
                .collect(Collectors.toList()));
        NBTTagCompound expected = read(file);
        checkSource(expected, capture(players), MigrationStorage.child(backup, FILE));
        File copy = MigrationStorage.child(backup, FILE);
        if (!copy.isFile()) write(copy, expected);
        List<Team> changed = targets(players);
        for (Team team : changed) {
            NBTTagCompound vm = data(team.getData(VM));
            clearWallets(vm);
            team.getData(VM)
                .readFromNBT(vm);
            team.markDirty();
        }
        flush(changed, Collections.emptySet());
    }

    static void checkUnchanged(MoveRecord move) throws IOException {
        File file = MigrationStorage.child(retained(move), FILE);
        if (file.isFile()) checkSource(
            read(file),
            capture(
                new HashSet<>(
                    move.players.stream()
                        .map(UUID::fromString)
                        .collect(Collectors.toList()))),
            MigrationStorage.child(MigrationStorage.localDirectory(), "retired/" + move.id + "/" + FILE));
    }

    private static void checkSource(NBTTagCompound expected, NBTTagCompound current, File backup) throws IOException {
        if (expected.equals(current)) return;
        if (backup.isFile() && expected.equals(read(backup))) {
            NBTTagList groups = expected.getTagList("Groups", 10), actual = current.getTagList("Groups", 10);
            if (groups.tagCount() == actual.tagCount()) {
                boolean valid = true;
                for (int i = 0; i < actual.tagCount(); i++) {
                    NBTTagCompound found = actual.getCompoundTagAt(i), original = null;
                    for (int j = 0; j < groups.tagCount(); j++) if (groups.getCompoundTagAt(j)
                        .getString("UUID")
                        .equals(found.getString("UUID"))) original = groups.getCompoundTagAt(j);
                    if (original == null) {
                        valid = false;
                        break;
                    }
                    if (original.equals(found)) continue;
                    NBTTagCompound cleared = (NBTTagCompound) original.copy();
                    clearWallets(
                        cleared.getCompoundTag("TeamData")
                            .getCompoundTag(VM));
                    if (!canonical(cleared).equals(found)) {
                        valid = false;
                        break;
                    }
                }
                if (valid) return;
            }
        }
        throw new IOException("Кошельки VendingMachine изменились после экспорта; исходные монеты сохранены");
    }

    private static void clearWallets(NBTTagCompound vm) {
        vm.setTag("wallet", new NBTTagCompound());
        NBTTagList personal = vm.getTagList("players", 10);
        for (int i = 0; i < personal.tagCount(); i++) personal.getCompoundTagAt(i)
            .setTag("wallet", new NBTTagCompound());
    }

    static void requireReceipt(MoveRecord move, String hash) throws IOException {
        if (!MigrationStorage.child(retained(move), FILE)
            .isFile()) return;
        File receipt = MigrationStorage
            .child(MigrationStorage.archives(), "team-moves/" + move.id + "/vending-imported");
        if (!receipt.isFile()
            || !hash.equals(new String(Files.readAllBytes(receipt.toPath()), java.nio.charset.StandardCharsets.UTF_8)))
            throw new IOException(
                "Нет подтверждения импорта кошельков VendingMachine на цели; исходные монеты сохранены");
    }

    static void verifyImported(MoveRecord move) throws IOException {
        if (move.vendingSnapshot == null) return;
        NBTTagList groups = decode(move.vendingSnapshot).getTagList("Groups", 10);
        List<Team> teams = new ArrayList<>();
        for (int i = 0; i < groups.tagCount(); i++) {
            NBTTagCompound group = groups.getCompoundTagAt(i);
            Set<UUID> members = ids(group, "Members");
            List<Team> existing = targets(members);
            if (existing.size() != 1 || !members.equals(
                existing.get(0)
                    .getMembers())
                || !data(
                    existing.get(0)
                        .getData(VM)).equals(
                            canonical(
                                group.getCompoundTag("TeamData")
                                    .getCompoundTag(VM))))
                throw new IOException("Кошелёк VendingMachine не совпадает со снимком: " + group.getString("TeamName"));
            teams.add(existing.get(0));
        }
        flush(teams, Collections.emptySet());
    }

    private static void flush(List<Team> teams, Set<UUID> removed) throws IOException {
        net.minecraft.world.WorldServer world = MinecraftServer.getServer()
            .worldServerForDimension(0);
        if (TeamDataSaver.INSTANCE == null || world == null) throw new IOException("Не загружены команды GTNHLib");
        TeamDataSaver.onWorldSave(new WorldEvent.Save(world));
        // GTNHLib logs failed deletions without throwing; a stale file would resurrect a second team on restart.
        for (UUID id : removed) {
            if (TeamManager.getTeamById(id) != null)
                throw new IOException("Поглощённая команда GTNHLib всё ещё загружена: " + id);
            Files.deleteIfExists(
                new File(
                    world.getSaveHandler()
                        .getWorldDirectory(),
                    "gtnhteams/" + id + ".json").toPath());
        }
        for (Team team : teams) {
            File file = new File(
                world.getSaveHandler()
                    .getWorldDirectory(),
                "gtnhteams/" + team.getTeamId() + ".json");
            try (Reader reader = Files.newBufferedReader(file.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                NBTTagCompound saved = (NBTTagCompound) NBTJson.toNbt(new JsonParser().parse(reader));
                if (!canonical(
                    saved.getCompoundTag("TeamData")
                        .getCompoundTag(VM)).equals(data(team.getData(VM))))
                    throw new IOException("Кошелёк VendingMachine не сохранился: " + team.getTeamId());
            }
        }
    }

    private static NBTTagCompound read(File file) throws IOException {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            return CompressedStreamTools.readCompressed(in);
        }
    }

    private static void write(File file, NBTTagCompound data) throws IOException {
        Files.createDirectories(
            file.toPath()
                .getParent());
        Path temp = Files.createTempFile(
            file.toPath()
                .getParent(),
            "vending-",
            ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                CompressedStreamTools.writeCompressed(data, out);
            }
            try {
                Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
