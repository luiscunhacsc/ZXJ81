package zxj81;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ZX81Screen extends JPanel {
    private static final int COLS = 32;
    private static final int ROWS = 24;
    private static final int CHAR_PIXELS = 8;
    private static final int WIDTH = COLS * CHAR_PIXELS;
    private static final int HEIGHT = ROWS * CHAR_PIXELS;

    private final ZX81Bus bus;
    private final boolean[][] charset = new boolean[64][64];
    private final BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
    private Image keyboardPhoto;
    private boolean keyboardOverlay;
    private boolean helpOverlay;

    ZX81Screen(ZX81Bus bus, byte[] rom, Path baseDir) {
        this.bus = bus;
        setBackground(Color.BLACK);
        setFocusable(true);
        setPreferredSize(new Dimension(WIDTH * 4, HEIGHT * 4));
        buildCharset(rom);
        loadKeyboardPhoto(baseDir);
    }

    void setKeyboardOverlay(boolean keyboardOverlay) {
        this.keyboardOverlay = keyboardOverlay;
        repaint();
    }

    boolean isKeyboardOverlay() {
        return keyboardOverlay;
    }

    void setHelpOverlay(boolean helpOverlay) {
        this.helpOverlay = helpOverlay;
        repaint();
    }

    void refreshFrame() {
        int[] pixels = ((java.awt.image.DataBufferInt) frame.getRaster().getDataBuffer()).getData();
        java.util.Arrays.fill(pixels, 0x000000);

        int dfile = bus.readRaw16(ZX81Bus.SYSVAR_D_FILE);
        if (dfile < ZX81Bus.RAM_START || dfile >= ZX81Bus.MEMORY_SIZE) {
            repaint();
            return;
        }
        dfile++; // skip the initial HALT byte of the display file
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int code = bus.readRaw8(dfile);
                if (code == 0x76) {
                    // HALT = end of this line; remaining columns stay black (paper)
                    break;
                }
                drawChar(col * CHAR_PIXELS, row * CHAR_PIXELS, code);
                dfile++;
            }
            // advance past the HALT byte that terminates this line
            // (we either broke out of the loop on it, or it's the next byte)
            while (dfile < ZX81Bus.MEMORY_SIZE && bus.readRaw8(dfile) != 0x76) {
                dfile++;
            }
            dfile++; // skip the HALT itself
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        int scale = Math.max(1, Math.min(getWidth() / WIDTH, getHeight() / HEIGHT));
        int drawWidth = WIDTH * scale;
        int drawHeight = HEIGHT * scale;
        int x = (getWidth() - drawWidth) / 2;
        int y = (getHeight() - drawHeight) / 2;

        g2.translate(x, y);
        g2.scale(scale, scale);
        g2.drawImage(frame, 0, 0, null);
        g2.scale(1.0 / scale, 1.0 / scale);
        g2.translate(-x, -y);
        if (keyboardOverlay) {
            drawKeyboardOverlay(g2, x, y, drawWidth, drawHeight);
        }
        if (helpOverlay) {
            drawHelpOverlay(g2, x, y, drawWidth, drawHeight);
        }
        g2.dispose();
    }

    private void drawChar(int x, int y, int code) {
        boolean inverse = (code & 0x80) != 0;
        int glyph = code & 0x3f;
        for (int py = 0; py < CHAR_PIXELS; py++) {
            for (int px = 0; px < CHAR_PIXELS; px++) {
                boolean ink = charset[glyph][py * CHAR_PIXELS + px] ^ inverse;
                frame.setRGB(x + px, y + py, ink ? 0xffffff : 0x000000);
            }
        }
    }

    private void buildCharset(byte[] rom) {
        int address = 0x1e00;
        for (int ch = 0; ch < 64; ch++) {
            for (int row = 0; row < CHAR_PIXELS; row++) {
                int bits = rom[address++] & 0xff;
                for (int col = 0; col < CHAR_PIXELS; col++) {
                    charset[ch][row * CHAR_PIXELS + col] = (bits & 0x80) == 0;
                    bits <<= 1;
                }
            }
        }
    }

    private void loadKeyboardPhoto(Path baseDir) {
        Path imagePath = baseDir.resolve("assets").resolve("ZX81Keys.bmp");
        if (!Files.exists(imagePath)) {
            imagePath = baseDir.resolve("assets").resolve("ZX81Keys.jpg");
        }
        if (Files.exists(imagePath)) {
            try {
                keyboardPhoto = ImageIO.read(imagePath.toFile());
            } catch (IOException ignored) {
                keyboardPhoto = null;
            }
        }
    }

    private void drawKeyboardOverlay(Graphics2D g2, int x, int y, int width, int height) {
        g2.setColor(new Color(0, 0, 0, 210));
        g2.fillRect(0, 0, getWidth(), getHeight());

        if (keyboardPhoto != null) {
            int photoWidth = width;
            int photoHeight = Math.min(height, keyboardPhoto.getHeight(null) * photoWidth / Math.max(1, keyboardPhoto.getWidth(null)));
            int px = x;
            int py = y + (height - photoHeight) / 2;
            g2.drawImage(keyboardPhoto, px, py, photoWidth, photoHeight, null);
            return;
        }

        g2.setColor(new Color(239, 233, 220));
        g2.fillRoundRect(x + 20, y + 24, width - 40, height - 48, 20, 20);
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(x + 20, y + 24, width - 40, height - 48, 20, 20);
        g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, Math.max(16, width / 34)));
        FontMetrics fm = g2.getFontMetrics();
        String[] rows = {
            "1 2 3 4 5 6 7 8 9 0",
            " Q W E R T Y U I O P",
            "  A S D F G H J K L ENTER",
            "SHIFT Z X C V B N M . SPACE"
        };
        int lineY = y + height / 2 - fm.getHeight() * 2;
        for (String row : rows) {
            int tx = x + (width - fm.stringWidth(row)) / 2;
            g2.drawString(row, tx, lineY);
            lineY += fm.getHeight() + 12;
        }
    }

    private void drawHelpOverlay(Graphics2D g2, int x, int y, int width, int height) {
        g2.setColor(new Color(0, 0, 0, 210));
        g2.fillRect(0, 0, getWidth(), getHeight());

        g2.setColor(new Color(239, 233, 220));
        g2.fillRoundRect(x + 20, y + 24, width - 40, height - 48, 20, 20);
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(x + 20, y + 24, width - 40, height - 48, 20, 20);

        Font titleFont = new Font(Font.MONOSPACED, Font.BOLD, Math.max(18, width / 28));
        Font bodyFont = new Font(Font.MONOSPACED, Font.PLAIN, Math.max(14, width / 38));
        g2.setFont(titleFont);
        FontMetrics tfm = g2.getFontMetrics();

        String title = "ZXJ81";
        int tx = x + (width - tfm.stringWidth(title)) / 2;
        int lineY = y + 24 + tfm.getHeight() + 12;
        g2.drawString(title, tx, lineY);
        lineY += tfm.getHeight() + 8;

        g2.setFont(bodyFont);
        FontMetrics bfm = g2.getFontMetrics();
        String[] lines = {
            "F1   Ajuda (manter premido)",
            "F4   Teclado (manter premido)",
            "F6   Launcher de tapes .P",
            "F10  Hard reset",
            "F11  Sair",
            "",
            "Teclado PC segue a matriz ZX81.",
            "Setas mapeiam para 5/6/7/8."
        };
        for (String line : lines) {
            int lx = x + 40;
            g2.drawString(line, lx, lineY);
            lineY += bfm.getHeight() + 4;
        }
    }
}
