package coint.commands;

import java.util.ArrayList;
import java.util.Collection;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import coint.integration.serverutilities.CointRankConfigs;
import coint.util.TimeUtil;
import serverutils.lib.config.RankConfigAPI;
import serverutils.lib.math.Ticks;
import serverutils.lib.util.NBTUtils;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

public class CommandHeal extends CommandBase {

    public static final String PERMISSION = "cointcore.command.heal";
    private static final String TAG_LAST_HEAL_MS = "cointcore_heal_last_ms";

    public CommandHeal() {
        PermissionAPI.registerNode(PERMISSION, DefaultPermissionLevel.OP, "CointCore heal permission");
    }

    @Override
    public String getCommandName() {
        return "heal";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, PERMISSION);
        }
        return true; // console/RCON
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/heal";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        EntityPlayer player = (EntityPlayer) sender;
        if (isOnCooldown(sender, player)) {
            return;
        }
        player.heal(Float.MAX_VALUE);
        clearNegativeEffects(player);
        ChatComponentText success = new ChatComponentText("Здоровье восстановлено, все эффекты сняты");
        success.getChatStyle()
            .setColor(EnumChatFormatting.GREEN);
        sender.addChatMessage(success);
    }

    private boolean isOnCooldown(ICommandSender sender, EntityPlayer player) {
        NBTTagCompound persisted = NBTUtils.getPersistedData(player, true);
        long cooldownTicks = RankConfigAPI
            .get((net.minecraft.entity.player.EntityPlayerMP) player, CointRankConfigs.HEAL_COOLDOWN)
            .getLong();
        if (cooldownTicks <= 0) {
            return false;
        }

        long now = System.currentTimeMillis();
        long lastUse = persisted.getLong(TAG_LAST_HEAL_MS);
        long elapsed = now - lastUse;
        long cooldownMs = Ticks.get(cooldownTicks)
            .millis();
        if (lastUse > 0 && elapsed < cooldownMs) {
            long remainingSeconds = cooldownMs - elapsed + 999L;
            sendError(sender, "Вы сможете исцелиться через " + TimeUtil.formatDuration(remainingSeconds));
            return true;
        }

        persisted.setLong(TAG_LAST_HEAL_MS, now);
        return false;
    }

    private void clearNegativeEffects(EntityPlayer player) {
        Collection<PotionEffect> effects = player.getActivePotionEffects();
        if (effects.isEmpty()) {
            return;
        }

        for (PotionEffect effect : new ArrayList<>(effects)) {
            if (Potion.potionTypes[effect.getPotionID()] != null) {
                player.removePotionEffect(effect.getPotionID());
            }
        }
    }

    private void sendError(ICommandSender sender, String message) {
        ChatComponentText msg = new ChatComponentText(message);
        msg.getChatStyle()
            .setColor(EnumChatFormatting.RED);
        sender.addChatMessage(msg);
    }
}
