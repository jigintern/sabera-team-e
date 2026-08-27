import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * README用のスマホホーム画面。実装と同じ色・配置・素材から作り直す。
 *
 * java -Djava.awt.headless=true tools/compose-phone-preview.java
 */
public class compose_phone_preview {
    private static final int WIDTH = 390;
    private static final int HEIGHT = 844;
    private static final int SCALE = 2;

    private static final Color NIGHT = new Color(0x05, 0x0B, 0x16);
    private static final Color GREEN = new Color(0x75, 0xE6, 0xA3);
    private static final Color ON_ACCENT = new Color(0x05, 0x20, 0x10);

    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path logoPath = root.resolve(
            "samples/kmp/app/src/main/res/drawable-nodpi/hoshishirube_logo.png"
        );
        Path fontPath = root.resolve(
            "samples/kmp/app/src/main/res/font/m_plus_rounded_1c_medium.ttf"
        );
        Path output = root.resolve("docs/images/smartphone-home.png");
        Files.createDirectories(output.getParent());

        BufferedImage image = new BufferedImage(WIDTH * SCALE, HEIGHT * SCALE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.scale(SCALE, SCALE);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setColor(NIGHT);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        drawBackground(g);

        BufferedImage logo = ImageIO.read(logoPath.toFile());
        // **枠に押し込めず、縦横比を保って中へ収める**（実装の ContentScale.Fit と同じ）。
        // 大きさを決め打つと、素材の縦横比が変わったときに丸い印が楕円になる
        int boxWidth = 326;
        int boxHeight = 92;
        double fit = Math.min(boxWidth / (double) logo.getWidth(), boxHeight / (double) logo.getHeight());
        int drawWidth = (int) Math.round(logo.getWidth() * fit);
        int drawHeight = (int) Math.round(logo.getHeight() * fit);
        g.drawImage(logo, 32 + (boxWidth - drawWidth) / 2, 328 + (boxHeight - drawHeight) / 2,
            drawWidth, drawHeight, null);

        Font medium = Font.createFont(Font.TRUETYPE_FONT, fontPath.toFile());
        drawCentered(g, "星空を、もっと身近に。", medium.deriveFont(20f), Color.WHITE, 0.82f, 451);

        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(GREEN);
        g.fill(new RoundRectangle2D.Float(35, 739, 320, 52, 52, 52));
        drawCentered(g, "スタート", medium.deriveFont(18f), ON_ACCENT, 1f, 772);

        drawRight(g, "夏の星座・さそり座", medium.deriveFont(12f), Color.WHITE, 0.48f, 370, 821);

        g.dispose();
        ImageIO.write(image, "png", output.toFile());
        System.out.println(output);
    }

    private static void drawBackground(Graphics2D g) {
        float[][] faint = {
            {0.07f, 0.10f}, {0.21f, 0.07f}, {0.41f, 0.12f}, {0.73f, 0.08f},
            {0.91f, 0.17f}, {0.12f, 0.43f}, {0.35f, 0.51f}, {0.61f, 0.48f},
            {0.92f, 0.44f}, {0.05f, 0.69f}, {0.25f, 0.76f}, {0.46f, 0.84f},
            {0.68f, 0.89f}, {0.93f, 0.78f},
        };
        g.setComposite(AlphaComposite.SrcOver.derive(0.25f));
        g.setColor(Color.WHITE);
        for (int i = 0; i < faint.length; i++) {
            float radius = i % 4 == 0 ? 2.2f : 1.2f;
            circle(g, WIDTH * faint[i][0], HEIGHT * faint[i][1], radius);
        }

        // 8月の候補にある「さそり座」。座標は SeasonalConstellationBackground と同じ。
        float[][] stars = {
            {0.20f, 0.25f}, {0.29f, 0.34f}, {0.39f, 0.43f}, {0.50f, 0.55f},
            {0.56f, 0.69f}, {0.68f, 0.78f}, {0.80f, 0.72f}, {0.86f, 0.58f},
        };
        g.setComposite(AlphaComposite.SrcOver.derive(0.24f));
        g.setColor(new Color(0xB6, 0xFF, 0xD1));
        g.setStroke(new java.awt.BasicStroke(1.4f));
        for (int i = 0; i < stars.length - 1; i++) {
            g.draw(new Line2D.Float(
                WIDTH * stars[i][0], HEIGHT * stars[i][1],
                WIDTH * stars[i + 1][0], HEIGHT * stars[i + 1][1]
            ));
        }
        g.setComposite(AlphaComposite.SrcOver.derive(0.88f));
        g.setColor(new Color(0xDF, 0xFF, 0xEA));
        for (int i = 0; i < stars.length; i++) {
            circle(g, WIDTH * stars[i][0], HEIGHT * stars[i][1], i == 2 ? 4.2f : 2.5f);
        }
        g.setComposite(AlphaComposite.SrcOver);
    }

    private static void circle(Graphics2D g, float x, float y, float radius) {
        g.fillOval(Math.round(x - radius), Math.round(y - radius), Math.round(radius * 2), Math.round(radius * 2));
    }

    private static void drawCentered(
        Graphics2D g, String text, Font font, Color color, float alpha, int baseline
    ) {
        g.setFont(font);
        g.setColor(color);
        g.setComposite(AlphaComposite.SrcOver.derive(alpha));
        int x = (WIDTH - g.getFontMetrics().stringWidth(text)) / 2;
        g.drawString(text, x, baseline);
        g.setComposite(AlphaComposite.SrcOver);
    }

    private static void drawRight(
        Graphics2D g, String text, Font font, Color color, float alpha, int right, int baseline
    ) {
        g.setFont(font);
        g.setColor(color);
        g.setComposite(AlphaComposite.SrcOver.derive(alpha));
        g.drawString(text, right - g.getFontMetrics().stringWidth(text), baseline);
        g.setComposite(AlphaComposite.SrcOver);
    }
}
