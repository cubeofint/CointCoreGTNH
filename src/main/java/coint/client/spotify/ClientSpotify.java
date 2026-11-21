package coint.client.spotify;

import java.io.File;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;

import org.lwjgl.input.Keyboard;

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
        int w = Math.min(210, event.resolution.getScaledWidth() / 3);
        int x = event.resolution.getScaledWidth() - w - 6;
        Gui.drawRect(x, 6, x + w, 42, 0xB0101614);
        mc.fontRenderer
            .drawStringWithShadow(mc.fontRenderer.trimStringToWidth(song.title, w - 12), x + 6, 11, 0xFFFFFF);
        String second = song.playing ? song.artist : I18n.format("cointcore.spotify.paused");
        mc.fontRenderer.drawStringWithShadow(mc.fontRenderer.trimStringToWidth(second, w - 12), x + 6, 23, 0xB9C7BD);
        Gui.drawRect(x + 6, 36, x + w - 6, 38, 0xFF34453B);
        if (song.duration > 0)
            Gui.drawRect(x + 6, 36, x + 6 + (int) ((w - 12) * song.position / song.duration), 38, 0xFF1ED760);
    }
}
