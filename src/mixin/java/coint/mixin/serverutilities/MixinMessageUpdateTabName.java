package coint.mixin.serverutilities;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import serverutils.net.MessageUpdateTabName;

/**
 * Adjusts the tab-list name processing in {@link MessageUpdateTabName#onMessage()}.
 *
 * <p>
 * SU's default stripping only removes {@code <} and {@code >} from the display name
 * (via {@code replaceAll("[<>]", "")}). This is designed for the default
 * {@code name.format = <{name}>} pattern: the angle-brackets wrap the name in chat
 * but get stripped for the tab list.
 *
 * <p>
 * We use a different convention — {@code name.format = &f[Игрок]&r {name}:} — where
 * the trailing {@code :} acts as a chat separator ({@code [Игрок] mawlee: message}) but
 * should not appear in the tab list. This mixin additionally strips a trailing colon
 * (with optional surrounding whitespace) so that:
 * <ul>
 * <li><b>chat</b>: {@code [Игрок] mawlee: message}</li>
 * <li><b>tab list</b>: {@code [Игрок] mawlee}</li>
 * </ul>
 *
 * <p>
 * The redirect replaces the single {@code String.replaceAll("[<>]", "")} call inside
 * {@code onMessage()} with an equivalent that also trims any trailing {@code :} and
 * surrounding whitespace.
 */
@Mixin(value = MessageUpdateTabName.class, remap = false)
public class MixinMessageUpdateTabName {

    /**
     * Replaces the built-in {@code "[<>]"} strip with an extended version that also
     * removes a trailing colon (and any adjacent whitespace).
     *
     * <p>
     * Example (format {@code §f[Игрок]§r mawlee: }):
     * <ol>
     * <li>{@code replaceAll("[<>]", "")} → {@code §f[Игрок]§r mawlee: }</li>
     * <li>{@code replaceAll(":\\s*$", "")} → {@code §f[Игрок]§r mawlee}</li>
     * <li>{@code trim()} → {@code §f[Игрок]§r mawlee}</li>
     * </ol>
     */
    @Redirect(
        method = "onMessage",
        at = @At(
            value = "INVOKE",
            target = "Ljava/lang/String;replaceAll(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"))
    @SideOnly(Side.CLIENT)
    @SuppressWarnings("unused")
    private String cointcore$stripTabName(String instance, String regex, String replacement) {
        return instance.replaceAll("[<>]", "")
            .replaceAll(":\\s*$", "")
            .trim();
    }
}
