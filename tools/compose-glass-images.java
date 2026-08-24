// グラスをかけている人に見えているものを、ドキュメント用の PNG にする。
//
// 入力は DocumentImagesTest が書いた samples/kmp/app/build/doc-images/panels.txt。
// **中身の計算はアプリと同じコードでやってある**ので、ここがやるのは絵にするところだけ。
//
// **パネルは視野全体ではない。** 正面より少し上に小さな画面が浮かんでいて、その外側は素通し。
// **黒い画素は光らない**（波導ディスプレイ）ので、背景は塗りつぶさず、空と街へ**光を足す**。
//
//   ./gradlew :app:testDebugUnitTest --tests '*DocumentImagesTest*'   # 中身を出す
//   java tools/compose-glass-images.java                              # 絵にする
//
// **実機の写真ではない。** 街と暗い星は雰囲気のための飾りで、視界の広さもパネルの大きさも
// （画角が未実測なので）仮の値。パネルのにじみ・明るさ・実機のフォントも出ない。

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;

public class ComposeGlassImages {

    /** グラスのキャンバス座標系 */
    static final int PANEL_WIDTH = 576;
    static final int PANEL_HEIGHT = 360;

    /** 書き出す倍率 */
    static final int SCALE = 2;

    /** 作り直しても同じ街になるように種を固定する */
    static final long SEED = 20260101L;

    record Label(int x, int y, int width, int height, String text) {}

    record Star(double x, double y, double magnitude) {}

    /** 夜空の色。天頂側は暗く、地平側は町の明かりでわずかに明るい */
    static final Color SKY_TOP = new Color(5, 8, 18);
    static final Color SKY_BOTTOM = new Color(16, 22, 40);

    /** 街明かり（光害）。地平線の上をぼんやり染める */
    static final Color CITY_GLOW = new Color(255, 150, 80);

    // 視界。panels.txt の scene / horizon / panel / star 行で決まる
    static int sceneWidth = 880;
    static int sceneHeight = 550;
    static double horizon = Double.NaN;
    static double[] panelQuad = null;
    static List<Star> stars = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        File root = repoRoot();
        File input = new File(root, "samples/kmp/app/build/doc-images/panels.txt");
        if (!input.exists()) {
            System.err.println("先に ./gradlew :app:testDebugUnitTest --tests '*DocumentImagesTest*' を回す（"
                    + input + " が無い）");
            System.exit(1);
        }
        File outDir = new File(root, "docs/images");
        outDir.mkdirs();

