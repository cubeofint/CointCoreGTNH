package coint;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Objects;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraftforge.common.DimensionManager;

import coint.player.TeamsManager;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;

public class DimensionCleaner {

    static {
        processDims();
    }

    private static final long MS_TO_FREEZE = 86_400_000L * CointConfig.cleaner.daysToFreeze;
    private static final long MS_TO_DELETE = 86_400_000L * CointConfig.cleaner.daysToDelete;

    public static ArrayList<Integer> excludeRegisterList = new ArrayList<>();
    public static ArrayList<Integer> deleteList = new ArrayList<>();

    public static void processDims() {
        if (!CointConfig.cleaner.enabled) return;

        var mgr = TeamsManager.get();
        long curr = System.currentTimeMillis();
        for (ForgeTeam team : Universe.get()
            .getTeams()) {
            if (!mgr.pdBinds.containsKey(team.getUID())) continue;
            int dimId = mgr.pdBinds.get(team.getUID());

            long inactiveTime = curr - team.getLastActivity();
            if (inactiveTime >= MS_TO_DELETE) {
                deleteList.add(dimId);
            } else if (inactiveTime >= MS_TO_FREEZE) {
                excludeRegisterList.add(dimId);
            }
            processPlayers();
        }
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
}
