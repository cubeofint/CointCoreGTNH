package coint.migration;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldSavedData;
import net.minecraftforge.common.DimensionManager;

import coint.migration.MigrationStations.Station;
import micdoodle8.mods.galacticraft.core.network.IPacket;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;

/** Optional AmunRa 0.8.x integration. Only a team's own stationary ships are transferred. */
final class MigrationMotherships {

    private static final String BASE = "de.katzenpapst.amunra.";

    private MigrationMotherships() {}

    private static Class<?> type(String name) throws ReflectiveOperationException {
        return Class.forName(BASE + name);
    }

    private static Object registry() throws ReflectiveOperationException, IOException {
        Object value = type("tick.TickHandlerServer").getField("mothershipData")
            .get(null);
        if (value == null) throw new IOException("Реестр Mothership ещё не загружен");
        return value;
    }

    private static Map<?, ?> ships(Object registry) throws ReflectiveOperationException {
        // getMotherships() returns a clone; mutations must affect the actual registry.
        Field entries = registry.getClass()
            .getDeclaredField("mothershipIdList");
        entries.setAccessible(true);
        return (Map<?, ?>) entries.get(registry);
    }

    private static Object byDimension(Object registry, int dim) throws ReflectiveOperationException {
        return registry.getClass()
            .getMethod("getByDimensionId", int.class)
            .invoke(registry, dim);
    }

    private static NBTTagCompound data(Object ship) throws ReflectiveOperationException {
        NBTTagCompound nbt = new NBTTagCompound();
        ship.getClass()
            .getMethod("writeToNBT", NBTTagCompound.class)
            .invoke(ship, nbt);
        return nbt;
    }

    static Station describe(NBTTagCompound nbt, int dim) throws IOException {
        Station s = new Station();
        s.id = nbt.getInteger("dim");
        s.shipId = nbt.getInteger("id");
        s.owner = nbt.getString("ownerName");
        s.ownerUuid = nbt.getString("owner");
        s.parent = nbt.getString("parentName");
        if (dim <= 1 || s.id != dim
            || s.shipId <= 0
            || !s.owner.matches("[A-Za-z0-9_]{1,16}")
            || s.parent.isEmpty()
            || !nbt.hasKey("inTransit", 1)) throw new IOException("Некорректные данные Mothership " + dim);
        try {
            UUID.fromString(s.ownerUuid);
        } catch (IllegalArgumentException e) {
            throw new IOException("Нет UUID владельца корабля " + dim, e);
        }
        if (nbt.getBoolean("inTransit")) throw new IOException("Mothership " + dim + " в перелёте; дождитесь прибытия");
        return s;
    }

    static List<Station> discover(ForgeTeam team) throws IOException {
        List<Station> result = new ArrayList<>();
        try {
            try {
                type("tick.TickHandlerServer");
            } catch (ClassNotFoundException absent) {
                return result;
            }
            for (Object ship : ships(registry()).values()) {
                NBTTagCompound nbt = data(ship);
                for (ForgePlayer p : team.getMembers()) if (p.getId()
                    .toString()
                    .equals(nbt.getString("owner"))) {
                        Station s = describe(nbt, nbt.getInteger("dim"));
                        if (!p.getName()
                            .equalsIgnoreCase(s.owner))
                            throw new IOException("UUID/имя владельца корабля не совпадают: " + s.id);
                        result.add(s);
                        break;
                    }
            }
            return result;
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IOException("Несовместимый API AmunRa", e);
        }
    }

    static void checkProvider(Station s) throws IOException {
        try {
            Object body = type("mothership.Mothership").getMethod("findBodyByNamePath", String.class)
                .invoke(null, s.parent);
            if (body == null) throw new IOException("Не найдена орбита Mothership " + s.id + ": " + s.parent);
            provider();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IOException("Не найден AmunRa/орбита корабля " + s.id, e);
        }
    }

    private static int provider() throws ReflectiveOperationException {
        Object config = type("AmunRa").getField("config")
            .get(null);
        return config.getClass()
            .getField("mothershipProviderID")
            .getInt(config);
    }

    static void check(MoveRecord move, Station s, boolean target) throws IOException {
        if (!move.players.contains(s.ownerUuid)) throw new IOException("Владелец корабля не входит в команду: " + s.id);
        checkProvider(s);
        File root = MigrationStorage.session().world;
        try {
            Object registry = registry();
            Object ship = byDimension(registry, s.id);
            Object sameId = ships(registry).get(s.shipId);
            if (target && !MigrationStations.installed(move, s, root)) {
                if (ship != null || sameId != null
                    || DimensionManager.isDimensionRegistered(s.id)
                    || MigrationStorage.child(root, MigrationStations.folder(s))
                        .exists()
                    || MigrationStorage.child(root, MigrationStations.metadata(s))
                        .exists())
                    throw new IOException(
                        "ID корабля/измерения Mothership уже занят на цели: " + s.shipId + "/" + s.id);
                return;
            }
            if (ship == null || sameId != ship) throw new IOException("Нет регистрации Mothership " + s.id);
            MigrationStations.same(s, describe(data(ship), s.id));
            if (!DimensionManager.isDimensionRegistered(s.id) || DimensionManager.getProviderType(s.id) != provider())
                throw new IOException("Провайдер Mothership изменился: " + s.id);
            if (!MigrationStorage.child(root, MigrationStations.folder(s))
                .isDirectory()) throw new IOException("Нет папки Mothership " + s.id);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IOException("Ошибка проверки Mothership " + s.id, e);
        }
    }

