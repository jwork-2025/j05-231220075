package com.gameengine.core;

import com.gameengine.graphics.IRenderer;
import com.gameengine.graphics.RendererFactory;
import com.gameengine.recording.RecordingConfig;
import com.gameengine.recording.RecordingService;
import com.gameengine.input.InputManager;
import com.gameengine.scene.Scene;
 

/**
 * 游戏引擎
 */
public class GameEngine {
    private IRenderer renderer;
    private com.gameengine.recording.RecordingService recordingService;
    private InputManager inputManager;
    private Scene currentScene;
    private boolean running;
    private float targetFPS;
    private float deltaTime;
    private long lastTime;
    private String title;
    
    
    public GameEngine(int width, int height, String title) {
    this.title = title;
    this.renderer = RendererFactory.createRenderer(width, height, title);
    this.recordingService = new RecordingService(new RecordingConfig());
        this.inputManager = InputManager.getInstance();
        this.running = false;
        this.targetFPS = 60.0f;
        this.deltaTime = 0.0f;
        this.lastTime = System.nanoTime();
    }
    
    /**
     * 初始化游戏引擎
     */
    public boolean initialize() {
        return true; // Swing渲染器不需要特殊初始化
    }
    
    /**
     * 运行游戏引擎
     */
    public void run() {
        if (!initialize()) {
            System.err.println("游戏引擎初始化失败");
            return;
        }
        System.out.println("[GameEngine] run() starting");
        
        running = true;
        
        // 初始化当前场景
        if (currentScene != null) {
            currentScene.initialize();
        }
        
        // 使用独立循环（与 j05 保持一致），避免在 EDT 上运行整个游戏循环（兼容 GPU 后端）
        while (running) {
            // Per-frame input lifecycle:
            // 1) clear previous-frame "just pressed" (update())
            // 2) poll events which will call callbacks and add new just-pressed entries
            // 3) run game update() which can query isKeyJustPressed for this frame
            inputManager.update();

            renderer.pollEvents();

            if (renderer.shouldClose()) {
                running = false;
                break;
            }

            update();
            if (running) {
                render();
            }

            try { Thread.sleep(1); } catch (InterruptedException ignored) {}
        }

        // 游戏循环结束时确保录制完整落盘
        stopRecording();
    }
    
    /**
     * 更新游戏逻辑
     */
    private void update() {
        // 计算时间间隔
        long currentTime = System.nanoTime();
        deltaTime = (currentTime - lastTime) / 1_000_000_000.0f; // 转换为秒
        lastTime = currentTime;
        
        // 更新场景
        if (currentScene != null) {
            currentScene.update(deltaTime);
        }

        // RecordingService update (记录输入与周期关键帧)
        try {
            if (recordingService != null) {
                recordingService.update(deltaTime, currentScene, inputManager);
            }
        } catch (Exception ignored) {}
        
        // 检查退出条件（ESC键）
        if (inputManager.isKeyPressed(27)) { // ESC键
            running = false;
            renderer.cleanup();
        }

        // 检查窗口是否关闭
        if (renderer.shouldClose()) {
            running = false;
        }
    }
    
    /**
     * 渲染游戏
     */
    private void render() {
        renderer.beginFrame();
        // 渲染场景
        if (currentScene != null) {
            currentScene.render();
        }
        renderer.endFrame();
    }
    
    /**
     * 设置当前场景
     */
    public void setScene(Scene scene) {
        this.currentScene = scene;
        if (scene != null && running) {
            scene.initialize();
        }
    }
    
    /**
     * 获取当前场景
     */
    public Scene getCurrentScene() {
        return currentScene;
    }
    
    /**
     * 停止游戏引擎
     */
    public void stop() {
        running = false;
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        if (currentScene != null) {
            currentScene.clear();
        }
        renderer.cleanup();
    }
    
    /**
     * 获取渲染器 (IRenderer)
     */
    public IRenderer getRenderer() {
        return renderer;
    }

    /**
     * 启动录制服务并开始写入（如果 scene 可用）
     */
    public void startRecording(com.gameengine.recording.RecordingService svc) {
        if (svc == null) {
            return;
        }
        // 先停止旧的录制服务，避免线程泄漏或文件句柄未关闭
        stopRecordingInternal();
        this.recordingService = svc;
        try {
            if (currentScene != null) {
                svc.start(currentScene, renderer.getWidth(), renderer.getHeight());
            }
        } catch (Exception e) {
            System.err.println("Failed to start recording: " + e.getMessage());
        }
    }

    /**
     * 停止当前的录制服务
     */
    public void stopRecording() {
        stopRecordingInternal();
    }

    private void stopRecordingInternal() {
        if (recordingService == null) {
            return;
        }
        try {
            recordingService.stop();
        } catch (Exception ignored) {
        }
    }
    
    /**
     * 获取输入管理器
     */
    public InputManager getInputManager() {
        return inputManager;
    }
    
    /**
     * 获取时间间隔
     */
    public float getDeltaTime() {
        return deltaTime;
    }
    
    /**
     * 设置目标帧率
     */
    public void setTargetFPS(float fps) {
        this.targetFPS = fps;
    }
    
    /**
     * 获取目标帧率
     */
    public float getTargetFPS() {
        return targetFPS;
    }
    
    /**
     * 检查引擎是否正在运行
     */
    public boolean isRunning() {
        return running;
    }
}
