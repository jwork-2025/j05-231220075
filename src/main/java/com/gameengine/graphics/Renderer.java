package com.gameengine.graphics;

import com.gameengine.input.InputManager;
import com.gameengine.math.Vector2;
import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Swing-based software renderer implementing IRenderer.
 * Uses a simple double-buffered drawable list to avoid cross-thread modification between
 * the game thread and the Swing EDT.
 */
public class Renderer extends JFrame implements IRenderer {
    private int width;
    private int height;
    private String title;
    private GamePanel gamePanel;
    private InputManager inputManager;

    // 双缓冲绘制队列
    private final java.util.List<Drawable> frontBuffer = new ArrayList<>();
    private final java.util.List<Drawable> backBuffer = new ArrayList<>();

    public Renderer(int width, int height, String title) {
        this.width = width;
        this.height = height;
        this.title = title;
        this.inputManager = InputManager.getInstance();

        // Ensure Swing UI creation happens on the EDT to avoid platform-specific issues
        // Historically we attempted to defer initialization to the EDT which could
        // be starved if the EDT is busy running the game loop Timer. To ensure the
        // window is created and shown before the main loop starts, we perform
        // initialize() synchronously here. This avoids missing the initial show.
        System.out.println("[Renderer] initialize() synchronously (may be off-EDT)");
        try {
            initialize();
            System.out.println("[Renderer] initialize() completed (sync)");
        } catch (Throwable t) {
            System.out.println("[Renderer] initialize() threw: " + t);
            t.printStackTrace();
        }
    }

    private void initialize() {
        setTitle(title);
        setSize(width, height);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        gamePanel = new GamePanel();
        add(gamePanel);

        setupInput();

        setVisible(true);
    }

    private void setupInput() {
        // 键盘输入
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                inputManager.onKeyPressed(e.getKeyCode());
            }

