package com.gameengine.example;

import com.gameengine.components.*;
import com.gameengine.core.GameObject;
import com.gameengine.core.GameEngine;
import com.gameengine.core.GameLogic;
import com.gameengine.graphics.Renderer;
import com.gameengine.math.Vector2;
import com.gameengine.scene.Scene;

import java.util.List;
import java.util.Random;
import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;

/**
 * 游戏示例
 */
public class GameExample {
    public static void main(String[] args) {
        System.out.println("启动游戏引擎...");
        
        try {
            runGameLoop();
        } catch (Exception e) {
            System.err.println("游戏运行出错: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("游戏结束");
    }

    private static void runGameLoop() {
        boolean restart = true;
        while (restart) {
            // holder to obtain the GameLogic instance from the anonymous Scene so we can read survival time
            final com.gameengine.core.GameLogic[] gameLogicHolder = new com.gameengine.core.GameLogic[1];
            GameEngine engine = new GameEngine(800, 600, "游戏引擎");
            // 创建主菜单场景
            Scene menu = new com.gameengine.scene.MenuScene(engine, "MainMenu");
            
            // 设置场景为主菜单并运行引擎
            engine.setScene(menu);
            engine.run();

            // 主循环：当场景中 gameLogic 标记 gameOver 时，停止并清理当前引擎
            // 等待直到引擎停止或窗口关闭
            while (engine.isRunning()) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }

            // 清理渲染器与场景资源
            try {
                if (engine.getCurrentScene() != null) engine.getCurrentScene().clear();
            } catch (Exception ignored) {}
            try {
                if (engine.getRenderer() != null) engine.getRenderer().cleanup();
            } catch (Exception ignored) {}

            // 询问玩家是否重新开始（在主线程弹框），并显示本局存活时间
            float survival = 0f;
            try { if (gameLogicHolder[0] != null) survival = gameLogicHolder[0].getSurvivalTime(); } catch (Exception ignored) {}
            String msg = String.format("你本局存活: %.2f 秒。是否重新开始？", survival);
            if (GraphicsEnvironment.isHeadless()) {
                // Running in headless mode (we use headless AWT for texture generation).
                // Skip interactive dialog and do not restart by default.
                System.out.println(msg + " (headless mode - not prompting, exiting)");
                restart = false;
            } else {
                int res = JOptionPane.showConfirmDialog(null, msg, "重新开始", JOptionPane.YES_NO_OPTION);
                restart = (res == JOptionPane.YES_OPTION);
            }

            if (!restart) {
                // 退出整个循环，游戏结束
                break;
            }
            // 否则 while 循环会创建新的 GameEngine 与 Scene 并开始新局
        }
    }
}
