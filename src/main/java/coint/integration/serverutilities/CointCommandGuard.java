package coint.integration.serverutilities;

import net.minecraft.entity.player.EntityPlayerMP;
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
        } else if (event.command instanceof CmdTplast && args.length == 2) {
            node = CointSUPermissions.TPL_OTHER;
        }

        if (node != null && !PermissionAPI.hasPermission(player, node)) {
            event.setCanceled(true);
            ChatComponentTranslation msg = new ChatComponentTranslation("commands.generic.permission");
            msg.getChatStyle()
                .setColor(EnumChatFormatting.RED);
            player.addChatMessage(msg);
        }
    }
}
