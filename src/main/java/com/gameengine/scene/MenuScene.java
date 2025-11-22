package com.gameengine.scene;

import com.gameengine.core.GameEngine;
import com.gameengine.graphics.IRenderer;
import com.gameengine.input.InputManager;
import org.lwjgl.glfw.GLFW;
import com.gameengine.math.Vector2;
import com.gameengine.recording.RecordingConfig;
import com.gameengine.recording.RecordingService;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import com.gameengine.core.GameObject;
import com.gameengine.components.*;
import com.gameengine.math.Vector2;
import java.util.Random;

public class MenuScene extends Scene {
    public enum MenuOption {
        START_GAME,
        REPLAY,
        EXIT
    }
    
    private IRenderer renderer;
    private InputManager inputManager;
    private GameEngine engine;
    private int selectedIndex;
    private MenuOption[] options;
    private boolean selectionMade;
    private MenuOption selectedOption;
    private List<String> replayFiles;
    private boolean showReplayInfo;
    private int debugFrames;
    
    public MenuScene(GameEngine engine, String name) {
        super(name);
        this.engine = engine;
        this.renderer = engine.getRenderer();
        this.inputManager = InputManager.getInstance();
        this.selectedIndex = 0;
        this.options = new MenuOption[]{MenuOption.START_GAME, MenuOption.REPLAY, MenuOption.EXIT};
        this.selectionMade = false;
        this.selectedOption = null;
        this.replayFiles = new ArrayList<>();
        this.showReplayInfo = false;
    }
    
    private void loadReplayFiles() {}
    
    @Override
    public void initialize() {
        super.initialize();
        loadReplayFiles();
        selectedIndex = 0;
        selectionMade = false;
        debugFrames = 0;
        // 清除上一场景残留的刚按下按键，防止例如从 Replay 返回时上一次的 ESC 被立即消费导致程序退出
        InputManager.getInstance().update();
    }
    
    @Override
    public void update(float deltaTime) {
        super.update(deltaTime);
        
        handleMenuSelection();
        
        if (selectionMade) {
            processSelection();
        }
    }
    
    private void handleMenuSelection() {
        if (inputManager.isKeyJustPressed(GLFW.GLFW_KEY_UP) || inputManager.isKeyJustPressed(38)) {
            selectedIndex = (selectedIndex - 1 + options.length) % options.length;
        } else if (inputManager.isKeyJustPressed(GLFW.GLFW_KEY_DOWN) || inputManager.isKeyJustPressed(40)) {
            selectedIndex = (selectedIndex + 1) % options.length;
        } else if (inputManager.isKeyJustPressed(GLFW.GLFW_KEY_ENTER) || inputManager.isKeyJustPressed(10) || inputManager.isKeyJustPressed(GLFW.GLFW_KEY_SPACE) || inputManager.isKeyJustPressed(32)) {
            selectionMade = true;
            selectedOption = options[selectedIndex];
            
            if (selectedOption == MenuOption.REPLAY) {
                engine.stopRecording();
                Scene replay = new ReplayScene(engine, null);
                engine.setScene(replay);
            } else if (selectedOption == MenuOption.EXIT) {
                engine.stop();
                engine.cleanup();
                System.exit(0);
            }
        }
        
        Vector2 mousePos = inputManager.getMousePosition();
        // ESC to exit
        if (inputManager.isKeyJustPressed(GLFW.GLFW_KEY_ESCAPE) || inputManager.isKeyJustPressed(27)) {
            engine.stop();
            engine.cleanup();
            System.exit(0);
        }
        if (inputManager.isMouseButtonJustPressed(0)) {
            float centerY = renderer.getHeight() / 2.0f;
            float buttonY1 = centerY - 80;
            float buttonY2 = centerY + 0;
            float buttonY3 = centerY + 80;
            
            if (mousePos.y >= buttonY1 - 30 && mousePos.y <= buttonY1 + 30) {
                selectedIndex = 0;
                selectionMade = true;
                selectedOption = MenuOption.START_GAME;
            } else if (mousePos.y >= buttonY2 - 30 && mousePos.y <= buttonY2 + 30) {
                selectedIndex = 1;
                selectedOption = MenuOption.REPLAY;
                engine.stopRecording();
                Scene replay = new ReplayScene(engine, null);
                engine.setScene(replay);
            } else if (mousePos.y >= buttonY3 - 30 && mousePos.y <= buttonY3 + 30) {
                selectedIndex = 2;
                selectionMade = true;
                selectedOption = MenuOption.EXIT;
                engine.stop();
                engine.cleanup();
                System.exit(0);
            }
        }
    }

