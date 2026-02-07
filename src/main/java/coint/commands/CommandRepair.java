package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ChatComponentText;

import coint.util.ItemUtil;
import serverutils.ranks.Ranks;

public class CommandRepair extends CommandBase {

    @Override
    public String getCommandName() {
        return "repair";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            String v = Ranks.INSTANCE.getPermission(player.getGameProfile(), "coint.repair", false)
                .getString();
            if (v == null) return false;
            return v.equals("1") | v.equals("2");
        } else {
            sender.addChatMessage(new ChatComponentText("Console doesn't have an inventory"));
            return false;
        }
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/repair";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        EntityPlayer player = (EntityPlayer) sender;
        String permission = Ranks.INSTANCE.getPermission(player.getGameProfile(), "coint.repair", false)
            .getString();
        if (permission.equals("1")) ItemUtil.Repair(player.getHeldItem());
        else if (permission.equals("2")) {
            InventoryPlayer inventory = player.inventory;
            for (int i = 0; i < inventory.mainInventory.length; i++) {
                ItemUtil.Repair(inventory.mainInventory[i]);
            }
            for (int i = 0; i < inventory.armorInventory.length; i++) {
                ItemUtil.Repair(inventory.armorInventory[i]);
            }
        }
    }


}
