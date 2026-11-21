package coint.client.spotify;

import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;

import org.lwjgl.opengl.GL11;

/** One reusable GL texture, released on opt-out or when the cover disappears. */
final class SpotifyCoverTexture {

    private static DynamicTexture texture;
    private static String current = "";

    private SpotifyCoverTexture() {}

    static boolean draw(String id, SpotifyCover cover, int x, int y, int size) {
        if (id.isEmpty() || !id.equals(cover.id) || cover.image == null) {
            clear();
            return false;
        }
        if (texture == null) texture = new DynamicTexture(SpotifyCover.SIZE, SpotifyCover.SIZE);
        if (!current.equals(id)) {
            cover.image
                .getRGB(0, 0, SpotifyCover.SIZE, SpotifyCover.SIZE, texture.getTextureData(), 0, SpotifyCover.SIZE);
            texture.updateDynamicTexture();
            current = id;
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getGlTextureId());
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.addVertexWithUV(x, y + size, 0, 0, 1);
        t.addVertexWithUV(x + size, y + size, 0, 1, 1);
        t.addVertexWithUV(x + size, y, 0, 1, 0);
        t.addVertexWithUV(x, y, 0, 0, 0);
        t.draw();
        return true;
    }

    static void clear() {
        if (texture != null) texture.deleteGlTexture();
        texture = null;
        current = "";
    }
}
