package coint.commands;

import java.util.Collections;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
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
    private static final String MODE_ALL = "all";
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
        return "/repair";
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        return Collections.emptyList();
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayer player)) {
            return;
        }

        String mode = RankConfigAPI
            .get((net.minecraft.entity.player.EntityPlayerMP) player, CointRankConfigs.REPAIR_MODE)
            .getString();
        if (mode == null || mode.isEmpty()) {
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

        InventoryPlayer inventory = player.inventory;
        for (int i = 0; i < inventory.mainInventory.length; i++) {
            ItemUtil.Repair(inventory.mainInventory[i]);
        }

        if (MODE_ALL.equals(mode)) {
            for (int i = 0; i < inventory.armorInventory.length; i++) {
                ItemUtil.Repair(inventory.armorInventory[i]);
            }
            sendSuccess(sender, "Предметы в инвентаре и броня починены");
        } else {
            sendSuccess(sender, "Предметы в инвентаре починены");
        }
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
