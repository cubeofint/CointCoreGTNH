package coint.player;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldServer;

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

    public TeamsManager() {
        super(DATA_NAME);
    }

    public void setDimensionId(short shortId, int dimId) {
        pdBinds.put(shortId, dimId);
        markDirty(); // Уведомляем сервер о необходимости сохранения данных на диск
    }

    public boolean isDimBound(short teamUid) {
        return pdBinds.containsKey(teamUid);
    }

    public void removeBinding(short shortId) {
        if (pdBinds.remove(shortId) != null) {
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
