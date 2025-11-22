package com.gameengine.scene;

import com.gameengine.components.*;
import com.gameengine.core.GameObject;
import com.gameengine.core.GameEngine;
import com.gameengine.core.GameLogic;
import com.gameengine.graphics.IRenderer;
import com.gameengine.input.InputManager;
import com.gameengine.math.Vector2;

import java.util.Random;

public class GameScene extends Scene {
    private final GameEngine engine;
    private GameLogic gameLogic;
    private Random random;
    private boolean recordingStopped;

    public GameScene(GameEngine engine) {
        super("GameScene");
        this.engine = engine;
        this.random = new Random();
        this.recordingStopped = false;
    }

    @Override
    public void initialize() {
        super.initialize();
        recordingStopped = false;
        IRenderer renderer = engine.getRenderer();
        this.gameLogic = new GameLogic(this, renderer);

        // create player
        GameObject player = new GameObject("Player") {
            @Override
            public void update(float dt) {
                super.update(dt);
                updateComponents(dt);
            }
            @Override
            public void render() {
                renderComponents();
            }
        };
        player.addComponent(new TransformComponent(new Vector2(400,300)));
        PhysicsComponent physics = player.addComponent(new PhysicsComponent(1.0f));
        physics.setFriction(0.94f);
        player.addComponent(new HealthComponent(1000));
        RenderComponent playerRender = player.addComponent(new RenderComponent(
            RenderComponent.RenderType.TRIANGLE,
            new Vector2(24, 28),
            new RenderComponent.Color(0.2f, 0.85f, 1.0f, 1.0f)
        ));
        playerRender.setRenderer(renderer);
        playerRender.setRotation(180f); // 指向上方，便于区分玩家与敌人
        addGameObject(player);

        // add a few enemies
        for (int i = 0; i < 10; i++) createEnemy();
    }

    private void createEnemy() {
        GameObject enemy = new GameObject("Enemy") {
            @Override
            public void update(float dt) { super.update(dt); updateComponents(dt); }
            @Override
            public void render() { renderComponents(); }
        };
        Vector2 position = new Vector2(random.nextFloat() * 800, random.nextFloat() * 600);
        enemy.addComponent(new TransformComponent(position));
        RenderComponent rc = enemy.addComponent(new RenderComponent(
            RenderComponent.RenderType.RECTANGLE,
            new Vector2(20,20),
            new RenderComponent.Color(1f,0.45f,0.1f,1f)
        ));
        rc.setRenderer(engine.getRenderer());
        PhysicsComponent p = enemy.addComponent(new PhysicsComponent(0.5f));
        p.setVelocity(new Vector2(0,0));
        p.setFriction(0.98f);
        enemy.addComponent(new HealthComponent(10));
        addGameObject(enemy);
    }

    @Override
    public void update(float deltaTime) {
        super.update(deltaTime);
        if (gameLogic != null) {
            gameLogic.update(deltaTime);
            if (gameLogic.isGameOver() && !recordingStopped) {
                engine.stopRecording();
                recordingStopped = true;
            }
        }

        if (gameLogic != null && gameLogic.isGameOver()) {
            InputManager input = engine.getInputManager();
            if (input.isKeyJustPressed('R') || input.isKeyJustPressed(10) || input.isKeyJustPressed(32)) {
                engine.setScene(new MenuScene(engine, "MainMenu"));
            }
        }
    }

    @Override
    public void render() {
        IRenderer r = engine.getRenderer();
        r.drawRect(0,0,r.getWidth(), r.getHeight(), 0.1f,0.1f,0.2f,1f);
        super.render();
        if (gameLogic != null) gameLogic.renderHUD();
    }
}
