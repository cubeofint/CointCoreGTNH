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
import coint.util.TimeUtil;
import serverutils.lib.config.RankConfigAPI;
import serverutils.lib.math.Ticks;
import serverutils.lib.util.NBTUtils;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

public class CommandRepair extends CommandBase {

    public static final String PERMISSION = "cointcore.command.repair";
    private static final String ARG_HAND = "hand";
    private static final String ARG_ALL = "all";
    private static final String TAG_LAST_REPAIR_MS = "cointcore_repair_last_ms";

    public CommandRepair() {
        PermissionAPI.registerNode(PERMISSION, DefaultPermissionLevel.OP, "CointCore repair permission");
    }

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
            return PermissionAPI.hasPermission(player, PERMISSION);
        }
        return true; // console/RCON
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
                long remainingSeconds = cooldownMs - elapsed + 999L;
                sendError(sender, "Вы сможете починить предметы через " + TimeUtil.formatDuration(remainingSeconds));
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
}
