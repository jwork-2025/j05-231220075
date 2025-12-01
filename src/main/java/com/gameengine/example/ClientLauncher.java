package com.gameengine.example;

import com.gameengine.core.GameEngine;
import com.gameengine.graphics.IRenderer;
import com.gameengine.input.InputManager;
import com.gameengine.net.NioClient;
import com.gameengine.net.NetworkBuffer;

public class ClientLauncher {
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = 7777;
        NetworkBuffer buffer = new NetworkBuffer();
        NioClient client = new NioClient(host, port, buffer);
        Thread t = new Thread(client, "nio-client");
        t.start();

        GameEngine engine = new GameEngine(1280, 800, "网络客户端");
        IRenderer renderer = engine.getRenderer();
        InputManager input = engine.getInputManager();

        NetworkGameScene scene = new NetworkGameScene(engine, renderer, input, client, buffer);
        engine.setScene(scene);
        engine.run();
    }
}
