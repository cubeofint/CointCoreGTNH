package coint.player;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.LinkedList;
import java.util.UUID;

import net.minecraft.command.ICommandSender;
import net.minecraft.command.PlayerNotFoundException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.util.Constants;

import coint.CointCore;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;
import serverutils.lib.util.NBTUtils;

public class CointPlayer {

    private static final String NBT_DATA = CointCore.MOD_ID + ".data";
    private static final String NBT_WARN = "warns";
    private static final String NBT_MUTE = "mute";
    private static final String NBT_BAN = "ban";

    ForgePlayer player;

    Mute mute;
    TBan ban;
    LinkedList<Warn> warns;

    public static NBTTagCompound getOfflineNBT(UUID uuid) {
        NBTTagCompound nbt;
        try {
            File w = MinecraftServer.getServer()
                .worldServerForDimension(0)
                .getSaveHandler()
                .getWorldDirectory();
            File pdir = new File(w, "playerdata");
            File dat = new File(pdir, uuid.toString() + ".dat");
            try (FileInputStream stream = new FileInputStream(dat)) {
                nbt = CompressedStreamTools.readCompressed(stream)
                    .getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG)
                    .getCompoundTag(NBT_DATA);
            }
        } catch (Exception e) {
            CointCore.LOG.warn("NBT {} read problem", uuid);
            CointCore.LOG.error(e.getMessage());
            return null;
        }

        return nbt;
    }

    public static void saveOfflineNBT(UUID uuid, NBTTagCompound nbt) {
        try {
            File w = MinecraftServer.getServer()
                .worldServerForDimension(0)
                .getSaveHandler()
                .getWorldDirectory();
            File pdir = new File(w, "playerdata");
            File dat = new File(pdir, uuid.toString() + ".dat");
            NBTTagCompound base;
            try (FileInputStream fis = new FileInputStream(dat)) {
                base = CompressedStreamTools.readCompressed(fis);
            }
            base.getCompoundTag("ForgeData")
                .getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG)
                .setTag(NBT_DATA, nbt);
            try (FileOutputStream fos = new FileOutputStream(dat)) {
                CompressedStreamTools.writeCompressed(base, fos);
            }
        } catch (Exception e) {
            CointCore.LOG.warn("NBT {} write problem", uuid);
            CointCore.LOG.error(e.getMessage());
        }
    }

    private CointPlayer(ForgePlayer player) {
        this.player = player;

        NBTTagCompound nbt;
        if (player.isOnline()) {
            nbt = NBTUtils.getPersistedData(getPlayer(), true)
                .getCompoundTag(NBT_DATA);
        } else {
            nbt = getOfflineNBT(player.getId());
            if (nbt == null) throw new PlayerNotFoundException();
        }

        warns = new LinkedList<>();
        if (nbt.hasKey(NBT_WARN, Constants.NBT.TAG_LIST)) {
            NBTTagList nbtWarns = nbt.getTagList(NBT_WARN, Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < nbtWarns.tagCount(); i++) {
                NBTTagCompound nbtWarn = nbtWarns.getCompoundTagAt(i);
                Warn warn = new Warn();
                warn.warner = nbtWarn.getString("warner");
                warn.reason = nbtWarn.getString("reason");
                warn.timestamp = nbtWarn.getString("timestamp");
                warns.add(warn);
            }
        }

        if (nbt.hasKey(NBT_MUTE, Constants.NBT.TAG_COMPOUND)) {
            NBTTagCompound nbtMute = nbt.getCompoundTag(NBT_MUTE);
            mute = new Mute();
            mute.muter = nbtMute.getString("muter");
            mute.reason = nbtMute.getString("reason");
            mute.expiresAt = nbtMute.getLong("expiresAt");
        }

        if (nbt.hasKey(NBT_BAN, Constants.NBT.TAG_COMPOUND)) {
            NBTTagCompound nbtBan = nbt.getCompoundTag(NBT_BAN);
            ban = new TBan();
            ban.banner = nbtBan.getString("banner");
            ban.reason = nbtBan.getString("reason");
            ban.expiresAt = nbtBan.getLong("expiresAt");
        }
    }

    private void save() {
        NBTTagCompound nbt = new NBTTagCompound();

        if (warns != null) {
            NBTTagList nbtWarns = new NBTTagList();
            for (Warn warn : warns) {
                NBTTagCompound nbtWarn = new NBTTagCompound();
                nbtWarn.setString("warner", warn.warner);
                nbtWarn.setString("reason", warn.reason);
                nbtWarn.setString("timestamp", warn.timestamp);
                nbtWarns.appendTag(nbtWarn);
            }
            nbt.setTag(NBT_WARN, nbtWarns);
        }

        if (isMuted()) {
            NBTTagCompound nbtMute = new NBTTagCompound();
            nbtMute.setString("muter", mute.muter);
            nbtMute.setString("reason", mute.reason);
            nbtMute.setLong("expiresAt", mute.expiresAt);
            nbt.setTag(NBT_MUTE, nbtMute);
        }

        if (isBanned()) {
            NBTTagCompound nbtBan = new NBTTagCompound();
            nbtBan.setString("banner", ban.banner);
            nbtBan.setString("reason", ban.reason);
            nbtBan.setLong("expiresAt", ban.expiresAt);
            nbt.setTag(NBT_BAN, nbtBan);
        }

        if (player.isOnline()) {
            NBTTagCompound base = NBTUtils.getPersistedData(getPlayer(), true);
            base.setTag(NBT_DATA, nbt);
        } else {
            saveOfflineNBT(player.getId(), nbt);
        }
        // player.setPlayerNBT(base);
    }

    public static CointPlayer get(CharSequence nameOrId) {
        ForgePlayer player = Universe.get()
            .getPlayer(nameOrId);
        if (player == null) {
            throw new PlayerNotFoundException();
        }
        return new CointPlayer(player);
    }

    public static CointPlayer get(ICommandSender sender) {
        ForgePlayer player = Universe.get()
            .getPlayer(sender);
        return new CointPlayer(player);
    }

    public boolean isOnline() {
        return player.isOnline();
    }

    public EntityPlayerMP getPlayer() {
        return player.getPlayer();
    }

    public String getName() {
        return player.getName();
    }

    public void ban(ICommandSender sender, String reason, long durationMs) {
        ban = new TBan(sender, reason, durationMs);
        save();
    }

    public void unban() {
        ban = null;
        save();
    }

    public boolean isBanned() {
        return ban != null && !ban.isExpired();
    }

    public String getBanMessage() {
        if (ban != null) return ban.getBanMessage();
        else return "Вы разбанены";
    }

    public void mute(ICommandSender sender, String reason, long durationMs) {
        mute = new Mute(sender, reason, durationMs);
        save();
    }

    public void unmute() {
        mute = null;
        save();
    }

    public boolean isMuted() {
        return mute != null && !mute.isExpired();
    }

    public boolean isMuteExpired() {
        return mute != null && mute.isExpired();
    }

    public long getMuteRemaining() {
        return mute.expiresAt - System.currentTimeMillis();
    }

    public void warn(ICommandSender sender, String reason) {
        warns.add(new Warn(sender, reason));
        save();
    }

    public void unwarn(int i) {
        if (i < 0) warns.clear();
        else warns.remove(i);
        save();
    }

    public LinkedList<Warn> getWarns() {
        return warns;
    }
}
