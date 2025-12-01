package com.gameengine.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Minimal NIO server broadcasting JSON line keyframes and reading INPUT lines.
 */
public class NioServer implements Runnable {
    private final int port;
    private volatile boolean running = true;
    private Selector selector;
    private ServerSocketChannel server;
    private final Map<SocketChannel,StringBuilder> recvBuf = new HashMap<>();

    // Simple world state: a single entity moving with last input per client
    private static class ClientInput { float vx, vy; boolean fire; float ax, ay; }
    private static class Player { int id; float x, y; int hp = 100; float cd=0; String name = "Player"; boolean dead = false; }
    private static class Bullet { int id; float x, y, vx, vy; int owner; float life=2.0f; }

    private final Map<SocketChannel, ClientInput> inputs = new HashMap<>();
    private final Map<SocketChannel, Player> players = new HashMap<>();
    private final List<Bullet> bullets = new ArrayList<>();
    private int nextPlayerId = 1;
    private int nextBulletId = 10000;

    public NioServer(int port) { this.port = port; }

    public void stop() { running = false; if (selector != null) selector.wakeup(); }

    @Override public void run() {
        try {
            selector = Selector.open();
            server = ServerSocketChannel.open();
            server.configureBlocking(false);
            server.bind(new InetSocketAddress(port));
            server.register(selector, SelectionKey.OP_ACCEPT);

            long lastBroadcast = System.currentTimeMillis();
            long lastPhysics = lastBroadcast;
            while (running) {
                selector.select(25);
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next(); it.remove();
                    if (!key.isValid()) continue;
                    if (key.isAcceptable()) accept();
                    if (key.isReadable()) read(key);
                }
                long now = System.currentTimeMillis();
                float dt = (now - lastPhysics) / 1000.0f;
                lastPhysics = now;
                stepWorld(dt);
                if (now - lastBroadcast >= 50) { // 20Hz
                    broadcastKF(now);
                    lastBroadcast = now;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try { if (server != null) server.close(); } catch (IOException ignored) {}
            try { if (selector != null) selector.close(); } catch (IOException ignored) {}
        }
    }

    private void accept() throws IOException {
        SocketChannel sc = server.accept();
        if (sc == null) return;
        sc.configureBlocking(false);
        sc.register(selector, SelectionKey.OP_READ);
        recvBuf.put(sc, new StringBuilder());
        inputs.put(sc, new ClientInput());
        Player p = new Player();
        p.id = nextPlayerId++;
        p.x = 400; p.y = 300;
        players.put(sc, p);
        // welcome with assigned id
        String welcome = "{\"type\":\"welcome\",\"id\":" + p.id + "}\n";
        try { sc.write(ByteBuffer.wrap(welcome.getBytes(StandardCharsets.UTF_8))); } catch (IOException ignored) {}
    }

    private void read(SelectionKey key) throws IOException {
        SocketChannel sc = (SocketChannel) key.channel();
        ByteBuffer buf = ByteBuffer.allocate(1024);
        int n = sc.read(buf);
        if (n <= 0) { // closed
            cleanup(sc);
            return;
        }
        buf.flip();
        String s = StandardCharsets.UTF_8.decode(buf).toString();
        StringBuilder sb = recvBuf.get(sc);
        sb.append(s);
        int idx;
        while ((idx = sb.indexOf("\n")) >= 0) {
            String line = sb.substring(0, idx).trim();
            sb.delete(0, idx + 1);
            if (line.startsWith("INPUT:")) {
                String[] parts = line.substring(6).split(",");
                try {
                    float vx = Float.parseFloat(parts[0]);
                    float vy = Float.parseFloat(parts[1]);
                    boolean fire = parts.length > 2 && ("1".equals(parts[2]) || "true".equalsIgnoreCase(parts[2]));
                    float ax = parts.length > 3 ? Float.parseFloat(parts[3]) : 0f;
                    float ay = parts.length > 4 ? Float.parseFloat(parts[4]) : -1f;
                    ClientInput ci = inputs.get(sc);
                    if (ci != null) { ci.vx = vx; ci.vy = vy; ci.fire = fire; ci.ax = ax; ci.ay = ay; }
                } catch (Exception ignored) { }
            } else if (line.startsWith("HELLO:")) {
                String nm = line.substring(6).trim();
                Player p = players.get(sc);
                if (p != null && nm.length() > 0) p.name = nm;
            }
        }
    }

    private void cleanup(SocketChannel sc) throws IOException {
        recvBuf.remove(sc);
        inputs.remove(sc);
        players.remove(sc);
        try { sc.close(); } catch (IOException ignored) {}
    }

    private void broadcastKF(long now) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"kf\",\"t\":").append(now/1000.0).append(",\"entities\":[");
        boolean first = true;
          for (Player p : players.values()) {
            if (!first) sb.append(','); first = false;
            sb.append('{').append("\"id\":").append(p.id)
              .append(",\"x\":").append(p.x).append(",\"y\":").append(p.y)
              .append(",\"k\":\"p\",\"hp\":").append(p.hp)
              .append(",\"nm\":\"").append(p.name.replace("\"","\"\""))
              .append("\"}");
        }
        for (Bullet b : bullets) {
            if (!first) sb.append(','); first = false;
            sb.append('{').append("\"id\":").append(b.id)
              .append(",\"x\":").append(b.x).append(",\"y\":").append(b.y)
              .append(",\"k\":\"b\",\"o\":").append(b.owner).append('}');
        }
        sb.append("]}\n");
        String json = sb.toString();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        for (SelectionKey k : selector.keys()) {
            if (k.channel() instanceof SocketChannel) {
                SocketChannel sc = (SocketChannel) k.channel();
                try {
                    sc.write(ByteBuffer.wrap(bytes));
                } catch (IOException e) {
                    try { cleanup(sc); } catch (IOException ignored) {}
                }
            }
        }
    }

    private void stepWorld(float dt) {
        // integrate players
        float speed = 200f;
        for (Map.Entry<SocketChannel, ClientInput> entry : inputs.entrySet()) {
            Player p = players.get(entry.getKey());
            if (p == null) continue;
            ClientInput ci = entry.getValue();
            p.x += ci.vx * speed * dt;
            p.y += ci.vy * speed * dt;
            // clamp bounds
            if (p.x < 0) p.x = 0; if (p.y < 0) p.y = 0; if (p.x > 800) p.x = 800; if (p.y > 600) p.y = 600;
            // shooting with cooldown
            p.cd -= dt;
            if (ci.fire && p.cd <= 0) {
                Bullet b = new Bullet();
                b.id = nextBulletId++;
                b.x = p.x; b.y = p.y; b.owner = p.id;
                float bx = ci.ax, by = ci.ay;
                float norm = (float)Math.sqrt(bx*bx + by*by);
                if (norm < 0.0001f) { bx = 0f; by = -1f; norm = 1f; }
                bx /= norm; by /= norm;
                float bs = 400f;
                b.vx = bx * bs; b.vy = by * bs;
                bullets.add(b);
                p.cd = 0.3f; // 300ms
            }
            ci.fire = false; // consume
        }
        // integrate bullets and collisions
        Iterator<Bullet> it = bullets.iterator();
        while (it.hasNext()) {
            Bullet b = it.next();
            b.x += b.vx * dt; b.y += b.vy * dt; b.life -= dt;
            if (b.life <= 0) { it.remove(); continue; }
            for (Player p : players.values()) {
                if (p.id == b.owner || p.hp <= 0) continue;
                float dx = p.x - b.x, dy = p.y - b.y;
                if (dx*dx + dy*dy < 20*20) { // hit radius
                    p.hp = Math.max(0, p.hp - 10);
                    if (p.hp == 0) p.dead = true;
                    it.remove();
                    break;
                }
            }
        }
    }
}
