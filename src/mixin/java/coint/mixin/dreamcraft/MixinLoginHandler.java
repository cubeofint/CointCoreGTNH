package coint.mixin.dreamcraft;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;

import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.Tags;
import coint.config.CointConfig;
import cpw.mods.fml.common.gameevent.PlayerEvent;

/**
 * Replaces NewHorizonsCoreMod welcome chat lines with values from CointCore config.
 * Keeps the original Open to LAN warning text and condition unchanged.
 */
@Pseudo
@Mixin(targets = "com.dreammaster.loginhandler.LoginHandler", remap = false)
public class MixinLoginHandler {

    @Dynamic("Target method exists in NewHorizonsCoreMod and is not available in this compile classpath")
    @Inject(method = "onPlayerLogin", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    @SuppressWarnings("unused")
    private void cointcore$replaceWelcome(PlayerEvent.PlayerLoggedInEvent event, CallbackInfo ci) {
        if (!CointConfig.loginMessageOverrideEnabled) {
            return;
        }

        String[] lines = CointConfig.loginMessageLines;
        if (lines == null || lines.length == 0) {
            return;
        }

        String playerName = event.player.getCommandSenderName();
        for (String rawLine : lines) {
            String line = cointcore$applyPlaceholders(rawLine, playerName);
            event.player.addChatMessage(new ChatComponentText(line));
        }

        // Keep original Open to LAN warning semantics and translation key.
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null && server.isSinglePlayer()
            && !event.player.getGameProfile()
                .getName()
                .equals(server.getServerOwner())) {
            event.player.addChatMessage(new ChatComponentTranslation("dreamcraft.welcome.open_to_lan"));
        }

        ci.cancel();
    }

    @Unique
    private static String cointcore$applyPlaceholders(String rawLine, String playerName) {
        String line = rawLine == null ? "" : rawLine;
        line = line.replace("%player%", playerName);
        line = line.replace("%mod_version%", Tags.VERSION);
        return line.replace('&', (char) 167);
    }
}
