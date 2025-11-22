package com.gameengine.example;

import com.gameengine.core.GameEngine;

/**
 * 兼容性包装：保留原来在 example 包下的类名，委托到新的 scene 包下的实现。
 */
public class MenuScene extends com.gameengine.scene.MenuScene {
    public MenuScene(GameEngine engine, String name) {
        super(engine, name);
    }
}
