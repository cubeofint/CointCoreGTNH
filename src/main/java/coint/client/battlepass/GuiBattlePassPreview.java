package coint.client.battlepass;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Look-only prototype of the battle pass window, built from the designer's export: no server, no saved progress.
 * Everything is laid out in the design's own pixels (669x370) and scaled to fit the screen, so the layout matches
 * the mockup at any GUI scale. Clicking a reward that can be taken marks it taken, until the window is closed.
 */
@SideOnly(Side.CLIENT)
public class GuiBattlePassPreview extends GuiScreen {

    private static final int W = 669;
    private static final int H = 370;
    private static final int TEXT = 0xEAF0FF;
    private static final int SUBTEXT = 0x9FB2E6;

    private static final int LEVEL = 3;
    private static final int XP = 500;
    private static final int XP_NEXT = 1000;

    private static final ResourceLocation FRAME = tex("frame");
    private static final ResourceLocation LOGO = tex("logo");
    private static final ResourceLocation TITLE = tex("title");
    private static final ResourceLocation PANEL_LEVEL = tex("panel_level");
    private static final ResourceLocation PANEL_PREMIUM = tex("panel_premium");
    private static final ResourceLocation TIMER_BOX = tex("timer_box");
    private static final ResourceLocation OCTAGON = tex("octagon");
    private static final ResourceLocation SIDE_FREE = tex("side_free");
    private static final ResourceLocation SIDE_PREMIUM = tex("side_premium");
    private static final ResourceLocation SEPARATOR = tex("separator");
    private static final ResourceLocation PREMIUM_LOGO = tex("premium_logo");
    private static final ResourceLocation PREMIUM_TITLE = tex("premium_title");
    private static final ResourceLocation PREMIUM_DESC = tex("premium_desc");
    private static final ResourceLocation TIMER_LABEL = tex("timer_label");
    private static final ResourceLocation FREE_LABEL = tex("free_label");
    private static final ResourceLocation ICON_LOCK_FREE = tex("icon_lock_free");
    private static final ResourceLocation ICON_LOCK_PREMIUM = tex("icon_lock_premium");
    private static final ResourceLocation ICON_CROWN = tex("icon_crown");
    private static final ResourceLocation ICON_TIMER = tex("icon_timer");
    private static final ResourceLocation ICON_LOCK_SMALL = tex("icon_lock_small");
    private static final ResourceLocation CELL = tex("cell");
    private static final ResourceLocation CELL_FINAL = tex("cell_final");
    private static final ResourceLocation BUTTON = tex("button");

    /** Rewards of levels 1..10, free row and premium row. */
    private final ItemStack[] free = rewards(1);
    private final ItemStack[] premium = rewards(2);
    private final boolean[] freeTaken = new boolean[10];
    private final boolean[] premiumTaken = new boolean[10];
    private boolean premiumOwned;

    private float scale;
    private int left, top;

    private static ResourceLocation tex(String name) {
        return new ResourceLocation("cointcore", "textures/gui/battlepass/" + name + ".png");
    }

    private static ItemStack[] rewards(int mul) {
        return new ItemStack[] { new ItemStack(Items.diamond, mul), new ItemStack(Items.redstone, 256),
            new ItemStack(Items.golden_apple, mul), new ItemStack(Items.ender_pearl, 4 * mul),
            new ItemStack(Items.iron_ingot, 64), new ItemStack(Items.coal, 64),
            new ItemStack(Items.glowstone_dust, 128), new ItemStack(Items.emerald, 32),
            new ItemStack(Items.nether_star, 4 * mul), new ItemStack(Blocks.dragon_egg, mul) };
    }

    @Override
    public void initGui() {
        scale = Math.min(1.0F, Math.min((width - 16) / (float) W, (height - 16) / (float) H));
        left = (int) ((width - W * scale) / 2);
        top = (int) ((height - H * scale) / 2);
        freeTaken[0] = true;
        freeTaken[1] = true;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // --- coordinates

    private int dx(int mouseX) {
        return (int) ((mouseX - left) / scale);
    }

    private int dy(int mouseY) {
        return (int) ((mouseY - top) / scale);
    }

    private static boolean inside(int x, int y, int rx, int ry, int rw, int rh) {
        return x >= rx && y >= ry && x < rx + rw && y < ry + rh;
    }

    private static int cellX(int i) {
        return i < 9 ? 90 + i * 53 : 581;
    }

    private static int cellW(int i) {
        return i < 9 ? 46 : 74;
    }

    // --- drawing

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int mx = dx(mouseX), my = dy(mouseY);

        GL11.glPushMatrix();
        GL11.glTranslatef(left, top, 0);
        GL11.glScalef(scale, scale, 1);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1, 1, 1, 1);

