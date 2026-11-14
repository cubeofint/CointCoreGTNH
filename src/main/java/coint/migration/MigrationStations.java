package coint.migration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.DimensionManager;

import cpw.mods.fml.relauncher.ReflectionHelper;
import micdoodle8.mods.galacticraft.api.galaxies.GalaxyRegistry;
import micdoodle8.mods.galacticraft.api.galaxies.Satellite;
import micdoodle8.mods.galacticraft.core.GalacticraftCore;
import micdoodle8.mods.galacticraft.core.dimension.SpaceStationWorldData;
import micdoodle8.mods.galacticraft.core.network.PacketSimple;
import micdoodle8.mods.galacticraft.core.util.ConfigManagerCore;
import micdoodle8.mods.galacticraft.core.util.WorldUtil;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;

/** Whole, player-owned stations. Registration IDs are never silently remapped. */
public final class MigrationStations {

    private MigrationStations() {}

    public static final class Station {

        public int id, home, dynamic, fixed;
        public String owner;
    }

    static Station get(MoveRecord move, int dim) {
        if (move != null && move.stations != null) for (Station s : move.stations) if (s.id == dim) return s;
        return null;
    }

    static String folder(int id) {
        return "DIM_SPACESTATION" + id;
    }

    static String metadata(int id) {
        return "data/spacestation_" + id + ".dat";
    }

    static String folder(MoveRecord move, int id) {
        return get(move, id) == null ? "PERSONAL_DIM_" + id : folder(id);
    }

