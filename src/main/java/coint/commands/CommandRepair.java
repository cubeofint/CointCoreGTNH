package coint.commands;

import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import coint.integration.serverutilities.CointRankConfigs;
import coint.util.ItemUtil;
import serverutils.lib.config.RankConfigAPI;
import serverutils.lib.math.Ticks;
import serverutils.lib.util.NBTUtils;
import serverutils.ranks.Ranks;

public class CommandRepair extends CommandBase {

    private static final String ARG_HAND = "hand";
    private static final String ARG_ALL = "all";
    private static final String TAG_LAST_REPAIR_MS = "cointcore_repair_last_ms";

    @Override
    public String getCommandName() {
        return "repair";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return Ranks.INSTANCE.getPermission(player.getGameProfile(), "command.cointcore.repair", false)
                .getBoolean();
        }

        return false;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/repair [hand|all]";
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, ARG_HAND, ARG_ALL);
        }
        return super.addTabCompletionOptions(sender, args);
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayer player)) {
            return;
        }

        String allowedMode = RankConfigAPI
            .get((net.minecraft.entity.player.EntityPlayerMP) player, CointRankConfigs.REPAIR_MODE)
            .getString();
        if (allowedMode == null || allowedMode.isEmpty()) {
            throw new CommandException("commands.generic.permission");
        }

        String mode = args.length == 0 ? allowedMode : args[0].toLowerCase();
        if (!ARG_HAND.equals(mode) && !ARG_ALL.equals(mode)) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        if (ARG_ALL.equals(mode) && ARG_HAND.equals(allowedMode)) {
            throw new CommandException("commands.generic.permission");
        }

        NBTTagCompound persisted = NBTUtils.getPersistedData(player, true);
        long cooldownTicks = RankConfigAPI
            .get((net.minecraft.entity.player.EntityPlayerMP) player, CointRankConfigs.REPAIR_COOLDOWN)
            .getLong();
        if (cooldownTicks > 0) {
            long now = System.currentTimeMillis();
            long lastUse = persisted.getLong(TAG_LAST_REPAIR_MS);
            long elapsed = now - lastUse;
            long cooldownMs = Ticks.get(cooldownTicks)
                .millis();
            if (lastUse > 0 && elapsed < cooldownMs) {
                long remainingSeconds = (cooldownMs - elapsed + 999L) / 1000L;
                sendError(sender, "Вы сможете починить предметы через " + formatDuration(remainingSeconds));
                return;
            }
            persisted.setLong(TAG_LAST_REPAIR_MS, now);
        }

        if (ARG_HAND.equals(mode)) {
            ItemUtil.Repair(player.getHeldItem());
            sendSuccess(sender, "Предмет в руке починен");
            return;
        }

        InventoryPlayer inventory = player.inventory;
        for (int i = 0; i < inventory.mainInventory.length; i++) {
            ItemUtil.Repair(inventory.mainInventory[i]);
        }
        for (int i = 0; i < inventory.armorInventory.length; i++) {
            ItemUtil.Repair(inventory.armorInventory[i]);
        }
        sendSuccess(sender, "Предметы в инвентаре починены");
    }

    private void sendSuccess(ICommandSender sender, String message) {
        ChatComponentText msg = new ChatComponentText(message);
        msg.getChatStyle()
            .setColor(EnumChatFormatting.GREEN);
        sender.addChatMessage(msg);
    }

    private void sendError(ICommandSender sender, String message) {
        ChatComponentText msg = new ChatComponentText(message);
        msg.getChatStyle()
            .setColor(EnumChatFormatting.RED);
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
