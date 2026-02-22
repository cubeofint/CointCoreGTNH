package coint.commands.tban;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.IExtendedEntityProperties;

import coint.CointCore;

public class PlayerTBanData implements IExtendedEntityProperties {

    public static String EXT_PROP = CointCore.MOD_ID + "_tban";
    public static String NBT_TAG_TBAN = "tban";

    private TBan tban;

    public PlayerTBanData() {
        tban = null;
    }

    @Override
    public void saveNBTData(NBTTagCompound compound) {
        NBTTagCompound props = new NBTTagCompound();

        if (tban != null && !tban.isExpired()) {
            NBTTagCompound tbanCompound = new NBTTagCompound();
            tbanCompound.setString("banner", tban.banner);
            tbanCompound.setString("reason", tban.reason);
            tbanCompound.setLong("expiresAt", tban.expiresAt);
            props.setTag(NBT_TAG_TBAN, tbanCompound);
        } else {
            props.removeTag(NBT_TAG_TBAN);
        }

        compound.setTag(EXT_PROP, props);
    }

    @Override
    public void loadNBTData(NBTTagCompound compound) {
        NBTTagCompound props = compound.getCompoundTag(EXT_PROP);

        if (props != null && props.hasKey(NBT_TAG_TBAN)) {
            NBTTagCompound tbanCompound = props.getCompoundTag(NBT_TAG_TBAN);

            TBan tban = new TBan();
            tban.banner = tbanCompound.getString("banner");
            tban.reason = tbanCompound.getString("reason");
            tban.expiresAt = tbanCompound.getLong("expiresAt");

            if (!tban.isExpired()) {
                this.tban = tban;
            }
        }
    }

    @Override
    public void init(Entity entity, World world) {
        tban = null;
    }

    public static PlayerTBanData get(EntityPlayer player) {
        return (PlayerTBanData) player.getExtendedProperties(EXT_PROP);
    }

    public void set(TBan tban) {
        this.tban = tban;
    }

    public TBan get() {
        return tban;
    }

    public boolean isBanned() {
        return tban != null && !tban.isExpired();
    }

    public void clear() {
        tban = null;
    }
}