            @Override
            public void keyReleased(KeyEvent e) {
                inputManager.onKeyReleased(e.getKeyCode());
            }
        });

        // 鼠标输入
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                inputManager.onMousePressed(e.getButton());
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                inputManager.onMouseReleased(e.getButton());
            }
        });

        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                inputManager.onMouseMoved(e.getX(), e.getY());
            }
        });

        setFocusable(true);
        requestFocus();
    }

    @Override
    public void beginFrame() {
        // debug
        // Note: this may be called from the Swing Timer (EDT) in this project
        System.out.println("[Renderer] beginFrame");
        synchronized (backBuffer) {
            backBuffer.clear();
        }
    }

    @Override
    public void endFrame() {
        System.out.println("[Renderer] endFrame - swapping buffers and repainting");
        synchronized (backBuffer) {
            // swap contents into frontBuffer by copying references
            frontBuffer.clear();
            frontBuffer.addAll(backBuffer);
        }
        gamePanel.repaint();
    }

    @Override
    public void drawRect(float x, float y, float width, float height, float r, float g, float b, float a) {
        synchronized (backBuffer) {
            backBuffer.add(new RectDrawable(x, y, width, height, r, g, b, a));
        }
    }

    @Override
    public void drawText(String text, int x, int y, Color color) {
        synchronized (backBuffer) {
            backBuffer.add(new TextDrawable(text, x, y, color));
        }
    }

    @Override
    public void drawCircle(float x, float y, float radius, int segments, float r, float g, float b, float a) {
        synchronized (backBuffer) {
            backBuffer.add(new CircleDrawable(x, y, radius, r, g, b, a));
        }
    }

    @Override
    public void drawLine(float x1, float y1, float x2, float y2, float r, float g, float b, float a) {
        synchronized (backBuffer) {
            backBuffer.add(new LineDrawable(x1, y1, x2, y2, r, g, b, a));
        }
    }

    @Override
    public void drawTriangle(float x, float y, float size, float rotation, float r, float g, float b, float a) {
        synchronized (backBuffer) {
            backBuffer.add(new TriangleDrawable(x, y, size, rotation, r, g, b, a));
        }
    }

    @Override
    public boolean shouldClose() {
        return !isVisible();
    }

    @Override
    public void pollEvents() {
        // Swing event pump is handled by EDT
    }

    @Override
    public void cleanup() {
        dispose();
    }

    @Override
    public int getWidth() { return width; }

    @Override
    public int getHeight() { return height; }

    @Override
    public String getTitle() { return title; }

    private static class TextDrawable implements Drawable {
        private String text;
        private int x, y;
        private Color color;

        public TextDrawable(String text, int x, int y, Color color) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.color = color;
        }

        @Override
        public void draw(Graphics2D g) {
            g.setColor(color);
            g.setFont(new Font("Arial", Font.BOLD, 14));
            g.drawString(text, x, y);
        }
    }
    
    
    /**
     * 游戏面板类
     */
    private class GamePanel extends JPanel {
        public GamePanel() {
            setPreferredSize(new Dimension(width, height));
            setBackground(Color.BLACK);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // snapshot frontBuffer without holding backBuffer lock
            java.util.List<Drawable> snapshot;
            synchronized (backBuffer) {
                snapshot = new ArrayList<>(frontBuffer);
            }
            for (Drawable drawable : snapshot) {
                drawable.draw(g2d);
            }
        }
    }
    
    /**
     * 可绘制对象接口
     */
    private interface Drawable {
        void draw(Graphics2D g);
    }
    
    /**
     * 矩形绘制类
     */
    private static class RectDrawable implements Drawable {
        private float x, y, width, height;
        private Color color;
        
        public RectDrawable(float x, float y, float width, float height, float r, float g, float b, float a) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.color = new Color(r, g, b, a);
        }
        
        @Override
        public void draw(Graphics2D g) {
            g.setColor(color);
            g.fillRect((int) x, (int) y, (int) width, (int) height);
        }
    }

    /**
     * 三角形绘制类（中心点，size 为边长近似，以 rotation 旋转）
     */
    private static class TriangleDrawable implements Drawable {
        private float x, y, size, rotation;
        private Color color;

        public TriangleDrawable(float x, float y, float size, float rotation, float r, float g, float b, float a) {
            this.x = x;
            this.y = y;
            this.size = size;
            this.rotation = rotation;
            this.color = new Color(r, g, b, a);
        }

        @Override
        public void draw(Graphics2D g) {
            g.setColor(color);
            double half = size / 2.0;
            // base triangle centered at (0,0): pointing up
            double[] bx = new double[] {0, -half, half};
            double[] by = new double[] {-half, half, half};

            double rad = Math.toRadians(rotation);
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);

            int[] px = new int[3];
            int[] py = new int[3];
            for (int i = 0; i < 3; i++) {
                double rx = bx[i] * cos - by[i] * sin;
                double ry = bx[i] * sin + by[i] * cos;
                px[i] = (int) Math.round(x + rx);
                py[i] = (int) Math.round(y + ry);
            }

            // fill
            g.fillPolygon(px, py, 3);
            // draw stroke (slightly darker) to improve contrast on dark background
            Color stroke = new Color(Math.max(0f, color.getRed()/255f - 0.2f), Math.max(0f, color.getGreen()/255f - 0.2f), Math.max(0f, color.getBlue()/255f - 0.2f), color.getAlpha()/255f);
            g.setColor(new java.awt.Color(stroke.getRed(), stroke.getGreen(), stroke.getBlue(), Math.min(255, (int)(stroke.getAlpha()*255))));
            g.setStroke(new BasicStroke(2));
            g.drawPolygon(px, py, 3);
        }
    }
    
    /**
     * 圆形绘制类
     */
    private static class CircleDrawable implements Drawable {
        private float x, y, radius;
        private Color color;
        
        public CircleDrawable(float x, float y, float radius, float r, float g, float b, float a) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.color = new Color(r, g, b, a);
        }
        
        @Override
        public void draw(Graphics2D g) {
            g.setColor(color);
            g.fillOval((int) (x - radius), (int) (y - radius), (int) (radius * 2), (int) (radius * 2));
        }
    }
    
    /**
     * 线条绘制类
     */
    private static class LineDrawable implements Drawable {
        private float x1, y1, x2, y2;
        private Color color;
        
        public LineDrawable(float x1, float y1, float x2, float y2, float r, float g, float b, float a) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.color = new Color(r, g, b, a);
        }
        
        @Override
        public void draw(Graphics2D g) {
            g.setColor(color);
            g.drawLine((int) x1, (int) y1, (int) x2, (int) y2);
        }
    }
}
