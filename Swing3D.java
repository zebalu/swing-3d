/*
 * Copyright (c) 2026 Balázs Zaicsek
 * Licensed under the MIT License.
 */

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Swing3D extends JPanel
        implements KeyListener, MouseMotionListener, MouseListener {

    // ---- Camera ----
    private double camX = 0, camY = 0.3, camZ = 0;
    private double yaw = 0;
    private double pitch = 0;

    // ---- Input ----
    private final Set<Integer> keysPressed = new HashSet<Integer>();
    private Robot robot;
    private boolean ignoreNextMove = false;
    private boolean mouseCaptured = true;
    private boolean firstMove = true;
    private Cursor blankCursor;

    // ---- FPS counter ----
    private long lastFpsSampleNanos = System.nanoTime();
    private int framesSinceSample = 0;
    private double fps = 0.0;

    // ---- Scene ----
    private final double[][] cubeCenters = {
            {-0.6, 0.0, 4.0},   // front-left
            { 0.6, 0.0, 4.0},   // front-right
            { 0.0, 1.1, 4.0},   // on top of the two front cubes
            {-0.6, 0.0, 5.6},   // back-left
            { 0.6, 0.0, 5.6}    // back-right
    };
    private final double cubeSize = 1.0;
    private final double focalLength = 650;
    private final double nearPlane = 0.1;

    private static final Color[] CUBE_COLORS = {
            new Color(220, 90, 90),
            new Color(90, 200, 110),
            new Color(110, 150, 240),
            new Color(230, 200, 90),
            new Color(200, 110, 220)
    };
    private static final float[] FACE_BRIGHTNESS = {
            0.70f, 0.90f, 0.55f, 0.85f, 1.00f, 0.45f
    };

    public Swing3D() {
        setBackground(new Color(28, 30, 48));
        setFocusable(true);
        addKeyListener(this);
        addMouseMotionListener(this);
        addMouseListener(this);

        try {
            robot = new Robot();
        } catch (AWTException e) {
            robot = null;
        }

        blankCursor = Toolkit.getDefaultToolkit().createCustomCursor(
                new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB),
                new Point(0, 0), "blank");
        setCursor(blankCursor);

        Timer timer = new Timer(16, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                updateInput();
                repaint();
            }
        });
        timer.start();
    }

    private void updateInput() {
        double speed = 0.08;
        double fx = Math.sin(yaw);
        double fz = Math.cos(yaw);
        double rx = Math.cos(yaw);
        double rz = -Math.sin(yaw);

        if (keysPressed.contains(KeyEvent.VK_W)) { camX += fx * speed; camZ += fz * speed; }
        if (keysPressed.contains(KeyEvent.VK_S)) { camX -= fx * speed; camZ -= fz * speed; }
        if (keysPressed.contains(KeyEvent.VK_D)) { camX += rx * speed; camZ += rz * speed; }
        if (keysPressed.contains(KeyEvent.VK_A)) { camX -= rx * speed; camZ -= rz * speed; }
        if (keysPressed.contains(KeyEvent.VK_Q)) { camY += speed; }
        if (keysPressed.contains(KeyEvent.VK_E)) { camY -= speed; }
    }

    // World-space -> camera-space transform.
    private double[] worldToCamera(double wx, double wy, double wz) {
        double dx = wx - camX, dy = wy - camY, dz = wz - camZ;
        double cy = Math.cos(yaw),   sy = Math.sin(yaw);
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        // right  = ( cos y,        0,       -sin y       )
        // up     = (-sin p*sin y,  cos p,   -sin p*cos y )
        // fwd    = ( sin y*cos p,  sin p,    cos y*cos p )
        double rx = cy * dx - sy * dz;
        double ry = -sp * sy * dx + cp * dy - sp * cy * dz;
        double rz = sy * cp * dx + sp * dy + cy * cp * dz;
        return new double[]{rx, ry, rz};
    }

    private double[] project(double[] c) {
        int w = getWidth(), h = getHeight();
        double sx = w / 2.0 + c[0] * focalLength / c[2];
        double sy = h / 2.0 - c[1] * focalLength / c[2];
        return new double[]{sx, sy};
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawGrid(g2);

        List<Face> faces = new ArrayList<Face>();
        for (int i = 0; i < cubeCenters.length; i++) {
            double[] c = cubeCenters[i];
            addCubeFaces(faces, c[0], c[1], c[2], cubeSize, i);
        }
        // Painter's algorithm: farther faces first.
        faces.sort(new java.util.Comparator<Face>() {
            public int compare(Face a, Face b) { return Double.compare(b.avgZ, a.avgZ); }
        });

        for (Face f : faces) {
            int[] xs = new int[4], ys = new int[4];
            for (int i = 0; i < 4; i++) {
                xs[i] = (int) Math.round(f.pts[i][0]);
                ys[i] = (int) Math.round(f.pts[i][1]);
            }
            g2.setColor(f.color);
            g2.fillPolygon(xs, ys, 4);
            g2.setColor(new Color(0, 0, 0, 160));
            g2.drawPolygon(xs, ys, 4);
        }

        // ---- FPS sampling ----
        framesSinceSample++;
        long now = System.nanoTime();
        long elapsed = now - lastFpsSampleNanos;
        if (elapsed >= 500_000_000L) { // refresh twice per second
            fps = framesSinceSample * 1_000_000_000.0 / elapsed;
            framesSinceSample = 0;
            lastFpsSampleNanos = now;
        }

        // ---- HUD ----
        g2.setFont(new Font("Monospaced", Font.PLAIN, 12));
        g2.setColor(Color.WHITE);
        g2.drawString("WASD: move   Q/E: up/down   mouse: look   ESC: release mouse   click: capture", 10, 18);
        g2.drawString(String.format("pos=(%.2f, %.2f, %.2f)   yaw=%.2f   pitch=%.2f",
                camX, camY, camZ, yaw, pitch), 10, 34);
        if (!mouseCaptured) {
            g2.setColor(new Color(255, 220, 120));
            g2.drawString("Mouse released - click the panel to capture again.", 10, 52);
            g2.drawString("Or press esc again to exit.", 10, 72);
        }

        // FPS counter (top-right)
        String fpsText = String.format("FPS: %5.1f", fps);
        g2.setFont(new Font("Monospaced", Font.BOLD, 14));
        FontMetrics fm = g2.getFontMetrics();
        int textW = fm.stringWidth(fpsText);
        int textH = fm.getAscent();
        int padding = 6;
        int boxW = textW + padding * 2;
        int boxH = textH + padding * 2;
        int boxX = getWidth() - boxW - 10;
        int boxY = 10;
        g2.setColor(new Color(0, 0, 0, 140));
        g2.fillRect(boxX, boxY, boxW, boxH);
        g2.setColor(fps >= 50 ? new Color(140, 240, 140)
                  : fps >= 30 ? new Color(240, 220, 120)
                              : new Color(240, 130, 130));
        g2.drawString(fpsText, boxX + padding, boxY + padding + textH - 2);
    }

    private void drawGrid(Graphics2D g2) {
        g2.setColor(new Color(70, 75, 100));
        double floorY = -0.5;
        for (int i = -10; i <= 10; i++) {
            drawLine3D(g2, i, floorY, -10, i, floorY, 10);
            drawLine3D(g2, -10, floorY, i, 10, floorY, i);
        }
    }

    private void drawLine3D(Graphics2D g2,
                            double x1, double y1, double z1,
                            double x2, double y2, double z2) {
        double[] a = worldToCamera(x1, y1, z1);
        double[] b = worldToCamera(x2, y2, z2);
        if (a[2] <= nearPlane && b[2] <= nearPlane) return;
        if (a[2] <= nearPlane) {
            double t = (nearPlane - a[2]) / (b[2] - a[2]);
            a[0] += t * (b[0] - a[0]);
            a[1] += t * (b[1] - a[1]);
            a[2] = nearPlane;
        }
        if (b[2] <= nearPlane) {
            double t = (nearPlane - b[2]) / (a[2] - b[2]);
            b[0] += t * (a[0] - b[0]);
            b[1] += t * (a[1] - b[1]);
            b[2] = nearPlane;
        }
        double[] pa = project(a);
        double[] pb = project(b);
        g2.drawLine((int) pa[0], (int) pa[1], (int) pb[0], (int) pb[1]);
    }

    private static class Face {
        double[][] pts;
        double avgZ;
        Color color;
    }

    // Vertex indices for each cube face (with outward-pointing normal).
    private static final int[][] FACE_IDX = {
            {0, 1, 2, 3}, // -Z
            {5, 4, 7, 6}, // +Z
            {4, 0, 3, 7}, // -X
            {1, 5, 6, 2}, // +X
            {3, 2, 6, 7}, // +Y
            {4, 5, 1, 0}  // -Y
    };

    private void addCubeFaces(List<Face> out,
                              double cx, double cy, double cz,
                              double size, int cubeIdx) {
        double h = size / 2;
        double[][] corners = {
                {cx - h, cy - h, cz - h}, {cx + h, cy - h, cz - h},
                {cx + h, cy + h, cz - h}, {cx - h, cy + h, cz - h},
                {cx - h, cy - h, cz + h}, {cx + h, cy - h, cz + h},
                {cx + h, cy + h, cz + h}, {cx - h, cy + h, cz + h}
        };
        double[][] cam = new double[8][];
        for (int i = 0; i < 8; i++) {
            cam[i] = worldToCamera(corners[i][0], corners[i][1], corners[i][2]);
        }

        Color base = CUBE_COLORS[cubeIdx % CUBE_COLORS.length];
        for (int f = 0; f < 6; f++) {
            int[] idx = FACE_IDX[f];
            boolean visible = true;
            double avgZ = 0;
            for (int v : idx) {
                if (cam[v][2] <= nearPlane) { visible = false; break; }
                avgZ += cam[v][2];
            }
            if (!visible) continue;
            avgZ /= 4.0;

            double[][] sp = new double[4][];
            for (int i = 0; i < 4; i++) sp[i] = project(cam[idx[i]]);

            Face face = new Face();
            face.pts = sp;
            face.avgZ = avgZ;
            face.color = shade(base, FACE_BRIGHTNESS[f]);
            out.add(face);
        }
    }

    private Color shade(Color c, float k) {
        int r = Math.min(255, (int) (c.getRed()   * k));
        int g = Math.min(255, (int) (c.getGreen() * k));
        int b = Math.min(255, (int) (c.getBlue()  * k));
        return new Color(r, g, b);
    }

    // ---- Input handling ----
    @Override public void keyTyped(KeyEvent e) {}
    @Override public void keyPressed(KeyEvent e) {
        keysPressed.add(e.getKeyCode());
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            if (mouseCaptured) {
                // First ESC: release the mouse.
                mouseCaptured = false;
                setCursor(Cursor.getDefaultCursor());
            } else {
                // ESC again while already released: quit.
                System.exit(0);
            }
        }
    }
    @Override public void keyReleased(KeyEvent e) { keysPressed.remove(e.getKeyCode()); }

    @Override public void mouseDragged(MouseEvent e) { mouseMoved(e); }

    @Override public void mouseMoved(MouseEvent e) {
        if (ignoreNextMove) { ignoreNextMove = false; return; }
        if (!mouseCaptured) return;

        int cx = getWidth() / 2, cy = getHeight() / 2;

        if (firstMove) {
            firstMove = false;
            recenter(cx, cy);
            return;
        }

        int dx = e.getX() - cx;
        int dy = e.getY() - cy;
        if (dx == 0 && dy == 0) return;

        double sens = 0.003;
        yaw += dx * sens;
        pitch -= dy * sens;
        double limit = Math.PI / 2 - 0.01;
        if (pitch > limit) pitch = limit;
        if (pitch < -limit) pitch = -limit;

        // Keep yaw within [-PI, PI] for clean numbers.
        if (yaw > Math.PI)  yaw -= 2 * Math.PI;
        if (yaw < -Math.PI) yaw += 2 * Math.PI;

        recenter(cx, cy);
    }

    private void recenter(int cx, int cy) {
        if (robot == null) return;
        try {
            Point loc = getLocationOnScreen();
            ignoreNextMove = true;
            robot.mouseMove(loc.x + cx, loc.y + cy);
        } catch (IllegalComponentStateException ex) {
            // panel not visible yet
        }
    }

    @Override public void mouseClicked(MouseEvent e) {}
    @Override public void mousePressed(MouseEvent e) {
        mouseCaptured = true;
        firstMove = true;
        setCursor(blankCursor);
        requestFocusInWindow();
    }
    @Override public void mouseReleased(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}

    public static void main(String[] args) {
        boolean fullscreen = false;
        for (String arg : args) {
            if (arg.equals("-x") || arg.equals("-X")) {
                fullscreen = true;
            }
        }
        final boolean useFullscreen = fullscreen;

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                JFrame frame = new JFrame("Swing 3D - 5 cubes");
                frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                Swing3D panel = new Swing3D();
                frame.add(panel);

                if (useFullscreen) {
                    // Borderless, decoration-free full-screen window.
                    frame.setUndecorated(true);
                    GraphicsDevice device = GraphicsEnvironment
                            .getLocalGraphicsEnvironment()
                            .getDefaultScreenDevice();
                    if (device.isFullScreenSupported()) {
                        device.setFullScreenWindow(frame);
                    } else {
                        // Fallback: maximize an undecorated window.
                        frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
                        frame.setVisible(true);
                    }
                } else {
                    frame.setSize(960, 720);
                    frame.setLocationRelativeTo(null);
                    frame.setVisible(true);
                }
                panel.requestFocusInWindow();
            }
        });
    }
}