    private String findLatestRecording() {
        File dir = new File("recordings");
        if (!dir.exists() || !dir.isDirectory()) return null;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json") || name.endsWith(".jsonl"));
        if (files == null || files.length == 0) return null;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return files[0].getAbsolutePath();
    }

    private void processSelection() {
        if (selectedOption == MenuOption.START_GAME) {
            createAndSetGameScene();
        }
    }

    private void switchToGameScene() {
        createAndSetGameScene();
    }

    /**
     * 创建并设置一个新的游戏 Scene（用于开始和重启）
     */
    private void createAndSetGameScene() {
        // 清除输入快照，避免刚按下的按键被新场景误判
        InputManager.getInstance().update();

        // Build a basic game scene using the original Scene class and populate it
        Scene gameScene = new Scene("GameScene");
        IRenderer renderer = engine.getRenderer();
        // create player
        GameObject player = new GameObject("Player") {
            @Override
            public void update(float dt) { super.update(dt); updateComponents(dt); }
            @Override
            public void render() { renderComponents(); }
        };
        player.addComponent(new TransformComponent(new Vector2(400, 300)));
        PhysicsComponent physics = player.addComponent(new PhysicsComponent(1.0f));
        physics.setFriction(0.94f);
        player.addComponent(new HealthComponent(100));
        RenderComponent playerRender = player.addComponent(new RenderComponent(
            RenderComponent.RenderType.TRIANGLE,
            new Vector2(24, 28),
            new RenderComponent.Color(0.2f, 0.85f, 1.0f, 1.0f)
        ));
        playerRender.setRenderer(renderer);
        playerRender.setRotation(180f);
        gameScene.addGameObject(player);

        // add enemies
        Random random = new Random();
        for (int i = 0; i < 10; i++) {
            GameObject enemy = new GameObject("Enemy") {
                @Override
                public void update(float dt) { super.update(dt); updateComponents(dt); }
                @Override
                public void render() { renderComponents(); }
            };
            Vector2 position = new Vector2(random.nextFloat() * renderer.getWidth(), random.nextFloat() * renderer.getHeight());
            enemy.addComponent(new TransformComponent(position));
            RenderComponent rc = enemy.addComponent(new RenderComponent(
                RenderComponent.RenderType.RECTANGLE,
                new Vector2(20,20),
                new RenderComponent.Color(1f,0.45f,0.1f,1f)
            ));
            rc.setRenderer(renderer);
            PhysicsComponent p = enemy.addComponent(new PhysicsComponent(0.5f));
            p.setVelocity(new Vector2(0,0));
            p.setFriction(0.98f);
            // give enemy shooting ability (cooldown seconds, speed, damage)
            com.gameengine.components.ShooterComponent shooter = enemy.addComponent(new com.gameengine.components.ShooterComponent(1.5f, 120.0f, 1));
            enemy.addComponent(new HealthComponent(10));
            gameScene.addGameObject(enemy);
        }

        // attach GameLogic as a GameObject so it receives update/render callbacks
        final com.gameengine.core.GameLogic gameLogic = new com.gameengine.core.GameLogic(gameScene, renderer);
        GameObject logicHolder = new GameObject("GameLogic") {
            private boolean recordingStopped = false;
            @Override
            public void update(float dt) {
                super.update(dt);
                // update game logic
                gameLogic.update(dt);
                // stop recording when game over
                if (gameLogic.isGameOver() && !recordingStopped) {
                    engine.stopRecording();
                    recordingStopped = true;
                }
                // allow restart when game is over by pressing R; M or ESC returns to menu
                if (gameLogic.isGameOver()) {
                    InputManager im = engine.getInputManager();
                    // R (82) -> restart
                    if (im.isKeyJustPressed(82) || im.isKeyJustPressed('R')) {
                        createAndSetGameScene();
                        return;
                    }
                    // ESC or M -> return to menu
                    if (im.isKeyJustPressed(GLFW.GLFW_KEY_ESCAPE) || im.isKeyJustPressed(27) || im.isKeyJustPressed('M') || im.isKeyJustPressed(77)) {
                        engine.setScene(new MenuScene(engine, "MainMenu"));
                        return;
                    }
                }
            }
            @Override
            public void render() {
                // render HUD
                gameLogic.renderHUD();
            }
        };
        gameScene.addGameObject(logicHolder);

        engine.setScene(gameScene);
        try {
            new File("recordings").mkdirs();
            String path = "recordings/session_" + System.currentTimeMillis() + ".jsonl";
            RecordingConfig cfg = new RecordingConfig(path);
            RecordingService svc = new RecordingService(cfg);
            engine.startRecording(svc);
        } catch (Exception e) {
        
        }
    }
    
