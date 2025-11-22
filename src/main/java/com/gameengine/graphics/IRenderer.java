package com.gameengine.graphics;

import java.awt.Color;

public interface IRenderer {
    void beginFrame();
    void endFrame();

    void drawRect(float x, float y, float width, float height, float r, float g, float b, float a);
    void drawCircle(float x, float y, float radius, int segments, float r, float g, float b, float a);
    void drawLine(float x1, float y1, float x2, float y2, float r, float g, float b, float a);
    void drawTriangle(float x, float y, float size, float rotation, float r, float g, float b, float a);
    void drawText(String text, int x, int y, Color color);

    boolean shouldClose();
    void pollEvents();
    void cleanup();

    int getWidth();
    int getHeight();
    String getTitle();
}
