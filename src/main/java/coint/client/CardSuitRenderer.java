package coint.client;

import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

/** Card suits drawn as filled shapes, independently of small Unicode glyphs in a resource-pack font. */
final class CardSuitRenderer {

    private CardSuitRenderer() {}

    static void draw(int suit, float x, float y, float w, float h, int color) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glColor4f((color >> 16 & 255) / 255F, (color >> 8 & 255) / 255F, (color & 255) / 255F, 1);
        Tessellator tess = Tessellator.instance;
        tess.startDrawing(GL11.GL_TRIANGLES);
        if (suit == 0) {
            circle(tess, x, y, w, h, 0.28F, 0.29F, 0.24F);
            circle(tess, x, y, w, h, 0.72F, 0.29F, 0.24F);
            triangle(tess, x, y, w, h, 0.06F, 0.35F, 0.94F, 0.35F, 0.5F, 0.97F);
        } else if (suit == 1) {
            triangle(tess, x, y, w, h, 0.5F, 0, 1, 0.5F, 0, 0.5F);
            triangle(tess, x, y, w, h, 0, 0.5F, 1, 0.5F, 0.5F, 1);
        } else {
            if (suit == 2) {
                circle(tess, x, y, w, h, 0.5F, 0.25F, 0.24F);
                circle(tess, x, y, w, h, 0.25F, 0.56F, 0.24F);
                circle(tess, x, y, w, h, 0.75F, 0.56F, 0.24F);
            } else {
                triangle(tess, x, y, w, h, 0.5F, 0.02F, 0.05F, 0.58F, 0.95F, 0.58F);
                circle(tess, x, y, w, h, 0.28F, 0.59F, 0.23F);
                circle(tess, x, y, w, h, 0.72F, 0.59F, 0.23F);
            }
            triangle(tess, x, y, w, h, 0.43F, 0.55F, 0.57F, 0.55F, 0.35F, 0.98F);
            triangle(tess, x, y, w, h, 0.57F, 0.55F, 0.65F, 0.98F, 0.35F, 0.98F);
        }
        tess.draw();
        GL11.glPopAttrib();
    }

    private static void circle(Tessellator tess, float x, float y, float w, float h, float cx, float cy, float radius) {
        for (int i = 0; i < 20; i++) {
            double a = i * Math.PI / 10, b = (i + 1) * Math.PI / 10;
            triangle(
                tess,
                x,
                y,
                w,
                h,
                cx,
                cy,
                cx + (float) Math.cos(a) * radius,
                cy + (float) Math.sin(a) * radius,
                cx + (float) Math.cos(b) * radius,
                cy + (float) Math.sin(b) * radius);
        }
    }

    private static void triangle(Tessellator tess, float x, float y, float w, float h, float ax, float ay, float bx,
        float by, float cx, float cy) {
        tess.addVertex(x + ax * w, y + ay * h, 0);
        tess.addVertex(x + bx * w, y + by * h, 0);
        tess.addVertex(x + cx * w, y + cy * h, 0);
    }
}
