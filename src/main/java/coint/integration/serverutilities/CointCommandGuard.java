package coint.integration.serverutilities;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.event.CommandEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import serverutils.command.CmdFly;
import serverutils.command.CmdGod;
import serverutils.command.tp.CmdTplast;
import serverutils.lib.util.permission.PermissionAPI;

/**
 * Intercepts Forge {@link CommandEvent} to guard the "target another player"
 * variants of {@code /fly}, {@code /god} and {@code /tpl}.
 *
 * <p>
 * Replaces the former {@code MixinCmdFly} / {@code MixinCmdGod} /
 * {@code MixinCmdTplast} approach. Those mixins targeted
 * {@code processCommand} with {@code remap = false}; however ForgeGradle
 * reobfuscates overrides of vanilla interface methods to their SRG name
 * (e.g. {@code func_71515_b}), so Mixin could not find the method at
 * runtime and the injection was silently skipped.
 *
 * <p>
 * {@link CommandEvent} fires <em>after</em> SU's own
 * {@code canCommandSenderUseCommand} gate but <em>before</em>
 * {@code processCommand}, making it a reliable and framework-correct
 * interception point that does not depend on bytecode method names.
 */
public class CointCommandGuard {

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        if (!(event.sender instanceof EntityPlayerMP player)) return;

        final String[] args = event.parameters;
        String node = null;

        if (event.command instanceof CmdFly && args.length > 0) {
            node = CointSUPermissions.FLY_OTHER;
        } else if (event.command instanceof CmdGod && args.length > 0) {
            node = CointSUPermissions.GOD_OTHER;
        } else if (event.command instanceof CmdTplast) {
            // /tpl <who> <to> requires explicit permission for moving another player.
            if (args.length == 2) {
                node = CointSUPermissions.TPL_OTHER;
            }

            // Block teleports to protected destinations unless sender has bypass.
            EntityPlayerMP target = cointcore$resolveTplTarget(event, args);
            if (target != null && cointcore$isProtectedTplTarget(target)
                && !PermissionAPI.hasPermission(player, CointSUPermissions.TPL_TO_PROTECTED)) {
                cointcore$deny(event, player);
                return;
            }
        }

        if (node != null && !PermissionAPI.hasPermission(player, node)) {
            cointcore$deny(event, player);
        }
    }

    private static void cointcore$deny(CommandEvent event, EntityPlayerMP player) {
        event.setCanceled(true);
        ChatComponentTranslation msg = new ChatComponentTranslation("commands.generic.permission");
        msg.getChatStyle()
            .setColor(EnumChatFormatting.RED);
        player.addChatMessage(msg);
    }

    private static EntityPlayerMP cointcore$resolveTplTarget(CommandEvent event, String[] args) {
        // /tpl <player> -> target = args[0]
        // /tpl <who> <to> -> target = args[1]
        // /tpl <x> <y> <z> -> no player target
        String targetName = null;
        if (args.length == 1) {
            targetName = args[0];
        } else if (args.length == 2) {
            targetName = args[1];
        }

        if (targetName == null) {
            return null;
        }

        try {
            return CommandBase.getPlayer(event.sender, targetName);
        } catch (CommandException ignored) {
            return null;
        }
    }

    private static boolean cointcore$isProtectedTplTarget(EntityPlayerMP target) {
        return MinecraftServer.getServer() != null && MinecraftServer.getServer()
            .getConfigurationManager()
            .func_152596_g(target.getGameProfile());
    }
}
