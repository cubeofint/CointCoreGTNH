package coint.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

public final class ClientRestartOverlay {

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Text event) {
        if (!ClientRestartState.isActive()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) {
            return;
        }

        String phase = ClientRestartState.getPhase();
        int seconds = ClientRestartState.getSecondsRemaining();
        String main;
        String sub = "";

        if ("backup".equals(phase)) {
            main = "§c§lСЕРВЕР ПЕРЕЗАПУСКАЕТСЯ";
            sub = "§eСоздание резервной копии...";
        } else if ("saving".equals(phase)) {
            main = "§c§lСЕРВЕР ПЕРЕЗАПУСКАЕТСЯ";
            sub = "§eСохранение миров...";
        } else if ("stopping".equals(phase)) {
            main = "§c§lПЕРЕЗАПУСК СЕРВЕРА";
            sub = "§aРезервная копия готова";
        } else {
            main = "§c§lРЕСТАРТ ЧЕРЕЗ §f" + format(seconds);
            if (seconds <= 60) {
                sub = "§eЗавершите важные действия";
            }
        }

        FontRenderer font = mc.fontRenderer;
        int width = event.resolution.getScaledWidth();
        int y = 12;
        int mainWidth = font.getStringWidth(main);
        int subWidth = sub.isEmpty() ? 0 : font.getStringWidth(sub);
        int boxWidth = Math.max(mainWidth, subWidth) + 20;
        int boxHeight = sub.isEmpty() ? 20 : 32;
        int left = (width - boxWidth) / 2;

        Gui.drawRect(left, y - 5, left + boxWidth, y - 5 + boxHeight, 0xA0000000);
        font.drawStringWithShadow(main, (width - mainWidth) / 2, y, 0xFFFFFF);
        if (!sub.isEmpty()) {
            font.drawStringWithShadow(sub, (width - subWidth) / 2, y + 12, 0xFFFFFF);
        }
    }

    private static String format(int seconds) {
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, secs);
        }
        return String.format("%02d:%02d", minutes, secs);
    }
}
