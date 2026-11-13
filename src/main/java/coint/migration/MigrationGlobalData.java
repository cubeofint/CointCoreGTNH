package coint.migration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldSavedData;

import cpw.mods.fml.common.Loader;

/** Per-owner global data only. Never replaces a whole mod's shared world file. */
final class MigrationGlobalData {

    static final String FILE = "cointcore-migration/global-data.dat";
    private static final String SPACE = "gregtech.common.misc.spaceprojects.SpaceProjectManager";
    private static final String SPACE_SAVE = "gregtech.common.misc.spaceprojects.SpaceProjectWorldSavedData";
    private static final String ENERGY = "gregtech.common.misc.WirelessNetworkManager";
    private static final String ENDER = "codechicken.enderstorage.api.EnderStorageManager";

    private MigrationGlobalData() {}

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        Class<?> type = object instanceof Class<?>c ? c : object.getClass();
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object instanceof Class<?> ? null : object);
    }

    @SuppressWarnings("unchecked")
    private static Set<UUID> leaders(MoveRecord move) throws ReflectiveOperationException, IOException {
        Class<?> space = Class.forName(SPACE);
        Set<UUID> members = new HashSet<>(), leaders = new HashSet<>();
        for (String id : move.players) members.add(UUID.fromString(id));
        for (UUID id : members) {
            UUID leader = (UUID) space.getMethod("getLeader", UUID.class)
                .invoke(null, id);
            Collection<UUID> group = (Collection<UUID>) space.getMethod("getTeamMembers", UUID.class)
                .invoke(null, leader);
            if (!members.contains(leader) || !members.containsAll(group)) throw new IOException(
                "Группа wireless EU/космических проектов включает игроков другой команды: " + leader);
            leaders.add(leader);
        }
        return leaders;
    }

    static void preflight(MoveRecord move) throws IOException {
        try {
            if (Loader.isModLoaded("gregtech")) leaders(move);
        } catch (ReflectiveOperationException e) {
            throw new IOException("Не поддерживается API global data GregTech", e);
        }
    }

    private static boolean owner(MoveRecord move, String owner) {
        for (String name : move.names) if (name.equalsIgnoreCase(owner)) return true;
        return move.players.contains(owner);
    }

    static NBTTagCompound capture(MoveRecord move) throws IOException {
        NBTTagCompound result = new NBTTagCompound();
        result.setString("ChunkBonus", coint.chunkbonus.ChunkBonus.migrationSnapshot(move.names));
        try {
            if (Loader.isModLoaded("AWWayofTime")) {
                NBTTagCompound networks = new NBTTagCompound();
                for (String name : move.names) {
                    NBTTagCompound network = new NBTTagCompound();
                    network.setInteger("Essence", BloodMagicData.essence(name));
                    network.setInteger("Orb", BloodMagicData.orb(name));
                    networks.setTag(name, network);
                }
                result.setTag("BloodMagic", networks);
            }
            if (Loader.isModLoaded("gregtech")) {
                Set<UUID> leaders = leaders(move);
                Class<?> space = Class.forName(SPACE), energy = Class.forName(ENERGY);
                NBTTagCompound balances = new NBTTagCompound(), teams = new NBTTagCompound();
                for (UUID leader : leaders) balances.setString(
                    leader.toString(),
                    energy.getMethod("getUserEU", UUID.class)
                        .invoke(null, leader)
                        .toString());
                for (String id : move.players) teams.setString(
                    id,
                    space.getMethod("getLeader", UUID.class)
                        .invoke(null, UUID.fromString(id))
                        .toString());
                result.setTag("EU", balances);
                result.setTag("GTTeams", teams);
                Field projects = space.getField("spaceTeamProjects");
                Map<?, ?> all = (Map<?, ?>) projects.get(null);
                Map<Object, Object> selected = new HashMap<>();
                for (UUID leader : leaders) if (all.containsKey(leader)) selected.put(leader, all.get(leader));
                Object gson = field(Class.forName(SPACE_SAVE), "GSON_SPACE_PROJECT");
                String json = (String) gson.getClass()
                    .getMethod("toJson", Object.class, java.lang.reflect.Type.class)
                    .invoke(gson, selected, projects.getGenericType());
                result.setString("GTProjects", json);
            }
            if (Loader.isModLoaded("EnderStorage")) {
                Object manager = Class.forName(ENDER)
                    .getMethod("instance", boolean.class)
                    .invoke(null, false);
                saveEnder(manager);
                NBTTagCompound all = (NBTTagCompound) field(manager, "saveTag"), selected = new NBTTagCompound();
                for (Object raw : all.func_150296_c()) {
                    String key = raw.toString();
                    String[] parts = key.split("\\|", -1);
                    if (parts.length == 3 && owner(move, parts[1])) selected.setTag(
                        key,
                        all.getTag(key)
                            .copy());
                }
                result.setTag("EnderStorage", selected);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IOException("Не удалось сохранить глобальные данные команды", e);
        }
        return result;
    }

    static void write(File staging, MoveRecord move) throws IOException {
        File file = MigrationStorage.child(staging, FILE);
        Files.createDirectories(
            file.toPath()
                .getParent());
        try (OutputStream output = Files.newOutputStream(file.toPath())) {
            CompressedStreamTools.writeCompressed(capture(move), output);
        }
    }

    static NBTTagCompound read(File staging) throws IOException {
        try (InputStream input = Files.newInputStream(
            MigrationStorage.child(staging, FILE)
                .toPath())) {
            return CompressedStreamTools.readCompressed(input);
        }
    }

    static void retain(File staging, MoveRecord move) throws IOException {
        File root = retained(move);
        File copy = MigrationStorage.child(root, FILE);
        Files.createDirectories(
            copy.toPath()
                .getParent());
        Files.copy(
            MigrationStorage.child(staging, FILE)
                .toPath(),
            copy.toPath());
    }

    private static File retained(MoveRecord move) throws IOException {
        return MigrationStorage.child(MigrationStorage.localDirectory(), "move-snapshots/" + move.id);
    }

    static boolean unchanged(MoveRecord move) throws IOException {
        return sameSnapshot(read(retained(move)), capture(move));
    }

    static boolean sameSnapshot(NBTTagCompound left, NBTTagCompound right) {
        NBTTagCompound a = (NBTTagCompound) left.copy(), b = (NBTTagCompound) right.copy();
        long now = System.currentTimeMillis();
        a.setString("ChunkBonus", coint.chunkbonus.ChunkBonus.migrationNormalized(a.getString("ChunkBonus"), now));
        b.setString("ChunkBonus", coint.chunkbonus.ChunkBonus.migrationNormalized(b.getString("ChunkBonus"), now));
        return a.equals(b);
    }

    @SuppressWarnings("unchecked")
    static void apply(NBTTagCompound data, MoveRecord move, boolean clear) throws IOException {
        preflight(move);
        coint.chunkbonus.ChunkBonus.migrationRestore(data.getString("ChunkBonus"), move.names, clear);
        try {
            if (data.hasKey("BloodMagic", 10)) {
                if (!Loader.isModLoaded("AWWayofTime")) throw new IOException("На цели нет Blood Magic");
                NBTTagCompound networks = data.getCompoundTag("BloodMagic");
                for (Object raw : networks.func_150296_c()) {
                    String name = raw.toString();
                    if (!move.names.contains(name)) throw new IOException("Чужая сеть Blood Magic в архиве");
                    NBTTagCompound network = networks.getCompoundTag(name);
                    BloodMagicData.restore(name, clear ? 0 : network.getInteger("Essence"), network.getInteger("Orb"));
                }
            }
            if (data.hasKey("GTTeams", 10)) {
                if (!Loader.isModLoaded("gregtech")) throw new IOException("На цели нет GregTech");
                Class<?> space = Class.forName(SPACE), energy = Class.forName(ENERGY);
                NBTTagCompound teams = data.getCompoundTag("GTTeams"), balances = data.getCompoundTag("EU");
                for (Object raw : balances.func_150296_c()) {
                    String id = raw.toString();
                    if (!move.players.contains(id)) throw new IOException("Чужой EU owner в архиве");
                    space.getMethod("putInTeam", UUID.class, UUID.class)
                        .invoke(null, UUID.fromString(id), UUID.fromString(id));
                }
                for (String id : move.players) {
                    String leader = teams.getString(id);
                    if (!move.players.contains(leader)) throw new IOException("Чужой лидер GT в архиве");
                    space.getMethod("putInTeam", UUID.class, UUID.class)
                        .invoke(null, UUID.fromString(id), UUID.fromString(leader));
                }
                for (Object raw : balances.func_150296_c()) {
                    String id = raw.toString();
                    BigInteger amount = new BigInteger(balances.getString(id));
                    if (amount.signum() < 0) throw new IOException("Отрицательная wireless EU");
                    energy.getMethod("setUserEU", UUID.class, BigInteger.class)
                        .invoke(null, UUID.fromString(id), clear ? BigInteger.ZERO : amount);
                }
                Field projects = space.getField("spaceTeamProjects");
                Map<Object, Object> all = (Map<Object, Object>) projects.get(null);
                Object gson = field(Class.forName(SPACE_SAVE), "GSON_SPACE_PROJECT");
                Map<?, ?> incoming = (Map<?, ?>) gson.getClass()
                    .getMethod("fromJson", String.class, java.lang.reflect.Type.class)
                    .invoke(gson, data.getString("GTProjects"), projects.getGenericType());
                for (Object id : incoming.keySet()) if (!move.players.contains(id.toString()))
                    throw new IOException("Чужие космические проекты в архиве");
                for (Object raw : balances.func_150296_c()) all.remove(UUID.fromString(raw.toString()));
                if (!clear) all.putAll(incoming);
                ((WorldSavedData) Class.forName(SPACE_SAVE)
                    .getField("INSTANCE")
                    .get(null)).markDirty();
            }
            if (data.hasKey("EnderStorage", 10)) {
                if (!Loader.isModLoaded("EnderStorage")) throw new IOException("На цели нет EnderStorage");
                Object manager = Class.forName(ENDER)
                    .getMethod("instance", boolean.class)
                    .invoke(null, false);
                NBTTagCompound selected = data.getCompoundTag("EnderStorage");
                for (Object raw : selected.func_150296_c()) {
                    String key = raw.toString();
                    String[] parts = key.split("\\|", -1);
                    if (parts.length != 3 || !owner(move, parts[1]))
                        throw new IOException("Чужая частота EnderStorage в архиве");
                    NBTTagCompound value = (NBTTagCompound) selected.getCompoundTag(key)
                        .copy();
                    if (clear) {
                        if (parts[2].equals("item")) value.setTag("Items", new NBTTagList());
                        else if (parts[2].equals("liquid")) value.getCompoundTag("tank")
                            .setInteger("Amount", 0);
                        else throw new IOException("Неизвестный тип EnderStorage: " + parts[2]);
                    }
                    manager.getClass()
                        .getMethod("setStorage", String.class, int.class, String.class, NBTTagCompound.class)
                        .invoke(manager, parts[1], Integer.parseInt(parts[0]), parts[2], value);
                    ((NBTTagCompound) field(manager, "saveTag")).setTag(key, value);
                }
                saveEnder(manager);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IOException("Не удалось восстановить глобальные данные команды", e);
        }
    }

    private static void saveEnder(Object manager) throws ReflectiveOperationException {
        java.lang.reflect.Method save = manager.getClass()
            .getDeclaredMethod("save", boolean.class);
        save.setAccessible(true);
        save.invoke(manager, true);
    }
}
