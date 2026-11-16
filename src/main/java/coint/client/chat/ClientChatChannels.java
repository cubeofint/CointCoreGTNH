package coint.client.chat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.opengl.GL11;

import coint.chat.ChatChannel;
import coint.chat.PacketChatChannel;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** The full history stays in GuiNewChat, including lines hidden by the selected tab. */
@SideOnly(Side.CLIENT)
public final class ClientChatChannels {

    private static final String[] LABELS = { "all", "local", "global", "trade", "private" };
    private static final int[] COLORS = { 0xFFFFFFFF, 0xFF55FF55, 0xFFFFAA00, 0xFF55FFFF, 0xFFFF55FF };
    private static ChatChannel.View view = ChatChannel.View.ALL;
    private static ChatChannel pending;
    private static boolean active;

    public static boolean active() {
        return active;
    }

    public static ChatChannel.View view() {
        return view;
    }

    public static ChatChannel pending() {
        return pending;
    }

    public static void receive(PacketChatChannel packet) {
        Minecraft mc = Minecraft.getMinecraft();
        // Drop a queued packet if the connection was replaced while it waited for the client thread.
        Object connection = mc.getNetHandler();
        mc.func_152344_a(() -> {
            if (connection == null || connection != mc.getNetHandler()) return;
            if (packet.channel == -1) {
                active = true;
                return;
            }
            if (packet.channel < 0 || packet.channel >= ChatChannel.values().length) return;
            IChatComponent component = IChatComponent.Serializer.func_150699_a(packet.json);
            if (component == null) return;
            active = true;
            ChatChannel previous = pending;
            pending = ChatChannel.values()[packet.channel];
            try {
                // Same event as vanilla: BQ can still turn its marker into a clickable quest link.
                ClientChatReceivedEvent event = new ClientChatReceivedEvent(component);
                if (!MinecraftForge.EVENT_BUS.post(event) && event.message != null) {
                    mc.ingameGUI.getChatGUI()
                        .printChatMessage(event.message);
                }
            } finally {
                pending = previous;
            }
        });
    }

    public static void setView(ChatChannel.View next) {
        if (!active || next == view) return;
        view = next;
        Minecraft.getMinecraft().ingameGUI.getChatGUI()
            .refreshChat();
    }

    public static boolean blockPrivate(String message) {
        return active && view == ChatChannel.View.PRIVATE && !explicit(message);
    }

    private static boolean explicit(String message) {
        return message.startsWith("/") || message.startsWith("!")
            || message.startsWith("$")
            || message.startsWith("betterquesting.msg.sharequest:");
    }

    public static String route(String message) {
        if (!active || explicit(message)) return message;
        if (view == ChatChannel.View.GLOBAL) return "!" + message;
        if (view == ChatChannel.View.TRADE) return "$" + message;
        return message;
    }

    public static void privateHint() {
        Minecraft.getMinecraft().ingameGUI.getChatGUI()
            .printChatMessage(new ChatComponentTranslation("cointcore.chat.privateHint"));
    }

    private static int y(GuiChat screen) {
        var chat = Minecraft.getMinecraft().ingameGUI.getChatGUI();
        return Math.max(
            2,
            screen.height - 28
                - (int) (chat.func_146232_i() * 9 * chat.func_146244_h())
                - Minecraft.getMinecraft().fontRenderer.FONT_HEIGHT
                - 8);
    }

    public static void render(GuiChat screen) {
        if (!active) return;
        var font = Minecraft.getMinecraft().fontRenderer;
        int width = 2;
        for (String key : LABELS) width += font.getStringWidth(I18n.format("cointcore.chat.tab." + key)) + 6;
        int top = y(screen);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        Gui.drawRect(2, top, 2 + width, top + font.FONT_HEIGHT + 5, 0xDD000000);
        int x = 6;
        for (int i = 0; i < LABELS.length; i++) {
            String label = I18n.format("cointcore.chat.tab." + LABELS[i]);
            int w = font.getStringWidth(label);
            boolean selected = view.ordinal() == i;
            font.drawStringWithShadow(label, x, top + 2, selected ? COLORS[i] : (COLORS[i] & 0xFFFFFF) | 0x88000000);
            if (selected) Gui.drawRect(x, top + font.FONT_HEIGHT + 2, x + w, top + font.FONT_HEIGHT + 3, COLORS[i]);
            x += w + 6;
        }
        GL11.glPopAttrib();
    }

    public static boolean click(GuiChat screen, int mouseX, int mouseY, int button) {
        if (!active || button != 0) return false;
        var font = Minecraft.getMinecraft().fontRenderer;
        int top = y(screen);
        if (mouseY < top || mouseY >= top + font.FONT_HEIGHT + 5) return false;
        int x = 6;
        for (int i = 0; i < LABELS.length; i++) {
            int w = font.getStringWidth(I18n.format("cointcore.chat.tab." + LABELS[i]));
            if (mouseX >= x - 2 && mouseX < x + w + 2) {
                setView(ChatChannel.View.values()[i]);
                return true;
            }
            x += w + 6;
        }
        return false;
    }

    @SubscribeEvent
    public void disconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minecraft.getMinecraft()
            .func_152344_a(() -> {
                active = false;
                view = ChatChannel.View.ALL;
                pending = null;
                Minecraft.getMinecraft().ingameGUI.getChatGUI()
                    .refreshChat();
            });
    }
}
