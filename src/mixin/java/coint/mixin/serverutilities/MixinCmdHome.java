package coint.mixin.serverutilities;

import net.minecraft.command.ICommandSender;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import coint.integration.serverutilities.CointRankConfigs;
import serverutils.command.tp.CmdHome;
import serverutils.lib.config.ConfigValue;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;

/**
 * Fixes the {@code /home list} display so it shows {@code size / (base + bonus)}
 * rather than just the base {@code serverutilities.homes.max} value.
 *
 * <p>
 * Redirects the call to {@code ConfigValue.getInt()} inside {@code CmdHome.processCommand}
 * and adds the player's {@code cointcore.bonus_homes} value.
 *
 * <p>
 * Note: {@code sender} here is the player who issued the command. For the {@code /home list}
 * self-view this is the target player. For admin {@code /home list <name>} the display
 * reflects the sender's own bonus — a minor cosmetic trade-off.
 */
@Mixin(value = CmdHome.class, remap = false)
public class MixinCmdHome {

    @Redirect(
        method = "processCommand(Lnet/minecraft/command/ICommandSender;[Ljava/lang/String;)V",
        at = @At(value = "INVOKE", target = "Lserverutils/lib/config/ConfigValue;getInt()I"))
    @SuppressWarnings("unused")
    private int cointcore$withBonusHomesDisplay(ConfigValue v, ICommandSender sender, String[] args) {
        int base = v.getInt();
        ForgePlayer player = Universe.get()
            .getPlayer(sender);
        if (player != null) {
            base += player.getRankConfig(CointRankConfigs.BONUS_HOMES)
                .getInt();
        }
        return base;
    }
}