        drawTex(FRAME, 0, 0, W, H);
        drawHeader();
        drawLevelPanel();
        drawPremiumPanel(mx, my);
        drawTimer();
        drawTex(SIDE_FREE, 14, 135, 66, 105);
        drawTex(SIDE_PREMIUM, 14, 240, 66, 105);
        drawTex(ICON_LOCK_FREE, 41, 164, 12, 13);
        drawTex(FREE_LABEL, 21, 186, 53, 19);
        drawTex(ICON_LOCK_PREMIUM, 41, 261, 12, 13);
        drawCentered("ПРЕМИУМ", 47, 283, TEXT);
        drawCentered("ПРОПУСК", 47, 294, TEXT);
        drawTex(ICON_CROWN, 40, 319, 14, 12);
        drawTex(SEPARATOR, 81, 241, 579, 2);

        ItemStack hovered = null;
        for (int i = 0; i < 10; i++) {
            drawCentered(String.valueOf(i + 1), cellX(i) + cellW(i) / 2, 141, 0xFFFFFF);
            if (drawCell(i, 158, free[i], i < LEVEL, freeTaken[i], mx, my)) {
                hovered = free[i];
            }
            boolean premiumOpen = premiumOwned && i < LEVEL;
            if (drawCell(i, 249, premium[i], premiumOpen, premiumTaken[i], mx, my)) {
                hovered = premium[i];
            }
            if (!premiumOwned && i < 9) {
                drawTex(ICON_LOCK_SMALL, cellX(i) + 34, 253, 7, 8);
            }
        }
        GL11.glPopMatrix();

