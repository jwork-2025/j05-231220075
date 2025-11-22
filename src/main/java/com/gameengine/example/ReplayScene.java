package com.gameengine.example;

import com.gameengine.core.GameEngine;

/**
 * 兼容性包装：保留原来在 example 包下的类名，委托到新的 scene 包下的实现。
 */
public class ReplayScene extends com.gameengine.scene.ReplayScene {
    public ReplayScene(GameEngine engine, String path) {
        super(engine, path);
    }
}
