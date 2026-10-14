package coint.client;

import java.util.Locale;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import coint.CointConfig;
import coint.CointCore;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Colors too bright and washed out on AMD cards: sRGB applied twice. Logs once what the driver gave (vendor, the
 * window framebuffer's color encoding, whether sRGB writes are on), and on AMD keeps sRGB writes off around each frame.
 */
@SideOnly(Side.CLIENT)
public final class SrgbGuard {

    private static final int GL_FRAMEBUFFER_SRGB = 0x8DB9;
    private static final int GL_SRGB = 0x8C40;
    private static final int GL_LINEAR = 0x2601;

    private boolean inspected;
    private boolean amd;
    private boolean reportedStart, reportedEnd;

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (!inspected) {
            inspected = true;
            inspect();
        }
        if (!amd || !CointConfig.general.amdSrgbFix) {
            return;
        }
        if (!GL11.glIsEnabled(GL_FRAMEBUFFER_SRGB)) {
            return;
        }
        boolean start = event.phase == TickEvent.Phase.START;
        if (start ? !reportedStart : !reportedEnd) {
            if (start) {
                reportedStart = true;
            } else {
                reportedEnd = true;
            }
            CointCore.LOG.warn(
                "[CointCore] sRGB writes were left on at frame {} (some mod enabled GL_FRAMEBUFFER_SRGB); turning them off",
                start ? "start" : "end");
        }
        GL11.glDisable(GL_FRAMEBUFFER_SRGB);
    }

    private void inspect() {
        try {
            String vendor = String.valueOf(GL11.glGetString(GL11.GL_VENDOR));
            String renderer = String.valueOf(GL11.glGetString(GL11.GL_RENDERER));
            String version = String.valueOf(GL11.glGetString(GL11.GL_VERSION));
            String lower = (vendor + " " + renderer).toLowerCase(Locale.ROOT);
            amd = lower.contains("amd") || lower.contains("ati ") || lower.contains("radeon");
            boolean srgbOn = GL11.glIsEnabled(GL_FRAMEBUFFER_SRGB);
            String encoding = "unknown";
            if (GLContext.getCapabilities().OpenGL30) {
                int previous = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
                int value = GL30.glGetFramebufferAttachmentParameteri(
                    GL30.GL_DRAW_FRAMEBUFFER,
                    GL11.GL_BACK_LEFT,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previous);
                encoding = value == GL_SRGB ? "sRGB"
                    : value == GL_LINEAR ? "linear" : "0x" + Integer.toHexString(value);
            }
            while (GL11.glGetError() != GL11.GL_NO_ERROR) {
                // Leave no error behind for the next caller.
            }
            CointCore.LOG.info(
                "[CointCore] GPU: {} | {} | GL {} | window framebuffer encoding: {} | sRGB writes on: {} | AMD sRGB fix: {}",
                vendor,
                renderer,
                version,
                encoding,
                srgbOn,
                amd && CointConfig.general.amdSrgbFix ? "active" : "off");
            if (amd && CointConfig.general.amdSrgbFix && srgbOn) {
                GL11.glDisable(GL_FRAMEBUFFER_SRGB);
            }
        } catch (RuntimeException | LinkageError e) {
            CointCore.LOG.warn("[CointCore] Could not inspect the GL framebuffer", e);
        }
    }
}