    static Station read(File file, int id) throws IOException {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            NBTTagCompound root = CompressedStreamTools.readCompressed(in);
            if (!root.hasKey("data", 10)) throw new IOException("Нет данных станции " + id);
            return describe(root.getCompoundTag("data"), id);
        }
    }

    static Station describe(NBTTagCompound nbt, int id) throws IOException {
        for (String key : new String[] { "homePlanet", "dimensionIdDynamic", "dimensionIdStatic" })
            if (!nbt.hasKey(key, 3)) throw new IOException("Нет параметра " + key + " станции " + id);
        Station s = new Station();
        s.id = id;
        s.owner = nbt.getString("owner");
        s.home = nbt.getInteger("homePlanet");
        s.dynamic = nbt.getInteger("dimensionIdDynamic");
        s.fixed = nbt.getInteger("dimensionIdStatic");
        if (id <= 1 || !s.owner.matches("[A-Za-z0-9_]{1,16}")) throw new IOException("Некорректная станция " + id);
        return s;
    }

    static void same(Station a, Station b) throws IOException {
        if (a.id != b.id || a.home != b.home
            || a.dynamic != b.dynamic
            || a.fixed != b.fixed
            || !a.owner.equalsIgnoreCase(b.owner))
            throw new IOException("Изменились владелец/параметры станции " + a.id);
    }

    static List<Station> discover(ForgeTeam team) throws IOException {
        List<Station> result = new ArrayList<>();
        if (WorldUtil.registeredSpaceStations == null) return result;
        Set<String> names = new HashSet<>();
        for (ForgePlayer p : team.getMembers()) names.add(
            p.getName()
                .toLowerCase(Locale.ROOT));
        File root = MigrationStorage.session().world;
        WorldServer world = DimensionManager.getWorld(0);
        for (int id : WorldUtil.registeredSpaceStations.keySet()) {
            WorldSavedData saved = world.loadItemData(SpaceStationWorldData.class, "spacestation_" + id);
            if (!(saved instanceof SpaceStationWorldData station) || !names.contains(
                station.getOwner()
                    .toLowerCase(Locale.ROOT)))
                continue;
            NBTTagCompound nbt = new NBTTagCompound();
            station.writeToNBT(nbt);
            Station s = describe(nbt, id);
            result.add(s);
        }
        return result;
    }

    static void checkProvider(Station s) throws IOException {
        boolean match = false;
        for (Satellite satellite : GalaxyRegistry.getRegisteredSatellites()
            .values())
            if (satellite.getDimensionID() == s.dynamic && satellite.getDimensionIdStatic() == s.fixed) match = true;
        if (!match || !DimensionManager.isDimensionRegistered(s.home)) throw new IOException(
            "Не найдены планета/провайдеры станции " + s.id + " (" + s.home + ", " + s.dynamic + ", " + s.fixed + ")");
    }

    static boolean installed(MoveRecord move, Station s, File root) throws IOException {
        File marker = MigrationStorage.child(root, folder(s.id) + "/.cointcore-move");
        return marker.isFile()
            && move.id.equals(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8));
    }

    static void check(MoveRecord move, Station s, boolean target) throws IOException {
        checkProvider(s);
        File root = MigrationStorage.session().world;
        if (target) {
            if (installed(move, s, root)) {
                same(s, read(MigrationStorage.child(root, metadata(s.id)), s.id));
                if (!DimensionManager.isDimensionRegistered(s.id)
                    || (DimensionManager.getProviderType(s.id) != s.dynamic
                        && DimensionManager.getProviderType(s.id) != s.fixed))
                    throw new IOException("Провайдер установленной станции изменился: " + s.id);
                return;
            }
            if (DimensionManager.isDimensionRegistered(s.id) || MigrationStorage.child(root, folder(s.id))
                .exists()
                || MigrationStorage.child(root, metadata(s.id))
                    .exists())
                throw new IOException("ID/файлы станции " + s.id + " уже заняты на цели");
        } else {
            if (WorldUtil.registeredSpaceStations == null || !WorldUtil.registeredSpaceStations.containsKey(s.id))
                throw new IOException("Станция не зарегистрирована: " + s.id);
            WorldSavedData saved = DimensionManager.getWorld(0)
                .loadItemData(SpaceStationWorldData.class, "spacestation_" + s.id);
            if (!(saved instanceof SpaceStationWorldData)) throw new IOException("Нет метаданных станции " + s.id);
            NBTTagCompound data = new NBTTagCompound();
            saved.writeToNBT(data);
            same(s, describe(data, s.id));
            if (!MigrationStorage.child(root, folder(s.id))
                .isDirectory()) throw new IOException("Нет папки станции " + s.id);
        }
    }

    static void register(MoveRecord move, Station s, File root) throws IOException {
        same(s, read(MigrationStorage.child(root, metadata(s.id)), s.id));
        checkProvider(s);
        if (DimensionManager.isDimensionRegistered(s.id))
            throw new IOException("ID станции занят перед регистрацией: " + s.id);
        int provider = Arrays.binarySearch(ConfigManagerCore.staticLoadDimensions, s.id) >= 0 ? s.fixed : s.dynamic;
        DimensionManager.registerDimension(s.id, provider);
        WorldUtil.registeredSpaceStations.put(s.id, provider);
        WorldUtil.dimNames.put(s.id, "Space Station " + s.id);
        Files.write(
            MigrationStorage.child(root, folder(s.id) + "/.cointcore-move")
                .toPath(),
            move.id.getBytes(StandardCharsets.UTF_8));
        broadcast();
    }

    static void forget(int id) throws IOException {
        // MapStorage must not recreate the retired station metadata on a later save.
        try {
            MapStorage storage = DimensionManager.getWorld(0).mapStorage;
            Field map = ReflectionHelper.findField(MapStorage.class, "loadedDataMap", "field_75749_b");
            Field list = ReflectionHelper.findField(MapStorage.class, "loadedDataList", "field_75750_c");
            Object saved = ((Map<?, ?>) map.get(storage)).remove("spacestation_" + id);
            if (saved != null) ((List<?>) list.get(storage)).remove(saved);
        } catch (Exception e) {
            throw new IOException("Не удалось очистить кеш станции " + id, e);
        }
        if (DimensionManager.isDimensionRegistered(id)) DimensionManager.unregisterDimension(id);
        if (WorldUtil.registeredSpaceStations != null) WorldUtil.registeredSpaceStations.remove(id);
        WorldUtil.dimNames.remove(id);
        broadcast();
    }

    private static void broadcast() {
        GalacticraftCore.packetPipeline.sendToAll(
            new PacketSimple(
                PacketSimple.EnumSimplePacket.C_UPDATE_SPACESTATION_LIST,
                WorldUtil.getSpaceStationList()));
    }
}
