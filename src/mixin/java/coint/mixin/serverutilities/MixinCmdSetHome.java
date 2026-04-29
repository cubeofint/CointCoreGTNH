package coint.mixin.serverutilities;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import coint.integration.serverutilities.CointRankConfigs;
import serverutils.command.tp.CmdSetHome;
import serverutils.lib.config.ConfigValue;
import serverutils.lib.config.RankConfigAPI;

/**
 * Adds {@code cointcore.bonus_homes} on top of the base {@code serverutilities.homes.max}
 * limit enforced by {@link CmdSetHome#processCommand}.
 *
 * <p>
 * Redirects the single {@code ConfigValue.getInt()} call (the {@code maxHomes} check) inside
 * {@code processCommand} and returns {@code base + bonus}. Uses {@code @Redirect} instead of
 * {@code @Overwrite} to avoid method-lookup failures caused by {@code remap = false}.
 */
@Mixin(value = CmdSetHome.class, remap = false)
public class MixinCmdSetHome {

    @Redirect(
        method = "processCommand(Lnet/minecraft/command/ICommandSender;[Ljava/lang/String;)V",
        at = @At(value = "INVOKE", target = "Lserverutils/lib/config/ConfigValue;getInt()I"))
    private int cointcore$withBonusHomes(ConfigValue self, ICommandSender sender, String[] args) {
        int base = self.getInt();
        if (sender instanceof EntityPlayerMP player) {
            base += RankConfigAPI.get(player, CointRankConfigs.BONUS_HOMES)
                .getInt();
        }
        return base;
    }
}
