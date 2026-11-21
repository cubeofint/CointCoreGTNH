package coint.client.spotify;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Bounded image decoding on the bridge worker, never on Minecraft's render thread. */
final class SpotifyCover {

    static final SpotifyCover EMPTY = new SpotifyCover("", null);
    static final int SIZE = 128;
    final String id;
    final BufferedImage image;

    private SpotifyCover(String id, BufferedImage image) {
        this.id = id;
        this.image = image;
    }

    static SpotifyCover decode(String id, String encoded) {
        if (!id.matches("[0-9a-f]{64}") || encoded.length() > 700000) return EMPTY;
        try {
            byte[] data = Base64.getDecoder()
                .decode(encoded);
            if (data.length == 0 || data.length > 524288) return EMPTY;
            StringBuilder digest = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256")
                .digest(data)) digest.append(String.format("%02x", b & 255));
            if (!id.equals(digest.toString())) return EMPTY;
            try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) return EMPTY;
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    int w = reader.getWidth(0), h = reader.getHeight(0);
                    if (w < 1 || h < 1 || w > 2048 || h > 2048) return EMPTY;
                    BufferedImage source = reader.read(0);
                    BufferedImage result = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = result.createGraphics();
                    try {
                        graphics.setRenderingHint(
                            RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        double scale = Math.min(SIZE / (double) w, SIZE / (double) h);
                        int width = Math.max(1, (int) Math.round(w * scale)),
                            height = Math.max(1, (int) Math.round(h * scale));
                        graphics.drawImage(source, (SIZE - width) / 2, (SIZE - height) / 2, width, height, null);
                    } finally {
                        graphics.dispose();
                    }
                    return new SpotifyCover(id, result);
                } finally {
                    reader.dispose();
                }
            }
        } catch (Exception ignored) {
            return EMPTY;
        }
    }
}
