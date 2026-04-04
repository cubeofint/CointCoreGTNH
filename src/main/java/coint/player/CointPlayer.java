package coint.player;

import java.util.LinkedList;

import net.minecraft.command.ICommandSender;
import net.minecraft.command.PlayerNotFoundException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import coint.CointCore;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;

public class CointPlayer {

    private static final String NBT_DATA = CointCore.MOD_ID + "data";
    private static final String NBT_WARN = CointCore.MOD_ID + "warns";
    private static final String NBT_MUTE = CointCore.MOD_ID + "mute";
    private static final String NBT_BAN = CointCore.MOD_ID + "ban";

    ForgePlayer player;

    Mute mute;
    TBan ban;
    LinkedList<Warn> warns;

    private CointPlayer(ForgePlayer player) {
        this.player = player;

        NBTTagCompound nbt = player.getPlayerNBT()
            .getCompoundTag(NBT_DATA);

        if (nbt.hasKey(NBT_WARN, Constants.NBT.TAG_LIST)) {
            NBTTagList nbtWarns = nbt.getTagList(NBT_WARN, Constants.NBT.TAG_COMPOUND);
            warns = new LinkedList<>();
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

        NBTTagCompound base = player.getPlayerNBT();
        base.setTag(NBT_DATA, nbt);
        player.setPlayerNBT(base);
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
