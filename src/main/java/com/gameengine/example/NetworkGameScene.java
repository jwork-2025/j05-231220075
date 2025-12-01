package com.gameengine.example;

import com.gameengine.components.RenderComponent;
import com.gameengine.components.TransformComponent;
import com.gameengine.core.GameObject;
import com.gameengine.graphics.IRenderer;
import com.gameengine.input.InputManager;
import com.gameengine.math.Vector2;
import com.gameengine.net.NioClient;
import com.gameengine.net.NetworkBuffer;
import com.gameengine.net.NetState;
import com.gameengine.core.GameEngine;

/**
 * 客户端场景：从 NetworkBuffer 采样插值并发送输入。
 */
public class NetworkGameScene extends com.gameengine.scene.Scene {
    private final IRenderer renderer;
    private final InputManager input;
    private final NioClient client;
    private final NetworkBuffer buffer;
    private final GameEngine engine;
    private final java.util.Map<Integer, GameObject> players = new java.util.HashMap<>();
    private final java.util.Map<Integer, GameObject> bullets = new java.util.HashMap<>();

    public NetworkGameScene(GameEngine engine, IRenderer renderer, InputManager input, NioClient client, NetworkBuffer buffer) {
        super("NetworkGameScene");
        this.engine = engine;
        this.renderer = renderer;
        this.input = input;
        this.client = client;
        this.buffer = buffer;
    }

    @Override
    public void initialize() {
        super.initialize();
        // 所有对象按需创建
    }

    @Override
    public void update(float deltaTime) {
        // 采样当前快照并检查自身是否死亡
        java.util.Map<Integer, NetState.EntitySnapshot> latest = buffer.sampleMap(System.currentTimeMillis());
        NetState.EntitySnapshot meSnap = latest.get(client.getOwnId());
        boolean dead = (meSnap != null && meSnap.hp <= 0);

        if (!dead) {
            // 采集输入（箭头键），转换为方向向量 + 射击 + 视线方向
            float vx = 0f, vy = 0f;
            if (input.isKeyPressed(37)) vx -= 1f; // LEFT
            if (input.isKeyPressed(39)) vx += 1f; // RIGHT
            if (input.isKeyPressed(38)) vy -= 1f; // UP
            if (input.isKeyPressed(40)) vy += 1f; // DOWN
            boolean fire = input.isKeyJustPressed(32) || input.isMouseButtonJustPressed(0);
            // 计算瞄准方向：鼠标位置 - 自己玩家位置
            float ax = 0f, ay = -1f; // 默认向上
            if (meSnap != null) {
                float mx = input.getMouseX();
                float my = input.getMouseY();
                ax = mx - meSnap.x;
                ay = my - meSnap.y;
                float norm = (float)Math.sqrt(ax*ax + ay*ay);
                if (norm > 0.0001f) { ax /= norm; ay /= norm; } else { ax = 0f; ay = -1f; }
            }
            client.sendInput(vx, vy, fire, ax, ay);
        } else {
            // 死亡后按键：R 重连进入联机，M 返回菜单
            if (input.isKeyJustPressed('R') || input.isKeyJustPressed(82)) {
                try {
                    client.stop();
                } catch (Exception ignored) {}
                com.gameengine.net.NetworkBuffer nb = new com.gameengine.net.NetworkBuffer();
                com.gameengine.net.NioClient nc = new com.gameengine.net.NioClient("127.0.0.1", 7777, nb);
                new Thread(nc, "nio-client").start();
                com.gameengine.example.NetworkGameScene netScene = new com.gameengine.example.NetworkGameScene(engine, renderer, input, nc, nb);
                engine.setScene(netScene);
                return;
            }
            if (input.isKeyJustPressed('M') || input.isKeyJustPressed(77)) {
                client.stop();
                engine.setScene(new com.gameengine.scene.MenuScene(engine, "MainMenu"));
                return;
            }
        }

        // 插值采样服务器广播的关键帧
        java.util.Map<Integer, NetState.EntitySnapshot> map = buffer.sampleMap(System.currentTimeMillis());
        java.util.Set<Integer> seenPlayers = new java.util.HashSet<>();
        java.util.Set<Integer> seenBullets = new java.util.HashSet<>();
        int selfId = client.getOwnId();

        for (NetState.EntitySnapshot e : map.values()) {
            if (e.kind == NetState.KIND_PLAYER) {
                seenPlayers.add(e.id);
                GameObject obj = players.get(e.id);
                if (obj == null) {
                    if (e.id == selfId) obj = EntityFactory.createPlayerVisual(renderer);
                    else obj = EntityFactory.createAIVisual(renderer, 20, 20, 1.0f, 0.3f, 0.3f, 1.0f);
                    players.put(e.id, obj);
                    addGameObject(obj);
                }
                TransformComponent tc = obj.getComponent(TransformComponent.class);
                if (tc != null) tc.setPosition(new Vector2(e.x, e.y));
            } else if (e.kind == NetState.KIND_BULLET) {
                seenBullets.add(e.id);
                GameObject obj = bullets.get(e.id);
                if (obj == null) {
                    GameObject b = new GameObject("Bullet");
                    b.addComponent(new TransformComponent(new Vector2(e.x, e.y)));
                    RenderComponent rc = b.addComponent(new RenderComponent(RenderComponent.RenderType.CIRCLE, new Vector2(6,6), new RenderComponent.Color(1.0f, 0.95f, 0.2f, 1.0f)));
                    rc.setRenderer(renderer);
                    bullets.put(e.id, b);
                    addGameObject(b);
                    obj = b;
                }
                TransformComponent tc = obj.getComponent(TransformComponent.class);
                if (tc != null) tc.setPosition(new Vector2(e.x, e.y));
            }
        }
        // 清理不在快照中的对象 (安全迭代移除避免 CME)
        java.util.Iterator<Integer> pit = players.keySet().iterator();
        while (pit.hasNext()) {
            int id = pit.next();
            if (!seenPlayers.contains(id)) {
                GameObject obj = players.get(id);
                if (obj != null) obj.destroy();
                pit.remove();
            }
        }
        java.util.Iterator<Integer> bit = bullets.keySet().iterator();
        while (bit.hasNext()) {
            int id = bit.next();
            if (!seenBullets.contains(id)) {
                GameObject obj = bullets.get(id);
                if (obj != null) obj.destroy();
                bit.remove();
            }
        }

        // 继续处理场景内对象更新
        super.update(deltaTime);
    }

