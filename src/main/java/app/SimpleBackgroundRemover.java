import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.awt.Color;
import java.util.ArrayDeque;
import java.util.Queue;

public class SimpleBackgroundRemover {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java SimpleBackgroundRemover input.jpg out.png");
            return;
        }
        BufferedImage src = ImageIO.read(new File(args[0]));
        int w = src.getWidth(), h = src.getHeight();
        int[][] vis = new int[w][h];
        boolean[][] mask = new boolean[w][h];

        // Sample background color from corners average
        Color bg = sampleBackgroundColor(src);

        int tol = 60; // color tolerance, tweak
        // Flood fill from four corners to mark background
        floodFill(src, 0, 0, bg, tol, mask);
        floodFill(src, w - 1, 0, bg, tol, mask);
        floodFill(src, 0, h - 1, bg, tol, mask);
        floodFill(src, w - 1, h - 1, bg, tol, mask);

        // Create output with alpha
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                if (mask[x][y]) {
                    // background -> transparent
                    out.setRGB(x, y, (rgb & 0x00FFFFFF)); // alpha 0
                } else {
                    // foreground -> copy with full alpha
                    out.setRGB(x, y, (0xFF << 24) | (rgb & 0x00FFFFFF));
                }
            }
        }
        ImageIO.write(out, "PNG", new File(args[1]));
        System.out.println("Saved " + args[1]);
    }

    static Color sampleBackgroundColor(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = new int[] {
                img.getRGB(0, 0), img.getRGB(w - 1, 0), img.getRGB(0, h - 1), img.getRGB(w - 1, h - 1)
        };
        long r = 0, g = 0, b = 0;
        for (int p : px) {
            r += (p >> 16) & 0xFF;
            g += (p >> 8) & 0xFF;
            b += p & 0xFF;
        }
        return new Color((int) (r / 4), (int) (g / 4), (int) (b / 4));
    }

    static void floodFill(BufferedImage img, int sx, int sy, Color bg, int tol, boolean[][] mask) {
        int w = img.getWidth(), h = img.getHeight();
        boolean[][] seen = new boolean[w][h];
        Queue<int[]> q = new ArrayDeque<>();
        q.add(new int[] { sx, sy });
        while (!q.isEmpty()) {
            int[] p = q.remove();
            int x = p[0], y = p[1];
            if (x < 0 || y < 0 || x >= w || y >= h)
                continue;
            if (seen[x][y])
                continue;
            seen[x][y] = true;

            int rgb = img.getRGB(x, y);
            int rr = (rgb >> 16) & 0xFF, gg = (rgb >> 8) & 0xFF, bb = rgb & 0xFF;
            int dr = rr - bg.getRed(), dg = gg - bg.getGreen(), db = bb - bg.getBlue();
            int dist = Math.abs(dr) + Math.abs(dg) + Math.abs(db);
            if (dist <= tol) {
                mask[x][y] = true;
                q.add(new int[] { x + 1, y });
                q.add(new int[] { x - 1, y });
                q.add(new int[] { x, y + 1 });
                q.add(new int[] { x, y - 1 });
            }
        }
    }
}