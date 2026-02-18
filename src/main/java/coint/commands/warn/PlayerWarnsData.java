package coint.commands.warn;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraftforge.common.IExtendedEntityProperties;
import net.minecraftforge.common.util.Constants;

import coint.CointCore;

public class PlayerWarnsData implements IExtendedEntityProperties {

    public static String EXT_PROP = CointCore.MOD_ID;

    List<Warn> warns;

    @Override
    public void saveNBTData(NBTTagCompound compound) {
        NBTTagCompound props = new NBTTagCompound();
        NBTTagList list = new NBTTagList();

        for (Warn warn : warns) {
            NBTTagCompound warnCompound = new NBTTagCompound();
            warnCompound.setString("warner", warn.warner);
            warnCompound.setString("reason", warn.reason);
            warnCompound.setString("timestamp", warn.timestamp);

            list.appendTag(warnCompound);
        }

        props.setTag("warns", list);
        compound.setTag(EXT_PROP, props);
    }

    @Override
    public void loadNBTData(NBTTagCompound compound) {
        NBTTagCompound props = (NBTTagCompound) compound.getTag(EXT_PROP);

        if (props != null) {
            if (props.hasKey("warns", Constants.NBT.TAG_LIST)) {
                NBTTagList warnsList = props.getTagList("warns", Constants.NBT.TAG_COMPOUND);

                this.warns.clear();

                for (int i = 0; i < warnsList.tagCount(); i++) {
                    NBTTagCompound warnCompound = warnsList.getCompoundTagAt(i);

                    Warn warn = new Warn();
                    warn.warner = warnCompound.getString("warner");
                    warn.reason = warnCompound.getString("reason");
                    warn.timestamp = warnCompound.getString("timestamp");

                    this.warns.add(warn);
                }
            }
        }
    }

    @Override
    public void init(Entity entity, World world) {
        warns = new ArrayList<>();
    }

    public static List<Warn> getOffline(UUID player) {
        File playerDataFile = new File("World/playerdata/" + player + ".dat");
        if (playerDataFile.exists()) {
            try (FileInputStream fileInputStream = new FileInputStream(playerDataFile)) {
                NBTTagCompound fullPlayerData = CompressedStreamTools.readCompressed(fileInputStream);

                NBTTagCompound props = fullPlayerData.getCompoundTag(EXT_PROP);
                if (props != null && props.hasKey("warns", Constants.NBT.TAG_LIST)) {
                    NBTTagList warnsList = props.getTagList("warns", Constants.NBT.TAG_COMPOUND);

                    List<Warn> warns = new LinkedList<>();

                    for (int i = 0; i < warnsList.tagCount(); i++) {
                        NBTTagCompound warnCompound = warnsList.getCompoundTagAt(i);

                        Warn warn = new Warn();
                        warn.warner = warnCompound.getString("warner");
                        warn.reason = warnCompound.getString("reason");
                        warn.timestamp = warnCompound.getString("timestamp");

                        warns.add(warn);
                    }

                    return warns;
                }

            } catch (Exception e) {
                CointCore.LOG.error(e.getMessage());
            }
        }
        return null;
    }

    public static void addOffline(UUID player, Warn warn) {
        File playerDataFile = new File("World/playerdata/" + player + ".dat");
        if (playerDataFile.exists()) {
            try (FileInputStream fileInputStream = new FileInputStream(playerDataFile)) {
                NBTTagCompound compound = CompressedStreamTools.readCompressed(fileInputStream);

                NBTTagCompound props = compound.getCompoundTag(EXT_PROP);
                if (props != null) {
                    NBTTagList list = props.getTagList("warns", Constants.NBT.TAG_LIST);

                    NBTTagCompound warnCompound = new NBTTagCompound();
                    warnCompound.setString("warner", warn.warner);
                    warnCompound.setString("reason", warn.reason);
                    warnCompound.setString("timestamp", warn.timestamp);

                    list.appendTag(warnCompound);

                    props.setTag("warns", list);
                    compound.setTag(EXT_PROP, props);

                    FileOutputStream fileOutputStream = new FileOutputStream(playerDataFile);
                    CompressedStreamTools.writeCompressed(compound, fileOutputStream);
                    fileOutputStream.close();
                }
            } catch (Exception e) {
                CointCore.LOG.error(e.getMessage());
            }
        }
    }

    public static PlayerWarnsData get(EntityPlayer player) {
        return (PlayerWarnsData) player.getExtendedProperties(EXT_PROP);
    }

    public void add(Warn warn) {
        warns.add(warn);
    }

    public List<Warn> get() {
        return warns;
    }
}
