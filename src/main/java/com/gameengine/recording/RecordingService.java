package com.gameengine.recording;

import com.gameengine.components.TransformComponent;
import com.gameengine.core.GameObject;
import com.gameengine.input.InputManager;
import com.gameengine.scene.Scene;
import com.gameengine.components.PhysicsComponent;
import com.gameengine.components.RenderComponent;
import com.gameengine.components.ProjectileComponent;
import com.gameengine.math.Vector2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class RecordingService {
    private final RecordingConfig config;
    private final BlockingQueue<String> lineQueue;
    private volatile boolean recording;
    private Thread writerThread;
    private RecordingStorage storage = new FileRecordingStorage();
    private double elapsed;
    private double keyframeElapsed;
    private double sampleAccumulator;
    private final double warmupSec = 0.1; // 等待一帧让场景对象完成初始化
    private final DecimalFormat qfmt;
    private Scene lastScene;
    // 上一帧看到的实体 id 集合（用于检测 spawn / despawn）
    private final Set<String> prevEntityIds = new HashSet<>();

    public RecordingService(RecordingConfig config) {
        this.config = config;
        this.lineQueue = new ArrayBlockingQueue<>(config.queueCapacity);
        this.recording = false;
        this.elapsed = 0.0;
        this.keyframeElapsed = 0.0;
        this.sampleAccumulator = 0.0;
        this.qfmt = new DecimalFormat();
        this.qfmt.setMaximumFractionDigits(Math.max(0, config.quantizeDecimals));
        this.qfmt.setGroupingUsed(false);
    }

    public boolean isRecording() {
        return recording;
    }

    public void start(Scene scene, int width, int height) throws IOException {
        if (recording) return;
        // ensure recordings directory exists and cleanup old recordings before opening writer
        try {
            Path p = Paths.get(config.outputPath);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
        } catch (Exception ignored) {}
        try { storage.cleanupOldRecordings(config.maxRecordFiles); } catch (Exception ignored) {}
        storage.openWriter(config.outputPath);
        writerThread = new Thread(() -> {
            try {
                while (recording || !lineQueue.isEmpty()) {
                    String s = lineQueue.poll();
                    if (s == null) {
                        try { Thread.sleep(2); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    storage.writeLine(s);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try { storage.closeWriter(); } catch (Exception ignored) {}
            }
        }, "record-writer");
        recording = true;
        writerThread.start();

        // header
        enqueue("{\"type\":\"header\",\"version\":1,\"w\":" + width + ",\"h\":" + height + "}");
        keyframeElapsed = 0.0;
    }

    public void stop() {
        if (!recording) return;
        try {
            if (lastScene != null) {
                writeKeyframe(lastScene);
            }
        } catch (Exception ignored) {}
        recording = false;
        try { writerThread.join(500); } catch (InterruptedException ignored) {}
    }

    public void update(double deltaTime, Scene scene, InputManager input) {
        if (!recording) return;
        elapsed += deltaTime;
        keyframeElapsed += deltaTime;
        sampleAccumulator += deltaTime;
        lastScene = scene;

        // input events (sample at native frequency, 但只写有justPressed)
        Set<Integer> just = input.getJustPressedKeysSnapshot();
        if (!just.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"type\":\"input\",\"t\":").append(qfmt.format(elapsed)).append(",\"keys\":[");
            boolean first = true;
            for (Integer k : just) {
                if (!first) sb.append(',');
                sb.append(k);
                first = false;
            }
            sb.append("]}");
            enqueue(sb.toString());
        }

        // detect spawn / despawn by comparing entity ids
        try {
            List<GameObject> objs = scene.getGameObjects();
            Set<String> current = new HashSet<>();
            for (GameObject o : objs) current.add(o.getId());
            // spawn: in current not in prev
            for (String id : current) {
                if (!prevEntityIds.contains(id)) {
                    // find the object and write spawn event
                    for (GameObject o : objs) {
                        if (o.getId().equals(id)) {
                            writeSpawn(o);
                            break;
                        }
                    }
                }
            }
            // despawn: in prev not in current
            for (String id : new HashSet<>(prevEntityIds)) {
                if (!current.contains(id)) {
                    writeDespawn(id);
                }
            }
            prevEntityIds.clear();
            prevEntityIds.addAll(current);
        } catch (Exception ignored) {}

        // frequent sampling for smooth movements (子弹等)
        if (sampleAccumulator >= config.sampleIntervalSec) {
            try {
                writeSample(scene);
            } catch (Exception ignored) {}
            sampleAccumulator = 0.0;
        }

        if (elapsed >= warmupSec && keyframeElapsed >= config.keyframeIntervalSec) {
            if (writeKeyframe(scene)) {
                keyframeElapsed = 0.0;
            }
        }
    }

    private boolean writeKeyframe(Scene scene) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"keyframe\",\"t\":").append(qfmt.format(elapsed)).append(",\"entities\":[");
        List<GameObject> objs = scene.getGameObjects();
        boolean first = true;
        int count = 0;
        for (GameObject obj : objs) {
            TransformComponent tc = obj.getComponent(TransformComponent.class);
            if (tc == null) continue;
            float x = tc.getPosition().x;
            float y = tc.getPosition().y;
            if (!first) sb.append(',');
                        sb.append('{')
                            .append("\"id\":\"").append(obj.getId()).append("\",")
                            .append("\"name\":\"").append(obj.getName()).append("\",")
              .append("\"x\":").append(qfmt.format(x)).append(',')
              .append("\"y\":").append(qfmt.format(y));

            // If this object has a projectile component, record its shooter info
            ProjectileComponent pcomp = obj.getComponent(ProjectileComponent.class);
            if (pcomp != null && pcomp.getShooter() != null) {
                sb.append(',').append("\"ownerId\":\"").append(pcomp.getShooter().getId()).append("\"");
                sb.append(',').append("\"ownerName\":\"").append(pcomp.getShooter().getName()).append("\"");
            }

            com.gameengine.components.RenderComponent rc = obj.getComponent(com.gameengine.components.RenderComponent.class);
            if (rc != null) {
                com.gameengine.components.RenderComponent.RenderType rt = rc.getRenderType();
                com.gameengine.math.Vector2 sz = rc.getSize();
                com.gameengine.components.RenderComponent.Color col = rc.getColor();
                                sb.append(',')
                                    .append("\"rt\":\"").append(rt.name()).append("\",")
                  .append("\"w\":").append(qfmt.format(sz.x)).append(',')
                  .append("\"h\":").append(qfmt.format(sz.y)).append(',')
                  .append("\"color\":[")
                  .append(qfmt.format(col.r)).append(',')
                  .append(qfmt.format(col.g)).append(',')
                  .append(qfmt.format(col.b)).append(',')
                  .append(qfmt.format(col.a)).append(']');
            } else {
                sb.append(',').append("\"rt\":\"CUSTOM\"");
            }

                        // velocity / rotation
                        PhysicsComponent ph = obj.getComponent(PhysicsComponent.class);
                        if (ph != null) {
                                Vector2 v = ph.getVelocity();
                                sb.append(',').append("\"vx\":").append(qfmt.format(v.x)).append(',')
                                    .append("\"vy\":").append(qfmt.format(v.y));
                        }
            if (rc != null) {
                sb.append(',').append("\"rot\":").append(qfmt.format(rc.getRotation()));
            }

            sb.append('}');
            first = false;
            count++;
        }
        sb.append("]}");
        if (count == 0) return false;
        enqueue(sb.toString());
        return true;
    }

    private void enqueue(String line) {
        if (!lineQueue.offer(line)) {
            // drop on full
        }
    }

    private void writeSample(Scene scene) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"sample\",\"t\":").append(qfmt.format(elapsed)).append(",\"entities\":[");
        List<GameObject> objs = scene.getGameObjects();
        boolean first = true;
        int count = 0;
        for (GameObject obj : objs) {
            TransformComponent tc = obj.getComponent(TransformComponent.class);
            if (tc == null) continue;
            float x = tc.getPosition().x;
            float y = tc.getPosition().y;
            if (!first) sb.append(',');
                        sb.append('{')
                            .append("\"id\":\"").append(obj.getId()).append("\",")
                            .append("\"name\":\"").append(obj.getName()).append("\",")
              .append("\"x\":").append(qfmt.format(x)).append(',')
              .append("\"y\":").append(qfmt.format(y));

            // If this object has a projectile component, record its shooter info
            ProjectileComponent pcomp2 = obj.getComponent(ProjectileComponent.class);
            if (pcomp2 != null && pcomp2.getShooter() != null) {
                sb.append(',').append("\"ownerId\":\"").append(pcomp2.getShooter().getId()).append("\"");
                sb.append(',').append("\"ownerName\":\"").append(pcomp2.getShooter().getName()).append("\"");
            }

            RenderComponent rc = obj.getComponent(RenderComponent.class);
            if (rc != null) {
                RenderComponent.RenderType rt = rc.getRenderType();
                Vector2 sz = rc.getSize();
                RenderComponent.Color col = rc.getColor();
                sb.append(',')
                  .append("\"rt\":\"").append(rt.name()).append("\",")
                  .append("\"w\":").append(qfmt.format(sz.x)).append(',')
                  .append("\"h\":").append(qfmt.format(sz.y)).append(',')
                  .append("\"color\":[")
                  .append(qfmt.format(col.r)).append(',')
                  .append(qfmt.format(col.g)).append(',')
                  .append(qfmt.format(col.b)).append(',')
                  .append(qfmt.format(col.a)).append(']');
            } else {
                sb.append(',').append("\"rt\":\"CUSTOM\"");
            }

            PhysicsComponent ph = obj.getComponent(PhysicsComponent.class);
            if (ph != null) {
                Vector2 v = ph.getVelocity();
                sb.append(',').append("\"vx\":").append(qfmt.format(v.x)).append(',')
                  .append("\"vy\":").append(qfmt.format(v.y));
            }
            if (rc != null) {
                sb.append(',').append("\"rot\":").append(qfmt.format(rc.getRotation()));
            }

            sb.append('}');
            first = false;
            count++;
        }
        sb.append("]}");
        if (count > 0) enqueue(sb.toString());
    }

    private void writeSpawn(GameObject obj) {
        try {
            TransformComponent tc = obj.getComponent(TransformComponent.class);
            if (tc == null) return;
            StringBuilder sb = new StringBuilder();
            sb.append("{\"type\":\"spawn\",\"t\":").append(qfmt.format(elapsed)).append(",");
            sb.append("\"id\":\"").append(obj.getId()).append("\",");
            sb.append("\"name\":\"").append(obj.getName()).append("\",");
            sb.append("\"x\":").append(qfmt.format(tc.getPosition().x)).append(',');
            sb.append("\"y\":").append(qfmt.format(tc.getPosition().y));

            // projectile owner info at spawn if applicable
            ProjectileComponent spawnP = obj.getComponent(ProjectileComponent.class);
            if (spawnP != null && spawnP.getShooter() != null) {
                sb.append(',').append("\"ownerId\":\"").append(spawnP.getShooter().getId()).append("\"");
                sb.append(',').append("\"ownerName\":\"").append(spawnP.getShooter().getName()).append("\"");
            }
            RenderComponent rc = obj.getComponent(RenderComponent.class);
            if (rc != null) {
                Vector2 sz = rc.getSize();
                RenderComponent.RenderType rt = rc.getRenderType();
                RenderComponent.Color col = rc.getColor();
                sb.append(',').append("\"rt\":\"").append(rt.name()).append("\"");
                sb.append(',').append("\"w\":").append(qfmt.format(sz.x)).append(',').append("\"h\":").append(qfmt.format(sz.y));
                sb.append(',').append("\"color\":[")
                  .append(qfmt.format(col.r)).append(',').append(qfmt.format(col.g)).append(',')
                  .append(qfmt.format(col.b)).append(',').append(qfmt.format(col.a)).append(']');
                sb.append(',').append("\"rot\":").append(qfmt.format(rc.getRotation()));
            }
            PhysicsComponent ph = obj.getComponent(PhysicsComponent.class);
            if (ph != null) {
                Vector2 v = ph.getVelocity();
                sb.append(',').append("\"vx\":").append(qfmt.format(v.x)).append(',')
                  .append("\"vy\":").append(qfmt.format(v.y));
            }
            sb.append('}');
            enqueue(sb.toString());
        } catch (Exception ignored) {}
    }

    private void writeDespawn(String id) {
        enqueue("{\"type\":\"despawn\",\"t\":" + qfmt.format(elapsed) + ",\"id\":\"" + id + "\"}");
    }
}
