package coint.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;

import org.lwjgl.opengl.GL11;

import coint.cards.Cards;
import coint.cards.TileCardTable;

/** The round on a card table's top: the top card face up, the draw pile, and each seat's cards face down. */
public class RenderCardTable extends TileEntitySpecialRenderer {

    private static final int BACK = 0xFF4A2E1A, BACK_INNER = 0xFF6B4226, EDGE = 0xFFF4F0E6;

    @Override
    public void renderTileEntityAt(TileEntity te, double x, double y, double z, float partial) {
        if (!(te instanceof TileCardTable table) || table.top < 0) {
            return;
        }
        int light = te.getWorldObj()
            .getLightBrightnessForSkyBlocks(te.xCoord, te.yCoord + 1, te.zCoord, 0);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, light % 65536, light / 65536F);

        GL11.glPushMatrix();
        GL11.glTranslated(x + 0.5, y + 1.002, z + 0.5);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);

        // The draw pile, a little stack, and the top card beside it.
        for (int i = 0; i < 3; i++) {
            card(-0.12, 0, 0.16, 0.24, 8 + i * 4, i * 0.004, BACK, BACK_INNER);
        }
        int suit = Cards.suit(table.top);
        int color = suit < Cards.SUITS ? GuiCardTable.SUIT_COLORS[suit] : 0xFF2A2A2A;
        card(0.1, 0, 0.16, 0.24, -6, 0, EDGE, color);

        // Each seat's hand, face down along the edge.
        int seats = table.counts.length;
        for (int s = 0; s < seats; s++) {
            double angle = s * 2 * Math.PI / Math.max(1, seats);
            int shown = Math.min(table.counts[s], 9);
            GL11.glPushMatrix();
            GL11.glRotatef((float) Math.toDegrees(angle), 0, 1, 0);
            for (int c = 0; c < shown; c++) {
                double along = (c - (shown - 1) / 2.0) * 0.035;
                card(along, 0.38, 0.09, 0.13, (c - (shown - 1) / 2F) * 6, c * 0.0015, BACK, BACK_INNER);
            }
            GL11.glPopMatrix();
        }

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        label(table.top);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glPopMatrix();
    }

    /** A card lying flat, centered on (cx, cz), turned by deg; lifted by dy so stacks do not flicker. */
    private static void card(double cx, double cz, double w, double h, float deg, double dy, int edge, int inner) {
        GL11.glPushMatrix();
        GL11.glTranslated(cx, dy, cz);
        GL11.glRotatef(deg, 0, 1, 0);
        rect(-w / 2, -h / 2, w / 2, h / 2, 0, edge);
        double b = w * 0.1;
        rect(-w / 2 + b, -h / 2 + b, w / 2 - b, h / 2 - b, 0.0006, inner);
        GL11.glPopMatrix();
    }

    private static void rect(double x0, double z0, double x1, double z1, double y, int argb) {
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.setColorOpaque_I(argb & 0xFFFFFF);
        t.addVertex(x0, y, z0);
        t.addVertex(x0, y, z1);
        t.addVertex(x1, y, z1);
        t.addVertex(x1, y, z0);
        t.draw();
    }

    /** The top card's value written on it. */
    private static void label(int card) {
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        String text = switch (Cards.value(card)) {
            case Cards.SKIP -> "Ø";
            case Cards.REVERSE -> "«»";
            case Cards.DRAW_TWO -> "+2";
            case Cards.WILD -> "*";
            case Cards.WILD_DRAW_FOUR -> "+4";
            default -> String.valueOf(Cards.value(card));
        };
        GL11.glPushMatrix();
        GL11.glTranslated(0.1, 0.0015, 0);
        GL11.glRotatef(-6, 0, 1, 0);
        GL11.glRotatef(90, 1, 0, 0);
        float scale = 0.012F;
        GL11.glScalef(scale, scale, scale);
        GL11.glDepthMask(false);
        font.drawString(text, -font.getStringWidth(text) / 2, -4, 0xFFFFFF);
        GL11.glDepthMask(true);
        GL11.glPopMatrix();
    }
}
