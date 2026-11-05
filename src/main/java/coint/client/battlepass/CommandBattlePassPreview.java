package coint.client.battlepass;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Client command {@code /bptest}: opens the battle pass prototype. The window opens on the next tick, once the chat
 * that ran the command has closed.
 */
@SideOnly(Side.CLIENT)
public class CommandBattlePassPreview extends CommandBase {

    private static boolean pending;

    @Override
    public String getCommandName() {
        return "bptest";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/bptest";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        pending = true;
    }

    /** Opens the window requested by the command. */
    public static final class Opener {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END && pending) {
                pending = false;
                Minecraft.getMinecraft()
                    .displayGuiScreen(new GuiBattlePassPreview());
            }
        }
    }
}