    private boolean removeObject(java.util.Map<Integer, GameObject> map, int id) {
        GameObject obj = map.remove(id);
        if (obj != null) { obj.destroy(); return true; }
        return false;
    }

    @Override
    public void render() {
        super.render();
        // HUD: 自身 HP 与标签
        int selfId = client.getOwnId();
        java.util.Map<Integer, NetState.EntitySnapshot> snap = buffer.sampleMap(System.currentTimeMillis());
        NetState.EntitySnapshot me = snap.get(selfId);
        if (me != null) {
            renderer.drawText("HP:" + me.hp, 16, 24, new java.awt.Color(255, 255, 255));
            if (me.hp <= 0) {
                String msg = "DEAD: press R to rejoin, M to menu";
                int w = msg.length() * 12;
                int cx = renderer.getWidth() / 2;
                int cy = renderer.getHeight() / 2;
                renderer.drawRect(cx - w/2 - 10, cy - 30, w + 20, 60, 0.2f,0.2f,0.2f,0.8f);
                renderer.drawText(msg, cx - w/2, cy, new java.awt.Color(255,240,100));
            }
        }

        // 绘制其他玩家名字（如果有）
        for (NetState.EntitySnapshot e : snap.values()) {
            if (e.kind == NetState.KIND_PLAYER) {
                String nm = client.getName(e.id);
                if (nm != null && !nm.isEmpty()) {
                    renderer.drawText(nm, (int)e.x + 10, (int)e.y - 36, new java.awt.Color(220, 220, 220));
                }
            }
        }
    }
}
