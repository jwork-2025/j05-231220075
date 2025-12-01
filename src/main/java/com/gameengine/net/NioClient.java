package com.gameengine.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * Minimal NIO client that connects to server, receives keyframes, and sends INPUT lines.
 */
public class NioClient implements Runnable {
    private final String host;
    private final int port;
    private final NetworkBuffer buffer;
    private volatile boolean running = true;
    private volatile int ownId = -1;
    private final java.util.concurrent.ConcurrentHashMap<Integer, String> names = new java.util.concurrent.ConcurrentHashMap<>();

    public NioClient(String host, int port, NetworkBuffer buffer) {
        this.host = host; this.port = port; this.buffer = buffer;
    }

    private Selector selector;
    private SocketChannel channel;
    private final StringBuilder recv = new StringBuilder();

    public void stop() { running = false; if (selector != null) selector.wakeup(); }

    @Override public void run() {
        try {
            selector = Selector.open();
            channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.connect(new InetSocketAddress(host, port));
            channel.register(selector, SelectionKey.OP_CONNECT | SelectionKey.OP_READ);
            while (running) {
                selector.select(25);
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next(); it.remove();
                    if (!key.isValid()) continue;
                    if (key.isConnectable()) finishConnect();
                    if (key.isReadable()) read();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try { if (channel != null) channel.close(); } catch (IOException ignored) {}
            try { if (selector != null) selector.close(); } catch (IOException ignored) {}
        }
    }

    private void finishConnect() throws IOException {
        if (channel.isConnectionPending()) channel.finishConnect();
        channel.register(selector, SelectionKey.OP_READ);
        // Send HELLO with username if provided via system property or env
        String name = System.getenv("NETWORK_USERNAME");
        if (name == null || name.isEmpty()) {
            name = System.getProperty("network.username", "Player");
        }
        String hello = "HELLO:" + name + "\n";
        channel.write(ByteBuffer.wrap(hello.getBytes(StandardCharsets.UTF_8)));
    }

    private void read() throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(2048);
        int n = channel.read(buf);
        if (n <= 0) return;
        buf.flip();
        recv.append(StandardCharsets.UTF_8.decode(buf));
        int idx;
        while ((idx = recv.indexOf("\n")) >= 0) {
            String line = recv.substring(0, idx).trim();
            recv.delete(0, idx + 1);
            if (line.startsWith("{\"type\":\"kf\"")) {
                parseKF(line);
            } else if (line.startsWith("{\"type\":\"welcome\"")) {
                int idIdx = line.indexOf("\"id\":");
                if (idIdx > 0) {
                    try { ownId = Integer.parseInt(line.substring(idIdx + 5, line.indexOf('}', idIdx)).replaceAll("[^0-9]","")); } catch (Exception ignored) {}
                }
            }
        }
    }

    private void parseKF(String json) {
        // very lightweight parsing without external deps
        try {
            int tIdx = json.indexOf("\"t\":");
            int commaAfterT = json.indexOf(',', tIdx);
            String tStr = json.substring(tIdx + 4, commaAfterT).trim();
            double tSec = Double.parseDouble(tStr);
            long tMillis = (long)(tSec * 1000);

            int entsIdx = json.indexOf("\"entities\":");
            int arrStart = json.indexOf('[', entsIdx);
            int arrEnd = json.indexOf(']', arrStart);
            String arr = json.substring(arrStart + 1, arrEnd);
            String[] items = arr.isEmpty() ? new String[0] : arr.split("\\},\\{");
            java.util.Map<Integer, NetState.EntitySnapshot> map = new java.util.HashMap<>();
            for (String it : items) {
                String s = it.replace("{", "").replace("}", "");
                String[] kvs = s.split(",");
                int id = 0; float x = 0, y = 0; char kind = 'p'; int hp = 0; int owner = 0; String nm = null;
                for (String kv : kvs) {
                    String[] pair = kv.split(":");
                    if (pair.length < 2) continue;
                    String key = pair[0].replace("\"", "").trim();
                    String val = pair[1].trim().replace("\"","");
                    if ("id".equals(key)) id = Integer.parseInt(val);
                    else if ("x".equals(key)) x = Float.parseFloat(val);
                    else if ("y".equals(key)) y = Float.parseFloat(val);
                    else if ("k".equals(key)) kind = val.isEmpty() ? 'p' : val.charAt(0);
                    else if ("hp".equals(key)) hp = Integer.parseInt(val);
                    else if ("o".equals(key)) owner = Integer.parseInt(val);
                    else if ("nm".equals(key)) nm = val;
                }
                map.put(id, new NetState.EntitySnapshot(id, x, y, kind, hp, owner));
                if (nm != null) names.put(id, nm);
            }
            buffer.push(new NetworkBuffer.Keyframe(tMillis, map));
        } catch (Exception ignored) { }
    }

    public void sendInput(float vx, float vy, boolean fire, float ax, float ay) {
        if (channel == null || !channel.isConnected()) return;
        String line = "INPUT:" + vx + "," + vy + "," + (fire ? 1 : 0) + "," + ax + "," + ay + "\n";
        try {
            channel.write(ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException ignored) { }
    }

    public int getOwnId() { return ownId; }
    public String getName(int id) { return names.getOrDefault(id, ""); }
}
