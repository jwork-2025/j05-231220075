package com.gameengine.example;

import com.gameengine.core.GameEngine;

/**
 * 兼容性包装：原来的 example.GameScene 现在映射到通用的 Scene 实例。
 */
public class GameScene extends com.gameengine.scene.Scene {
    public GameScene(GameEngine engine) {
        super("GameScene");
    }
}