    @Override
    public void render() {
        if (renderer == null) return;
        
        int width = renderer.getWidth();
        int height = renderer.getHeight();
        if (debugFrames < 5) {
            
            debugFrames++;
        }
        
        renderer.drawRect(0, 0, width, height, 0.25f, 0.25f, 0.35f, 1.0f);
        
        super.render();
        
        renderMainMenu();
    }
    
    private void renderMainMenu() {
        if (renderer == null) return;
        
        int width = renderer.getWidth();
        int height = renderer.getHeight();
        
        float centerX = width / 2.0f;
        float centerY = height / 2.0f;
        
        String title = "GAME ENGINE";
        float titleWidth = title.length() * 20.0f;
        float titleX = centerX - titleWidth / 2.0f;
        float titleY = 120.0f;
        
    renderer.drawRect(centerX - titleWidth / 2.0f - 20, titleY - 40, titleWidth + 40, 80, 0.4f, 0.4f, 0.5f, 1.0f);
    renderer.drawText(title, (int)titleX, (int)titleY, new java.awt.Color(255,255,255));
        
        for (int i = 0; i < options.length; i++) {
            String text = "";
            if (options[i] == MenuOption.START_GAME) {
                text = "START GAME";
            } else if (options[i] == MenuOption.REPLAY) {
                text = "REPLAY";
            } else if (options[i] == MenuOption.EXIT) {
                text = "EXIT";
            }
            
            float textWidth = text.length() * 20.0f;
            float textX = centerX - textWidth / 2.0f;
            float textY = centerY - 80.0f + i * 80.0f;
            
            if (i == selectedIndex) {
                renderer.drawRect(textX - 20, textY - 20, textWidth + 40, 50, 0.6f, 0.5f, 0.2f, 0.9f);
            } else {
                renderer.drawRect(textX - 20, textY - 20, textWidth + 40, 50, 0.2f, 0.2f, 0.3f, 0.5f);
            }
            
                renderer.drawText(text, (int)textX, (int)textY, new java.awt.Color(255,255,255));
        }
        
        String hint1 = "USE ARROWS OR MOUSE TO SELECT, ENTER TO CONFIRM";
        float hint1Width = hint1.length() * 20.0f;
        float hint1X = centerX - hint1Width / 2.0f;
    renderer.drawText(hint1, (int)hint1X, height - 100, new java.awt.Color(160,160,160));
        
        String hint2 = "ESC TO EXIT";
        float hint2Width = hint2.length() * 20.0f;
        float hint2X = centerX - hint2Width / 2.0f;
    renderer.drawText(hint2, (int)hint2X, height - 70, new java.awt.Color(160,160,160));

        if (showReplayInfo) {
            String info = "REPLAY COMING SOON";
            float w = info.length() * 20.0f;
            renderer.drawText(info, (int)(centerX - w / 2.0f), height - 140, new java.awt.Color(230,200,50));
        }
    }
    
}
