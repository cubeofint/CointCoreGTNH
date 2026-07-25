package coint.player;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldServer;

import serverutils.lib.data.Universe;

/// Actually API for PersonalSpace
@SuppressWarnings("unused")
public class TeamsManager extends WorldSavedData {

    private static final String DATA_NAME = "COINT_Teams";

    public static final String NBT_PDS = "pds";
    public HashMap<Short, Integer> pdBinds = new HashMap<>();

    public static TeamsManager get() {
        WorldServer overworld = MinecraftServer.getServer()
            .worldServerForDimension(0);
        TeamsManager instance = (TeamsManager) overworld.loadItemData(TeamsManager.class, DATA_NAME);

        if (instance == null) {
            instance = new TeamsManager(DATA_NAME);
            overworld.setItemData(DATA_NAME, instance);
        }
        return instance;
    }

    public TeamsManager(String name) {
        super(name);
    }

    public boolean hasDimBinding(EntityPlayer player) {
        var p = Universe.get()
            .getPlayer(player);
        if (!p.hasTeam() || p.isOP()) return false;

        return pdBinds.containsKey(p.team.getUID());
    }

    public int getDim(EntityPlayer player) {
        var p = Universe.get()
            .getPlayer(player);
        if (!p.hasTeam()) return 0;

        return pdBinds.getOrDefault(p.team.getUID(), 0);
    }

    public void bindDim(EntityPlayer player, int dimId) {
        pdBinds.put(
            Universe.get()
                .getPlayer(player).team.getUID(),
            dimId);
        markDirty();
    }

    public void removeDimBind(EntityPlayer player) {
        if (pdBinds.remove(
            Universe.get()
                .getPlayer(player).team.getUID())
            != null) {
            markDirty();
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        pdBinds.clear();
        NBTTagCompound list = nbt.getCompoundTag(NBT_PDS);
        for (String key : list.func_150296_c()) {
            pdBinds.put(Short.parseShort(key), list.getInteger(key));
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagCompound list = new NBTTagCompound();
        for (Map.Entry<Short, Integer> entry : pdBinds.entrySet()) {
            list.setInteger(
                entry.getKey()
                    .toString(),
                entry.getValue());
        }
        nbt.setTag(NBT_PDS, list);
    }
}