        if (hovered != null) {
            renderToolTip(hovered, mouseX, mouseY);
        } else if (!premiumOwned && inside(mx, my, 551, 78, 90, 30)) {
            List<String> lines = new ArrayList<>();
            lines.add("§eПремиум пропуск");
            lines.add("§7В прототипе просто открывает премиум-награды.");
            drawHoveringText(lines, mouseX, mouseY, fontRendererObj);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawHeader() {
        drawTex(LOGO, 22, 4, 61, 61);
        drawTex(TITLE, 87, 19, 241, 20);
        fontRendererObj.drawString("Выполняй задания, получай награды и открывай новые горизонты!", 87, 41, SUBTEXT);
    }

    private void drawLevelPanel() {
        drawTex(PANEL_LEVEL, 20, 67, 292, 52);
        drawTex(OCTAGON, 26, 71, 45, 45);
        GL11.glPushMatrix();
        GL11.glTranslatef(48.5F, 87, 0);
        GL11.glScalef(2, 2, 1);
        String level = String.valueOf(LEVEL);
        fontRendererObj.drawString(level, -fontRendererObj.getStringWidth(level) / 2, 0, 0xFFFFFF);
        GL11.glPopMatrix();
        fontRendererObj.drawString("LVL.", 77, 85, TEXT);
        // The bar: dark track with a blue edge, light blue fill for the share of XP.
        drawRect(77, 95, 77 + 175, 95 + 13, 0xFF4169F5);
        drawRect(78, 96, 77 + 174, 95 + 12, 0xFF111A2E);
        int fill = (int) (172 * (XP / (float) XP_NEXT));
        drawRect(79, 97, 79 + fill, 95 + 11, 0xFF62D7FF);
        String xp = XP + "/" + XP_NEXT + " XP";
        fontRendererObj.drawString(xp, 307 - fontRendererObj.getStringWidth(xp), 98, TEXT);
        GL11.glColor4f(1, 1, 1, 1);
    }

    private void drawPremiumPanel(int mx, int my) {
        drawTex(PANEL_PREMIUM, 327, 67, 326, 52);
        drawTex(PREMIUM_LOGO, 345, 75, 38, 38);
        drawTex(PREMIUM_TITLE, 391, 78, 104, 7);
        drawTex(PREMIUM_DESC, 391, 88, 132, 15);
        boolean hover = inside(mx, my, 551, 78, 90, 30);
        if (premiumOwned) {
            GL11.glColor4f(0.45F, 0.45F, 0.5F, 1);
        } else if (hover) {
            GL11.glColor4f(1.25F, 1.25F, 1.25F, 1);
        }
        drawTex(BUTTON, 551, 78, 90, 30);
        GL11.glColor4f(1, 1, 1, 1);
        drawCentered(premiumOwned ? "КУПЛЕНО" : "УЛУЧШИТЬ", 596, 89, 0xFFFFFF);
    }

    private void drawTimer() {
        drawTex(TIMER_BOX, 570, 8, 95, 26);
        drawTex(ICON_TIMER, 577, 16, 10, 11);
        drawTex(TIMER_LABEL, 593, 12, 66, 7);
        fontRendererObj.drawString("31 д. 14 ч. 51 м.", 593, 22, TEXT);
    }

    /** One reward cell; true when the mouse is over it. */
    private boolean drawCell(int i, int y, ItemStack stack, boolean open, boolean taken, int mx, int my) {
        int x = cellX(i), w = cellW(i), h = 74;
        boolean hover = inside(mx, my, x, y, w, h);
        boolean takeable = open && !taken;
        if (takeable) {
            // Pulses while it waits to be taken.
            float pulse = 0.85F + 0.25F * (float) Math.sin(Minecraft.getSystemTime() / 250.0);
            GL11.glColor4f(pulse, pulse, pulse * 1.1F, 1);
        } else if (!open) {
            GL11.glColor4f(0.55F, 0.55F, 0.6F, 1);
        }
        drawTex(i < 9 ? CELL : CELL_FINAL, x, y, w, h);
        GL11.glColor4f(1, 1, 1, 1);
        if (hover) {
            drawRect(x + 2, y + 2, x + w - 2, y + h - 2, 0x22FFFFFF);
        }

        int itemSize = i < 9 ? 28 : 34;
        drawItem(stack, x + (w - itemSize) / 2, y + (i < 9 ? 14 : 10), itemSize);
        if (!open || taken) {
            drawRect(x + 2, y + 2, x + w - 2, y + h - 2, taken ? 0x99000000 : 0x66000000);
        }
        if (i < 9) {
            drawCentered("x" + stack.stackSize, x + w / 2, y + 55, TEXT);
        } else {
            drawCentered(stack.getDisplayName(), x + w / 2, y + 52, TEXT);
        }
        if (taken) {
            drawCentered("§a✔", x + w / 2, y + 30, 0xFFFFFF);
        }
        return hover;
    }

    private void drawItem(ItemStack stack, int x, int y, int size) {
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0);
        GL11.glScalef(size / 16.0F, size / 16.0F, 1);
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), stack, 0, 0);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glPopMatrix();
        GL11.glColor4f(1, 1, 1, 1);
    }

    private void drawCentered(String text, int cx, int y, int color) {
        fontRendererObj.drawString(text, cx - fontRendererObj.getStringWidth(text) / 2, y, color);
    }

    /** A whole texture stretched over the rectangle, smoothly filtered (the art is drawn at twice the size). */
    private void drawTex(ResourceLocation texture, double x, double y, double w, double h) {
        mc.getTextureManager()
            .bindTexture(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.addVertexWithUV(x, y + h, zLevel, 0, 1);
        t.addVertexWithUV(x + w, y + h, zLevel, 1, 1);
        t.addVertexWithUV(x + w, y, zLevel, 1, 0);
        t.addVertexWithUV(x, y, zLevel, 0, 0);
        t.draw();
    }

    // --- input

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        if (button != 0) {
            return;
        }
        int mx = dx(mouseX), my = dy(mouseY);
        if (!premiumOwned && inside(mx, my, 551, 78, 90, 30)) {
            premiumOwned = true;
            click();
            return;
        }
        for (int i = 0; i < LEVEL; i++) {
            if (!freeTaken[i] && inside(mx, my, cellX(i), 158, cellW(i), 74)) {
                freeTaken[i] = true;
                click();
            }
            if (premiumOwned && !premiumTaken[i] && inside(mx, my, cellX(i), 249, cellW(i), 74)) {
                premiumTaken[i] = true;
                click();
            }
        }
    }

    private void click() {
        mc.getSoundHandler()
            .playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
    }
}