    /** A standalone entry, never the shared ARMothershipData.dat. */
    static void snapshot(MoveRecord move, Station s, File root) throws IOException {
        if (s.shipId == 0) return;
        check(move, s, false);
        try {
            write(MigrationStorage.child(root, MigrationStations.metadata(s)), data(byDimension(registry(), s.id)));
        } catch (ReflectiveOperationException e) {
            throw new IOException("Не удалось сохранить запись корабля " + s.id, e);
        }
    }

    private static void write(File file, NBTTagCompound data) throws IOException {
        NBTTagCompound wrapped = new NBTTagCompound();
        wrapped.setTag("data", data);
        Files.createDirectories(
            file.toPath()
                .getParent());
        try (java.io.OutputStream out = Files.newOutputStream(file.toPath())) {
            CompressedStreamTools.writeCompressed(wrapped, out);
        }
    }

    private static NBTTagCompound read(File file) throws IOException {
        try (java.io.InputStream in = Files.newInputStream(file.toPath())) {
            return CompressedStreamTools.readCompressed(in)
                .getCompoundTag("data");
        }
    }

    private static void backupRegistry(Object registry, File folder) throws IOException {
        NBTTagCompound nbt = new NBTTagCompound();
        ((WorldSavedData) registry).writeToNBT(nbt);
        File file = new File(folder, "ARMothershipData.before.dat");
        if (!file.exists()) write(file, nbt);
    }

    static void register(MoveRecord move, Station s, File root) throws IOException {
        NBTTagCompound nbt = read(MigrationStorage.child(root, MigrationStations.metadata(s)));
        MigrationStations.same(s, describe(nbt, s.id));
        checkProvider(s);
        try {
            Object registry = registry();
            if (byDimension(registry, s.id) != null || ships(registry).containsKey(s.shipId)
                || DimensionManager.isDimensionRegistered(s.id))
                throw new IOException("ID Mothership занят перед импортом: " + s.id);
            backupRegistry(
                registry,
                MigrationStorage.child(MigrationStorage.localDirectory(), "mothership-backups/" + move.id));
            Class<?> shipType = type("mothership.Mothership");
            Object ship = shipType.getMethod("createFromNBT", NBTTagCompound.class)
                .invoke(null, nbt);
            // AmunRa's addMothership is CLIENT-only and is stripped on dedicated servers.
            Field dimensionsField = registry.getClass()
                .getDeclaredField("mothershipsByDimension");
            Field highestField = registry.getClass()
                .getDeclaredField("highestId");
            java.lang.reflect.Method orbits = registry.getClass()
                .getDeclaredMethod("updateAllOrbits");
            dimensionsField.setAccessible(true);
            highestField.setAccessible(true);
            orbits.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Integer, Object> dimensions = (Map<Integer, Object>) dimensionsField.get(registry);
            @SuppressWarnings("unchecked")
            Map<Integer, Object> entries = (Map<Integer, Object>) ships(registry);
            int highest = highestField.getInt(registry);
            DimensionManager.registerDimension(s.id, provider());
            entries.put(s.shipId, ship);
            dimensions.put(s.id, ship);
            highestField.setInt(registry, Math.max(highest, s.shipId));
            orbits.invoke(registry);
            ((WorldSavedData) registry).markDirty();
            DimensionManager.getWorld(0).mapStorage.saveAllData();
            Files.write(
                MigrationStorage.child(root, MigrationStations.folder(s) + "/.cointcore-move")
                    .toPath(),
                move.id.getBytes(StandardCharsets.UTF_8));
            broadcast(registry);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IOException("Ошибка регистрации Mothership " + s.id, e);
        }
    }

    static void forget(MoveRecord move, Station s, File backup) throws IOException {
        try {
            Object registry = registry();
            Object ship = byDimension(registry, s.id);
            Object sameId = ships(registry).get(s.shipId);
            if (ship == null && sameId == null && !DimensionManager.isDimensionRegistered(s.id)) return;
            if (ship == null || sameId != ship)
                throw new IOException("Регистрация корабля изменилась перед очисткой " + s.id);
            MigrationStations.same(s, describe(data(ship), s.id));
            if (DimensionManager.getWorld(s.id) != null) throw new IOException("Корабль ещё загружен " + s.id);
            Field byDim = registry.getClass()
                .getDeclaredField("mothershipsByDimension");
            byDim.setAccessible(true);
            Map<?, ?> dimensions = (Map<?, ?>) byDim.get(registry);
            java.lang.reflect.Method orbits = registry.getClass()
                .getDeclaredMethod("updateAllOrbits");
            orbits.setAccessible(true);
            backupRegistry(registry, backup);
            ships(registry).remove(s.shipId);
            dimensions.remove(s.id);
            orbits.invoke(registry);
            ((WorldSavedData) registry).markDirty();
            DimensionManager.getWorld(0).mapStorage.saveAllData();
            if (DimensionManager.isDimensionRegistered(s.id)) DimensionManager.unregisterDimension(s.id);
            broadcast(registry);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IOException("Ошибка удаления исходной регистрации Mothership " + s.id, e);
        }
    }

    private static void broadcast(Object registry) throws ReflectiveOperationException {
        NBTTagCompound data = new NBTTagCompound();
        ((WorldSavedData) registry).writeToNBT(data);
        Class<?> enumType = type("network.packet.PacketSimpleAR$EnumSimplePacket");
        Object kind = enumType.getField("C_UPDATE_MOTHERSHIP_LIST")
            .get(null);
        Object packet = type("network.packet.PacketSimpleAR").getConstructor(enumType, Object[].class)
            .newInstance(kind, new Object[] { data });
        Object pipeline = type("AmunRa").getField("packetPipeline")
            .get(null);
        pipeline.getClass()
            .getMethod("sendToAll", IPacket.class)
            .invoke(pipeline, packet);
    }
}
