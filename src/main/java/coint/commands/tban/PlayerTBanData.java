package coint.commands.tban;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.IExtendedEntityProperties;
import net.minecraftforge.common.util.Constants;

import coint.CointCore;

public class PlayerTBanData implements IExtendedEntityProperties {

    public static String EXT_PROP = CointCore.MOD_ID + "_ban";
    public static String NBT_TAG_BAN = "ban";

    private TBan tban;

    public PlayerTBanData() {
        tban = null;
    }

    @Override
    public void saveNBTData(NBTTagCompound compound) {
        NBTTagCompound props = new NBTTagCompound();

        if (tban != null && !tban.isExpired()) {
            NBTTagCompound banCompound = new NBTTagCompound();
            banCompound.setString("banner", tban.banner);
            banCompound.setString("reason", tban.reason);
            banCompound.setLong("expiresAt", tban.expiresAt);
            props.setTag(NBT_TAG_BAN, banCompound);
        } else {
            props.removeTag(NBT_TAG_BAN);
        }

        compound.setTag(EXT_PROP, props);
    }

    @Override
    public void loadNBTData(NBTTagCompound compound) {
        NBTTagCompound props = compound.getCompoundTag(EXT_PROP);

        if (props != null && props.hasKey(NBT_TAG_BAN)) {
            NBTTagCompound banCompound = props.getCompoundTag(NBT_TAG_BAN);

            TBan ban = new TBan();
            ban.banner = banCompound.getString("banner");
            ban.reason = banCompound.getString("reason");
            ban.expiresAt = banCompound.getLong("expiresAt");

            if (!ban.isExpired()) {
                this.tban = ban;
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

    public static void setOffline(UUID player, TBan ban) {
        File playerDataFile = new File("World/playerdata/" + player + ".dat");
        if (playerDataFile.exists()) {
            try (FileInputStream fileInputStream = new FileInputStream(playerDataFile)) {
                NBTTagCompound compound = CompressedStreamTools.readCompressed(fileInputStream);

                NBTTagCompound props = compound.getCompoundTag(EXT_PROP);
                if (props != null) {
                    if (ban == null) {
                        props.removeTag(NBT_TAG_BAN);
                    } else {
                        NBTTagCompound nbtBan;
                        if (props.hasKey(NBT_TAG_BAN, Constants.NBT.TAG_COMPOUND)) {
                            nbtBan = props.getCompoundTag(NBT_TAG_BAN);
                        } else {
                            nbtBan = new NBTTagCompound();
                        }

                        nbtBan.setString("banner", ban.banner);
                        nbtBan.setString("reason", ban.reason);
                        nbtBan.setLong("expiresAt", ban.expiresAt);

                        props.setTag(NBT_TAG_BAN, nbtBan);
                    }

                    compound.setTag(EXT_PROP, props);
                    try (FileOutputStream fileOutputStream = new FileOutputStream(playerDataFile)) {
                        CompressedStreamTools.writeCompressed(compound, fileOutputStream);
                    }
                }
            } catch (Exception e) {
                CointCore.LOG.error(e.getMessage());
            }
        }
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
