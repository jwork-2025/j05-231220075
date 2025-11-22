package com.gameengine.example;

import com.gameengine.components.RenderComponent;
import com.gameengine.components.TransformComponent;
import com.gameengine.core.GameObject;
import com.gameengine.graphics.IRenderer;
import com.gameengine.math.Vector2;

public final class EntityFactory {
    private EntityFactory() {}

    public static GameObject createPlayerVisual(IRenderer renderer) {
        GameObject obj = new GameObject("Player");
        obj.addComponent(new TransformComponent(new Vector2(0, 0)));
        RenderComponent rc = obj.addComponent(new RenderComponent(
            RenderComponent.RenderType.TRIANGLE,
            new Vector2(24, 28),
            new RenderComponent.Color(0.2f, 0.85f, 1.0f, 1.0f)
        ));
        rc.setRenderer(renderer);
        rc.setRotation(180f);
        return obj;
    }

    public static GameObject createAIVisual(IRenderer renderer, float w, float h, float r, float g, float b, float a) {
        GameObject obj = new GameObject("AIPlayer");
        obj.addComponent(new TransformComponent(new Vector2(0, 0)));
        RenderComponent rc = obj.addComponent(new RenderComponent(
            RenderComponent.RenderType.RECTANGLE,
            new Vector2(Math.max(1, w), Math.max(1, h)),
            new RenderComponent.Color(r, g, b, a)
        ));
        rc.setRenderer(renderer);
        return obj;
    }
}
