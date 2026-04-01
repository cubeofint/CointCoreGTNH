package coint.mixin.dreamcraft;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

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

    @Unique
    private static final Pattern COINTCORE_URL_PATTERN = Pattern.compile("https?://\\S+");

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
            event.player.addChatMessage(cointcore$buildLoginComponent(line));
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

    @Unique
    private static IChatComponent cointcore$buildLoginComponent(String line) {
        ChatComponentText root = new ChatComponentText("");
        Matcher matcher = COINTCORE_URL_PATTERN.matcher(line);
        int cursor = 0;

        while (matcher.find()) {
            if (matcher.start() > cursor) {
                root.appendSibling(new ChatComponentText(line.substring(cursor, matcher.start())));
            }

            String matched = matcher.group();
            int end = matched.length();
            while (end > 0 && cointcore$isTrailingPunctuation(matched.charAt(end - 1))) {
                end--;
            }

            String url = matched.substring(0, end);
            String trailing = matched.substring(end);

            if (!url.isEmpty()) {
                ChatComponentText link = new ChatComponentText(url);
                ChatStyle style = new ChatStyle().setUnderlined(true)
                    .setColor(EnumChatFormatting.AQUA)
                    .setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                    .setChatHoverEvent(
                        new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText("Открыть ссылку")));
                link.setChatStyle(style);
                root.appendSibling(link);
            }

            if (!trailing.isEmpty()) {
                root.appendSibling(new ChatComponentText(trailing));
            }

            cursor = matcher.end();
        }

        if (cursor < line.length()) {
            root.appendSibling(new ChatComponentText(line.substring(cursor)));
        }

        return root;
    }

    @Unique
    private static boolean cointcore$isTrailingPunctuation(char c) {
        return c == '.' || c == ',' || c == '!' || c == '?' || c == ':' || c == ';' || c == ')' || c == ']';
    }
}
