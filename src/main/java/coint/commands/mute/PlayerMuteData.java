package coint.commands.mute;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.IExtendedEntityProperties;

import coint.CointCore;

public class PlayerMuteData implements IExtendedEntityProperties {

    public static String EXT_PROP = CointCore.MOD_ID + "_mute";
    public static String NBT_TAG_MUTE = "mute";

    private Mute mute;

    public PlayerMuteData() {
        mute = null;
    }

    @Override
    public void saveNBTData(NBTTagCompound compound) {
        NBTTagCompound props = new NBTTagCompound();

        if (mute != null && !mute.isExpired()) {
            NBTTagCompound muteCompound = new NBTTagCompound();
            muteCompound.setString("muter", mute.muter);
            muteCompound.setString("reason", mute.reason);
            muteCompound.setLong("expiresAt", mute.expiresAt);
            props.setTag(NBT_TAG_MUTE, muteCompound);
        } else {
            props.removeTag(NBT_TAG_MUTE);
        }

        compound.setTag(EXT_PROP, props);
    }

    @Override
    public void loadNBTData(NBTTagCompound compound) {
        NBTTagCompound props = compound.getCompoundTag(EXT_PROP);

        if (props != null && props.hasKey(NBT_TAG_MUTE)) {
            NBTTagCompound muteCompound = props.getCompoundTag(NBT_TAG_MUTE);

            Mute mute = new Mute();
            mute.muter = muteCompound.getString("muter");
            mute.reason = muteCompound.getString("reason");
            mute.expiresAt = muteCompound.getLong("expiresAt");

            if (!mute.isExpired()) {
                this.mute = mute;
            }
        }
    }

    @Override
    public void init(Entity entity, World world) {
        mute = null;
    }

    public static PlayerMuteData get(EntityPlayer player) {
        return (PlayerMuteData) player.getExtendedProperties(EXT_PROP);
    }

    public void set(Mute mute) {
        this.mute = mute;
    }

    public Mute get() {
        return mute;
    }

    public boolean isMuted() {
        return mute != null && !mute.isExpired();
    }

    public void clear() {
        mute = null;
    }
}