        BufferedImage scene = null;
        String name = null;
        int width = 0, height = 0;
        byte[] gray = null;
        List<Label> labels = new ArrayList<>();
        for (String line : Files.readAllLines(input.toPath(), StandardCharsets.UTF_8)) {
            if (line.startsWith("scene ")) {
                String[] parts = line.split(" ");
                sceneWidth = Integer.parseInt(parts[1]);
                sceneHeight = Integer.parseInt(parts[2]);
            } else if (line.startsWith("horizon ")) {
                horizon = Double.parseDouble(line.substring("horizon ".length()));
            } else if (line.startsWith("panel ")) {
                String[] parts = line.split(" ");
                panelQuad = new double[8];
                for (int i = 0; i < 8; i++) panelQuad[i] = Double.parseDouble(parts[i + 1]);
            } else if (line.startsWith("star ")) {
                String[] parts = line.split(" ");
                stars.add(new Star(Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                        Double.parseDouble(parts[3])));
            } else if (line.startsWith("screen ")) {
                if (name != null) write(outDir, scene, name, width, height, gray, labels);
                if (scene == null) scene = scene();   // 景色は 1 回描いて使い回す
                String[] parts = line.split(" ");
                name = parts[1];
                width = Integer.parseInt(parts[2]);
                height = Integer.parseInt(parts[3]);
                gray = null;
                labels = new ArrayList<>();
            } else if (line.startsWith("pixels ")) {
                gray = Base64.getDecoder().decode(line.substring("pixels ".length()));
            } else if (line.startsWith("label ")) {
                String[] parts = line.split(" ", 6);
                labels.add(new Label(
                        Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), parts[5]));
            }
        }
        if (name != null) write(outDir, scene, name, width, height, gray, labels);
    }

    /** 素通しで見えているもの（空・星・街）。パネルはまだ置かない */
    static BufferedImage scene() {
        BufferedImage image = new BufferedImage(sceneWidth * SCALE, sceneHeight * SCALE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, SKY_TOP, 0, image.getHeight(), SKY_BOTTOM));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        cityGlow(g, image);
        stars(g);
        skyline(g, image);
        groundFade(g, image);
        vignette(g, image);
        g.dispose();
        return image;
    }

    static void write(File outDir, BufferedImage scene, String name, int width, int height,
                      byte[] gray, List<Label> labels) throws Exception {
        BufferedImage image = new BufferedImage(scene.getWidth(), scene.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D base = image.createGraphics();
        base.drawImage(scene, 0, 0, null);
        base.dispose();

        // パネルの光を、視界の中の矩形へ縮めて足す
        Rectangle rect = panelRect(image);
        BufferedImage placed = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = placed.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(panel(width, height, gray, labels), rect.x, rect.y, rect.width, rect.height, null);
        g.dispose();
        add(image, placed);

        // 画面の縁をうっすら見せる（**説明のための線**。実機ではここに枠は出ない）
        Graphics2D edge = image.createGraphics();
        edge.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        edge.setColor(new Color(120, 255, 190, 26));
        edge.setStroke(new BasicStroke(SCALE));
        edge.drawRoundRect(rect.x, rect.y, rect.width, rect.height, 6 * SCALE, 6 * SCALE);
        edge.dispose();

        File out = new File(outDir, name + ".png");
        ImageIO.write(image, "png", out);
        System.out.println(out.getPath() + "（" + image.getWidth() + "×" + image.getHeight()
                + "・パネルは視界の横 " + (100 * rect.width / image.getWidth()) + "%）");
    }

    /** パネルが視界のどこを占めるか。四隅の外接矩形で置く（傾きはほとんど無い） */
    static Rectangle panelRect(BufferedImage image) {
        if (panelQuad == null) {
            int w = image.getWidth() * 2 / 5;
            return new Rectangle((image.getWidth() - w) / 2, image.getHeight() / 6, w, w * PANEL_HEIGHT / PANEL_WIDTH);
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i += 2) {
            minX = Math.min(minX, panelQuad[i]);
            maxX = Math.max(maxX, panelQuad[i]);
            minY = Math.min(minY, panelQuad[i + 1]);
            maxY = Math.max(maxY, panelQuad[i + 1]);
        }
        return new Rectangle((int) (minX * SCALE), (int) (minY * SCALE),
                (int) ((maxX - minX) * SCALE), (int) ((maxY - minY) * SCALE));
    }

    /**
     * 肉眼の星。**大きさは等級で決める。**
     *
     * 星図の点は読ませるために大きく描いてあるので、そのまま空の星に使うと実際よりずっと大きく見える。
     * 空では 1 等星でもごく小さな点で、明るさの差はにじみの大きさとして出る。
     */
    static void stars(Graphics2D g) {
        // 星表は 5 等までしか持っていないので、**それより暗い星は飾りで足す**（位置に意味は無い）
        Random random = new Random(SEED + 7);
        double floor = Double.isNaN(horizon) ? sceneHeight : Math.min(sceneHeight, horizon);
        for (int i = 0; i < 1400; i++) {
            double x = random.nextDouble() * sceneWidth * SCALE;
            double y = random.nextDouble() * floor * SCALE;
            double radius = (0.2 + random.nextDouble() * 0.3) * SCALE;
            g.setColor(new Color(0.85f, 0.89f, 1f, 0.08f + random.nextFloat() * 0.26f));
            g.fill(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
        }

        for (Star star : stars) {
            // 0 等で半径 1.35 画素、5 等で 0.35 画素まで。暗い星ほど淡くする
            double t = Math.max(0.0, Math.min(1.0, (5.2 - star.magnitude()) / 5.2));
            double radius = (0.35 + 1.0 * t * t) * SCALE;
            float alpha = (float) (0.45 + 0.55 * t * t);
            double x = star.x() * SCALE, y = star.y() * SCALE;

            double haloRadius = radius * 5.0;
            g.setColor(new Color(0.72f, 0.80f, 1f, alpha * 0.18f));
            g.fill(new Ellipse2D.Double(x - haloRadius, y - haloRadius, haloRadius * 2, haloRadius * 2));

            g.setColor(new Color(0.90f, 0.93f, 1f, alpha));
            g.fill(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
        }
    }

    /** 街明かりで地平線のあたりが白む。**暗い空を前提にできない場所**でもある */
    static void cityGlow(Graphics2D g, BufferedImage image) {
        if (Double.isNaN(horizon)) return;
        int ground = (int) (horizon * SCALE);
        int top = ground - 130 * SCALE;
        if (ground < 0) return;
        g.setPaint(new GradientPaint(
                0, top, new Color(CITY_GLOW.getRed(), CITY_GLOW.getGreen(), CITY_GLOW.getBlue(), 0),
                0, ground, new Color(CITY_GLOW.getRed(), CITY_GLOW.getGreen(), CITY_GLOW.getBlue(), 58)));
        g.fillRect(0, Math.max(0, top), image.getWidth(), Math.min(image.getHeight(), ground) - Math.max(0, top));
    }

    /** 街のシルエット。**地平線から下**に建てる（うしろの星は隠れる） */
    static void skyline(Graphics2D g, BufferedImage image) {
        if (Double.isNaN(horizon)) return;
        int ground = (int) (horizon * SCALE);
        if (ground > image.getHeight() + 40 * SCALE) return;

        Random random = new Random(SEED);
        for (int layer = 0; layer < 2; layer++) {
            int maxHeight = layer == 0 ? 12 : 22;
            int x = -20;
            while (x < sceneWidth + 20) {
                // ときどき飛び抜けて高いビルを混ぜる（並びが揃うと壁に見える）
                boolean tower = random.nextInt(9) == 0;
                int shade = random.nextInt(4);
                Color body = layer == 0
                        ? new Color(11 + shade, 15 + shade, 28 + shade)
                        : new Color(4 + shade, 6 + shade, 13 + shade);
                int w = 12 + random.nextInt(26);
                int h = 4 + random.nextInt(maxHeight) + (tower ? 8 + random.nextInt(14) : 0);
                int top = ground - h * SCALE;
                g.setColor(body);
                g.fillRect(x * SCALE, top, w * SCALE, image.getHeight() - top);

                // 屋上のアンテナと航空障害灯（近景だけ）
                if (layer == 1 && random.nextInt(4) == 0) {
                    int mast = 4 + random.nextInt(7);
                    g.setColor(body);
                    g.fillRect((x + w / 2) * SCALE, top - mast * SCALE, Math.max(1, SCALE / 2), mast * SCALE);
                    g.setColor(new Color(255, 70, 60, 200));
                    g.fill(new Ellipse2D.Double((x + w / 2.0) * SCALE - SCALE * 0.6, top - (mast + 0.6) * SCALE,
                            SCALE * 1.2, SCALE * 1.2));
                }

                // 窓。**ぜんぶ点けない**（消えている部屋があるほうが街に見える）
                for (int wy = top + 3 * SCALE; wy < image.getHeight() - SCALE; wy += 6 * SCALE) {
                    for (int wx = (x + 2) * SCALE; wx < (x + w - 2) * SCALE; wx += 5 * SCALE) {
                        if (random.nextInt(100) >= (layer == 0 ? 10 : 18)) continue;
                        float warm = 0.55f + random.nextFloat() * 0.45f;
                        g.setColor(new Color(1f, 0.78f * warm + 0.15f, 0.42f * warm, layer == 0 ? 0.3f : 0.65f));
                        g.fillRect(wx, wy, SCALE, 2 * SCALE);
                    }
                }
                x += w + 2 + random.nextInt(7);
            }
        }
    }

    /**
     * 足元へ向かうほど暗く落とす。
     *
     * 近い建物ほど視界の下を占めるが、**絵の主役は空**なので、下は闇に沈めて輪郭だけ残す。
     */
    static void groundFade(Graphics2D g, BufferedImage image) {
        if (Double.isNaN(horizon)) return;
        int ground = (int) (horizon * SCALE);
        if (ground >= image.getHeight()) return;
        g.setPaint(new GradientPaint(
                0, ground, new Color(0, 0, 0, 0),
                0, image.getHeight(), new Color(2, 3, 6, 235)));
        g.fillRect(0, ground, image.getWidth(), image.getHeight() - ground);
    }

    /** 四隅をわずかに落とす。**レンズ越しに見ている**感じを出すためだけのもの */
    static void vignette(Graphics2D g, BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        g.setPaint(new RadialGradientPaint(
                new Point2D.Float(w / 2f, h / 2f), Math.max(w, h) * 0.72f,
                new float[] {0.5f, 1f},
                new Color[] {new Color(0, 0, 0, 0), new Color(0, 0, 0, 110)}));
        g.fillRect(0, 0, w, h);
    }

    /** グラスが出している光だけを持つ層（黒は透明のまま） */
    static BufferedImage panel(int width, int height, byte[] gray, List<Label> labels) {
        BufferedImage panel = new BufferedImage(PANEL_WIDTH * SCALE, PANEL_HEIGHT * SCALE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D p = panel.createGraphics();
        p.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        if (gray != null) {
            int offsetX = (PANEL_WIDTH - width) / 2;
            int offsetY = (PANEL_HEIGHT - height) / 2;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    // 送るときと同じ 3bit へ落としてから色にする（8 階調より滑らかに見せない）
                    int step = (gray[y * width + x] & 0xFF) >>> 5;
                    if (step == 0) continue;
                    p.setColor(green(step * 255 / 7));
                    p.fillRect((offsetX + x) * SCALE, (offsetY + y) * SCALE, SCALE, SCALE);
                }
            }
        }
        p.setColor(green(255));
        p.setFont(japaneseFont());
        FontMetrics metrics = p.getFontMetrics();
        for (Label label : labels) {
            // 枠の縦中央に置く。実機のフォントは分からないので、字形ではなく置き場所だけを合わせる
            int baseline = (label.y() + label.height() / 2) * SCALE + (metrics.getAscent() - metrics.getDescent()) / 2;
            p.drawString(label.text(), (label.x() + 4) * SCALE, baseline);
        }
        p.dispose();
        return panel;
    }

    /** パネルの光を景色に足す（塗り替えるのではなく足すので、暗いところほど字が浮く） */
    static void add(BufferedImage scene, BufferedImage panel) {
        for (int y = 0; y < scene.getHeight(); y++) {
            for (int x = 0; x < scene.getWidth(); x++) {
                int src = panel.getRGB(x, y);
                int alpha = (src >>> 24);
                if (alpha == 0) continue;
                int base = scene.getRGB(x, y);
                int r = clamp(((base >> 16) & 0xFF) + ((src >> 16) & 0xFF) * alpha / 255);
                int g = clamp(((base >> 8) & 0xFF) + ((src >> 8) & 0xFF) * alpha / 255);
                int b = clamp((base & 0xFF) + (src & 0xFF) * alpha / 255);
                scene.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
    }

    /** 緑の単色パネル。青をわずかに混ぜると、写真で見たときの発色に近い */
    static Color green(int level) {
        return new Color(0, level, (int) (level * 0.45));
    }

    static int clamp(int value) {
        return Math.min(255, Math.max(0, value));
    }

    /** 日本語が出るフォントを選ぶ（無ければ論理フォント） */
    static Font japaneseFont() {
        List<String> installed = List.of(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String family : List.of("Hiragino Sans", "Noto Sans CJK JP", "Noto Sans JP", "Yu Gothic", "MS Gothic")) {
            if (installed.contains(family)) return new Font(family, Font.PLAIN, 24 * SCALE);
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 24 * SCALE);
    }

    /** data/ が見つかるまで遡る（どこから呼ばれても動くように） */
    static File repoRoot() {
        File dir = new File("").getAbsoluteFile();
        while (dir != null && !new File(dir, "data/stars.json").exists()) dir = dir.getParentFile();
        if (dir == null) throw new IllegalStateException("リポジトリの外から呼ばれた");
        return dir;
    }
}
