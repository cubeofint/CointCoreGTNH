package coint.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

public final class ClientRestartOverlay {

    private static final long CENTER_DURATION_MS = 5000L;

    private int lastSeconds = -1;
    private long centerUntil;
    private String centerText = "";

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Text event) {
        if (!ClientRestartState.isActive()) {
            resetLocalState();
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) {
            return;
        }

        String phase = ClientRestartState.getPhase();
        int seconds = ClientRestartState.getSecondsRemaining();
        long now = System.currentTimeMillis();

        if ("countdown".equals(phase)) {
            updateCenterAnnouncement(seconds, now);
            if (centerUntil > now && !centerText.isEmpty()) {
                drawCenterAnnouncement(mc, event, centerText);
            }
            if (seconds <= 30) {
                drawTopCountdown(mc, event, seconds);
            }
            return;
        }

        lastSeconds = -1;
        centerUntil = 0L;
        centerText = "";
        drawCenterRestarting(mc, event);
    }

    private void updateCenterAnnouncement(int seconds, long now) {
        if (seconds == lastSeconds) {
            return;
        }

        int milestone = crossedMilestone(lastSeconds, seconds);
        if (milestone > 0) {
            centerText = milestoneText(milestone);
            centerUntil = now + CENTER_DURATION_MS;
        }
        lastSeconds = seconds;
    }

    private static int crossedMilestone(int previous, int current) {
        int[] milestones = { 1800, 900, 300, 60 };
        if (previous < 0) {
            for (int milestone : milestones) {
                if (current == milestone) {
                    return milestone;
                }
            }
            return 0;
        }

        for (int milestone : milestones) {
            if (previous > milestone && current <= milestone) {
                return milestone;
            }
        }
        return 0;
    }

    private static String milestoneText(int seconds) {
        switch (seconds) {
            case 1800:
                return "§c§lРЕСТАРТ ЧЕРЕЗ 30 МИНУТ";
            case 900:
                return "§c§lРЕСТАРТ ЧЕРЕЗ 15 МИНУТ";
            case 300:
                return "§c§lРЕСТАРТ ЧЕРЕЗ 5 МИНУТ";
            case 60:
                return "§c§lРЕСТАРТ ЧЕРЕЗ 1 МИНУТУ";
            default:
                return "";
        }
    }

    private static void drawCenterAnnouncement(Minecraft mc, RenderGameOverlayEvent.Text event, String text) {
        FontRenderer font = mc.fontRenderer;
        int width = event.resolution.getScaledWidth();
        int height = event.resolution.getScaledHeight();
        float scale = 1.75F;
        int textWidth = font.getStringWidth(text);
        int boxWidth = (int) (textWidth * scale) + 28;
        int boxHeight = 30;
        int left = (width - boxWidth) / 2;
        int top = height / 2 - 36;

        Gui.drawRect(left, top, left + boxWidth, top + boxHeight, 0xA0000000);
        GL11.glPushMatrix();
        GL11.glScalef(scale, scale, scale);
        int scaledWidth = (int) (width / scale);
        int x = (scaledWidth - textWidth) / 2;
        int y = (int) ((top + 8) / scale);
        font.drawStringWithShadow(text, x, y, 0xFFFFFF);
        GL11.glPopMatrix();
    }

    private static void drawTopCountdown(Minecraft mc, RenderGameOverlayEvent.Text event, int seconds) {
        FontRenderer font = mc.fontRenderer;
        String main = "§c§lРЕСТАРТ ЧЕРЕЗ §f" + format(seconds);
        String sub = "§eЗавершите важные действия";
        int width = event.resolution.getScaledWidth();
        int y = 12;
        int mainWidth = font.getStringWidth(main);
        int subWidth = font.getStringWidth(sub);
        int boxWidth = Math.max(mainWidth, subWidth) + 20;
        int left = (width - boxWidth) / 2;

        Gui.drawRect(left, y - 5, left + boxWidth, y + 27, 0xA0000000);
        font.drawStringWithShadow(main, (width - mainWidth) / 2, y, 0xFFFFFF);
        font.drawStringWithShadow(sub, (width - subWidth) / 2, y + 12, 0xFFFFFF);
    }

    private static void drawCenterRestarting(Minecraft mc, RenderGameOverlayEvent.Text event) {
        FontRenderer font = mc.fontRenderer;
        String main = "§c§lИДЁТ РЕСТАРТ";
        String sub = "§fЗайдите через пару минут";
        int width = event.resolution.getScaledWidth();
        int height = event.resolution.getScaledHeight();
        int mainWidth = font.getStringWidth(main);
        int subWidth = font.getStringWidth(sub);
        int boxWidth = Math.max(mainWidth, subWidth) + 30;
        int boxHeight = 34;
        int left = (width - boxWidth) / 2;
        int top = height / 2 - 24;

        Gui.drawRect(left, top, left + boxWidth, top + boxHeight, 0xA0000000);
        font.drawStringWithShadow(main, (width - mainWidth) / 2, top + 7, 0xFFFFFF);
        font.drawStringWithShadow(sub, (width - subWidth) / 2, top + 19, 0xFFFFFF);
    }

    private void resetLocalState() {
        lastSeconds = -1;
        centerUntil = 0L;
        centerText = "";
    }

    private static String format(int seconds) {
        int minutes = seconds / 60;
        int secs = seconds % 60;
        return String.format("%02d:%02d", minutes, secs);
    }
}
