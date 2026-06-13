package coint;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.player.TeamsManager;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;

@EventBusSubscriber
public class DimensionCleaner extends WorldSavedData {

    @SubscribeEvent
    public static void checkDims(FMLServerStartedEvent event) {
        get().processDims();
    }

    private static final String DATA_NAME = "dimensions";
    private static final String NBT_FREEZE_LIST = "freeze";
    private static final String NBT_DELETE_LIST = "delete";

    private static final long MS_TO_FREEZE = 86_400_000L * CointConfig.cleaner.daysToFreeze;
    private static final long MS_TO_DELETE = 86_400_000L * CointConfig.cleaner.daysToDelete;

    public static ArrayList<Integer> excludeRegisterList = new ArrayList<>();
    public static ArrayList<Integer> deleteList = new ArrayList<>();

    public DimensionCleaner(String p_i2141_1_) {
        super(p_i2141_1_);
    }

    public static DimensionCleaner get() {
        WorldServer overworld = MinecraftServer.getServer()
            .worldServerForDimension(0);
        DimensionCleaner instance = (DimensionCleaner) overworld.loadItemData(DimensionCleaner.class, DATA_NAME);

        if (instance == null) {
            instance = new DimensionCleaner(DATA_NAME);
            overworld.setItemData(DATA_NAME, instance);
        }
        return instance;
    }

    public void processDims() {
        if (!CointConfig.cleaner.enabled || (!CointConfig.cleaner.deleteEnabled && !CointConfig.cleaner.freezeEnabled))
            return;

        var mgr = TeamsManager.get();
        long curr = System.currentTimeMillis();
        for (ForgeTeam team : Universe.get()
            .getTeams()) {
            if (!mgr.pdBinds.containsKey(team.getUID())) continue;
            int dimId = mgr.pdBinds.get(team.getUID());

            long inactiveTime = curr - team.getLastActivity();
            if (CointConfig.cleaner.deleteEnabled && inactiveTime >= MS_TO_DELETE) {
                deleteList.add(dimId);
            } else if (CointConfig.cleaner.deleteEnabled && inactiveTime >= MS_TO_FREEZE) {
                excludeRegisterList.add(dimId);
            }
        }
        markDirty();
        processPlayers();
    }

    private static void processPlayers() {
        if (excludeRegisterList.isEmpty() && deleteList.isEmpty()) return;

        File w = DimensionManager.getCurrentSaveRootDirectory();
        if (w == null || !w.isDirectory()) {
            return;
        }
        ChunkCoordinates spawn = MinecraftServer.getServer()
            .worldServerForDimension(0)
            .getSpawnPoint();
        for (File dat : Objects.requireNonNull(w.listFiles(((dir, name) -> name.endsWith(".dat"))))) {
            try {
                NBTTagCompound nbt;
                try (FileInputStream stream = new FileInputStream(dat)) {
                    nbt = CompressedStreamTools.readCompressed(stream);
                }
                if (nbt == null || !nbt.hasKey("Dimension")) continue;

                int dimId = nbt.getInteger("Dimension");
                if (excludeRegisterList.contains(dimId) || deleteList.contains(dimId)) {
                    nbt.setInteger("Dimension", 0);
                    nbt.setFloat("FallDistance", 0.0F);

                    NBTTagList pos = new NBTTagList();
                    pos.appendTag(new NBTTagDouble(spawn.posX + 0.5));
                    pos.appendTag(new NBTTagDouble(spawn.posY + 1.0));
                    pos.appendTag(new NBTTagDouble(spawn.posZ + 0.5));
                    nbt.setTag("Pos", pos);

                    try (FileOutputStream outputStream = new FileOutputStream(dat)) {
                        CompressedStreamTools.writeCompressed(nbt, outputStream);
                    }

                    CointCore.LOG.info("Player {} evacuated from {}", dat.getName(), dimId);
                }
            } catch (Exception e) {
                CointCore.LOG.error("Can't evacuate player: {}", dat.getName());
            }
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        excludeRegisterList.clear();
        Arrays.stream(nbt.getIntArray(NBT_FREEZE_LIST))
            .forEach(excludeRegisterList::add);
        deleteList.clear();
        Arrays.stream(nbt.getIntArray(NBT_DELETE_LIST))
            .forEach(deleteList::add);
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        nbt.setIntArray(
            NBT_FREEZE_LIST,
            excludeRegisterList.stream()
                .mapToInt(Integer::intValue)
                .toArray());
        nbt.setIntArray(
            NBT_DELETE_LIST,
            deleteList.stream()
                .mapToInt(Integer::intValue)
                .toArray());
    }
}
