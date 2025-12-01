package com.gameengine.example;

import com.gameengine.net.NioServer;

public class ServerLauncher {
    public static void main(String[] args) {
        // Start server thread
        NioServer server = new NioServer(7777);
        Thread t = new Thread(server, "nio-server");
        t.start();

        // Headless: keep process alive until interrupted
        System.out.println("NIO server started on 7777. Press Ctrl+C to stop.");
        try {
            while (true) Thread.sleep(1000);
        } catch (InterruptedException ignored) {
            server.stop();
        }
    }
}
