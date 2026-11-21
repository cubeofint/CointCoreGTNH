package coint.client.spotify;

import java.io.File;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** All settings and controls belong to this client, including when connected to another node. */
public final class ClientSpotify extends CommandBase {

    static final SpotifyBridge BRIDGE = new SpotifyBridge();
    private static Configuration config;
    static boolean enabled;
    static boolean hud;
    static SpotifyHudLayout layout = new SpotifyHudLayout(1, 0, 1);
    static final SpotifyHudInput INPUT = new SpotifyHudInput(BRIDGE::command, ClientSpotify::saveLayout);
    private boolean open;
    private final KeyBinding key = new KeyBinding("cointcore.spotify.key", Keyboard.KEY_NONE, "CointCoreGTNH");

    public static void init() {
        loadSettings(new File(Minecraft.getMinecraft().mcDataDir, "config/cointcore-spotify.cfg"));
        ClientSpotify events = new ClientSpotify();
        ClientCommandHandler.instance.registerCommand(events);
        ClientRegistry.registerKeyBinding(events.key);
        FMLCommonHandler.instance()
            .bus()
            .register(events);
        MinecraftForge.EVENT_BUS.register(events);
        if (enabled) BRIDGE.start();
        Runtime.getRuntime()
            .addShutdownHook(new Thread(BRIDGE::stop, "CointCore-Spotify-shutdown"));
    }

    static void loadSettings(File file) {
        config = new Configuration(file);
        config.load();
        enabled = config.getBoolean("enabled", "client", false, "Opt-in local Spotify controls (Windows 10/11 only).");
        hud = config.getBoolean("showHud", "client", true, "Show the current song while the integration is enabled.");
        layout = new SpotifyHudLayout(
            config.get("client", "hudX", 1.0, "Horizontal position from 0 (left) to 1 (right).")
                .getDouble(1),
            config.get("client", "hudY", 0.0, "Vertical position from 0 (top) to 1 (bottom).")
                .getDouble(0),
            config.get("client", "hudScale", 1.0, "Widget scale; adjustable in /spotify.")
                .getDouble(1));
        config.save();
    }

    static void saveLayout(SpotifyHudLayout value) {
        layout = value.copy();
        config.get("client", "hudX", 1.0)
            .set(layout.x);
        config.get("client", "hudY", 0.0)
            .set(layout.y);
        config.get("client", "hudScale", 1.0)
            .set(layout.scale);
        config.save();
    }

    static void setEnabled(boolean value) {
        enabled = value;
        config.get("client", "enabled", false)
            .set(value);
        config.save();
        if (value) BRIDGE.start();
        else BRIDGE.stop();
    }

    static void toggleHud() {
        hud = !hud;
        config.get("client", "showHud", true)
            .set(hud);
        config.save();
    }

    @Override
    public String getCommandName() {
        return "spotify";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/spotify";
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
        open = true;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (!enabled || !"ready".equals(BRIDGE.snapshot().status)) SpotifyCoverTexture.clear();
        boolean pressed = key.isPressed();
        if (open || (pressed && mc.currentScreen == null)) {
            open = false;
            mc.displayGuiScreen(new GuiSpotify(mc.currentScreen));
        }
    }

    @SubscribeEvent
    public void overlay(RenderGameOverlayEvent.Text event) {
        if (!enabled || !hud) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.thePlayer == null) return;
        SpotifyBridge.Snapshot song = BRIDGE.snapshot();
        if (!"ready".equals(song.status)) return;
        SpotifyHud.draw(
            mc.fontRenderer,
            layout.bounds(event.resolution.getScaledWidth(), event.resolution.getScaledHeight()),
            song,
            false,
            System.currentTimeMillis());
    }

    private static boolean chatActive() {
        return enabled && hud && "ready".equals(BRIDGE.snapshot().status);
    }

    public static void drawChat(GuiChat chat, int mouseX, int mouseY) {
        if (!chatActive()) return;
        Minecraft mc = Minecraft.getMinecraft();
        SpotifyHudLayout.Bounds box = layout.bounds(chat.width, chat.height);
        int hover = SpotifyHudControls.hit(box, mouseX, mouseY);
        SpotifyHud.draw(mc.fontRenderer, box, BRIDGE.snapshot(), true, System.currentTimeMillis(), hover);
        String key = switch (hover) {
            case SpotifyHudControls.PREVIOUS -> "previous";
            case SpotifyHudControls.PLAY -> BRIDGE.snapshot().playing ? "pause" : "play";
            case SpotifyHudControls.NEXT -> "next";
            case SpotifyHudControls.SHUFFLE -> "shuffle";
            case SpotifyHudControls.REPEAT -> "repeat";
            case SpotifyHudControls.MUTE -> "mute";
            case SpotifyHudControls.SEEK -> "seek";
            case SpotifyHudControls.VOLUME -> "volumeHint";
            case SpotifyHudControls.RESIZE -> "resizeHint";
            case SpotifyHudControls.MOVE -> "dragHint";
            default -> "";
        };
        if (!key.isEmpty()) {
            String text = mc.fontRenderer.trimStringToWidth(I18n.format("cointcore.spotify." + key), chat.width - 12);
            int w = mc.fontRenderer.getStringWidth(text);
            int x = Math.max(4, Math.min(mouseX, chat.width - w - 8));
            int y = box.y + box.height + 5;
            if (y + 14 > chat.height) y = Math.max(4, box.y - 15);
            Gui.drawRect(x - 3, y - 3, x + w + 3, y + 11, 0xE0101813);
            mc.fontRenderer.drawStringWithShadow(text, x, y, 0xD9F5E3);
        }
    }

    public static boolean chatMouse(GuiChat chat) {
        if (!chatActive()) {
            chatClosed();
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int x = Mouse.getEventX() * chat.width / Math.max(1, mc.displayWidth);
        int y = chat.height - Mouse.getEventY() * chat.height / Math.max(1, mc.displayHeight) - 1;
        return INPUT.mouse(
            layout,
            BRIDGE.snapshot(),
            x,
            y,
            Mouse.getEventButton(),
            Mouse.getEventButtonState(),
            Mouse.getEventDWheel(),
            chat.width,
            chat.height,
            System.currentTimeMillis());
    }

    public static void chatClosed() {
        INPUT.finish(layout, BRIDGE.snapshot(), System.currentTimeMillis());
    }
}
