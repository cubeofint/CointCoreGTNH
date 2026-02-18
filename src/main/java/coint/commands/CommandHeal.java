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
import serverutils.lib.config.RankConfigAPI;
import serverutils.lib.math.Ticks;
import serverutils.lib.util.NBTUtils;
import serverutils.ranks.Ranks;

public class CommandHeal extends CommandBase {

    private static final String TAG_LAST_HEAL_MS = "cointcore_heal_last_ms";

    @Override
    public String getCommandName() {
        return "heal";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return Ranks.INSTANCE.getPermission(player.getGameProfile(), "command.cointcore.heal", false)
                .getBoolean();
        }
        return false;
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
        sendSuccess(sender, "Здоровье восстановлено, все эффекты сняты");
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
            long remainingSeconds = (cooldownMs - elapsed + 999L) / 1000L;
            sendError(sender, "Вы сможете исцелиться через " + formatDuration(remainingSeconds));
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

    private void sendSuccess(ICommandSender sender, String message) {
        ChatComponentText msg = new ChatComponentText(message);
        msg.getChatStyle()
            .setColor(EnumChatFormatting.GREEN);
        sender.addChatMessage(msg);
    }

    private String formatDuration(long seconds) {
        if (seconds <= 0) {
            return "0 секунд";
        }

        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;

        if (minutes > 0 && remainingSeconds > 0) {
            return formatUnit(minutes, "минута", "минуты", "минут") + " "
                + formatUnit(remainingSeconds, "секунда", "секунды", "секунд");
        }
        if (minutes > 0) {
            return formatUnit(minutes, "минута", "минуты", "минут");
        }
        return formatUnit(remainingSeconds, "секунда", "секунды", "секунд");
    }

    private String formatUnit(long value, String one, String few, String many) {
        long mod100 = value % 100L;
        long mod10 = value % 10L;
        String word;

        if (mod100 >= 11L && mod100 <= 14L) {
            word = many;
        } else if (mod10 == 1L) {
            word = one;
        } else if (mod10 >= 2L && mod10 <= 4L) {
            word = few;
        } else {
            word = many;
        }

        return value + " " + word;
    }
}
