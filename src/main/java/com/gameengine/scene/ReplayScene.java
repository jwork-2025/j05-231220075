package com.gameengine.scene;

import org.lwjgl.glfw.GLFW;

import com.gameengine.components.TransformComponent;
import com.gameengine.core.GameEngine;
import com.gameengine.core.GameObject;
import com.gameengine.graphics.IRenderer;
import com.gameengine.input.InputManager;
import com.gameengine.math.Vector2;
import com.gameengine.example.EntityFactory;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * ReplayScene - improved replay handling
 */
public class ReplayScene extends Scene {
    private final GameEngine engine;
    private String recordingPath;
    private IRenderer renderer;
    private InputManager input;
    private float time;

    private static class Keyframe {
        static class EntityInfo {
            Vector2 pos;
            String rt;
            float w, h;
            float r = 0.9f, g = 0.9f, b = 0.2f, a = 1.0f;
            String id;
            String name;
            String ownerId;
            String ownerName;
        }
    }

    private static class EntityInfoWithTime {
        double t;
        Keyframe.EntityInfo info;
        EntityInfoWithTime(double t, Keyframe.EntityInfo info) { this.t = t; this.info = info; }
    }

    private final Map<String, List<EntityInfoWithTime>> entityTimelines = new HashMap<>();
    private final Map<String, GameObject> idToObject = new HashMap<>();
    private final Map<String, Float> alphaMap = new HashMap<>();

    private List<File> recordingFiles;
    private int selectedIndex = 0;

    public ReplayScene(GameEngine engine, String path) {
        super("Replay");
        this.engine = engine;
        this.recordingPath = path;
        this.time = 0f;
    }

    @Override
    public void initialize() {
        super.initialize();
        this.renderer = engine.getRenderer();
        this.input = engine.getInputManager();
        this.time = 0f;
        this.entityTimelines.clear();
        this.idToObject.clear();
        this.alphaMap.clear();
        this.recordingFiles = null;
        this.selectedIndex = 0;
        if (recordingPath != null) {
            loadRecording(recordingPath);
            buildObjectsFromTimelines();
        }
    }

    @Override
    public void update(float deltaTime) {
        super.update(deltaTime);
        if (input.isKeyJustPressed(GLFW.GLFW_KEY_M) || input.isKeyJustPressed('M') || input.isKeyJustPressed(77)) {
            if (recordingPath != null) {
                recordingPath = null;
                this.entityTimelines.clear();
                this.idToObject.clear();
                this.alphaMap.clear();
                this.recordingFiles = null;
                this.selectedIndex = 0;
                clear();
                return;
            } else {
                engine.setScene(new MenuScene(engine, "MainMenu"));
                return;
            }
        }

        if (recordingPath == null) {
            handleFileSelection();
            return;
        }

        if (entityTimelines.isEmpty()) return;

        time += deltaTime;
        double lastT = 0.0;
        for (List<EntityInfoWithTime> list : entityTimelines.values()) {
            if (!list.isEmpty()) lastT = Math.max(lastT, list.get(list.size()-1).t);
        }
        if (time > lastT) time = (float) lastT;

        for (Map.Entry<String, List<EntityInfoWithTime>> e : entityTimelines.entrySet()) {
            String id = e.getKey();
            List<EntityInfoWithTime> timeline = e.getValue();
            if (timeline == null || timeline.isEmpty()) continue;
            EntityInfoWithTime a = null, b = null;
            for (int i = 0; i < timeline.size(); i++) {
                EntityInfoWithTime cur = timeline.get(i);
                if (cur.t <= time) a = cur;
                if (cur.t >= time) { b = cur; break; }
            }
            if (a == null && b == null) continue;
            Keyframe.EntityInfo infoA = (a != null) ? a.info : b.info;
            Keyframe.EntityInfo infoB = (b != null) ? b.info : a.info;
            double ta = (a != null) ? a.t : b.t;
            double tb = (b != null) ? b.t : a.t;
            double span = Math.max(1e-6, tb - ta);
            double u = (span < 1e-6) ? 0.0 : ((time - ta) / span);
            float x = (float) ((1.0 - u) * infoA.pos.x + u * infoB.pos.x);
            float y = (float) ((1.0 - u) * infoA.pos.y + u * infoB.pos.y);

            GameObject obj = idToObject.get(id);
            if (obj != null) {
                TransformComponent tc = obj.getComponent(TransformComponent.class);
                if (tc != null) tc.setPosition(new Vector2(x, y));
                com.gameengine.components.RenderComponent rc = obj.getComponent(com.gameengine.components.RenderComponent.class);
                if (rc != null) {
                    float targetAlpha = (time >= timeline.get(0).t && time <= timeline.get(timeline.size()-1).t) ? 1f : 0f;
                    float cur = alphaMap.getOrDefault(id, 0f);
                    float step = Math.min(1f, deltaTime * 5f);
                    cur = cur + (targetAlpha - cur) * step;
                    alphaMap.put(id, cur);
                    rc.setColor(rc.getColor().r, rc.getColor().g, rc.getColor().b, cur);
                }
            }
        }
    }

    @Override
    public void render() {
        renderer.drawRect(0, 0, renderer.getWidth(), renderer.getHeight(), 0.06f, 0.06f, 0.08f, 1.0f);
        if (recordingPath == null) {
            renderFileList();
            return;
        }
        super.render();
        String hint = "REPLAY: M to return";
        float w = hint.length() * 12.0f;
        renderer.drawText(hint, (int)(renderer.getWidth()/2.0f - w/2.0f), 30, new java.awt.Color(200,200,200));
    }

    private void loadRecording(String path) {
        entityTimelines.clear();
        com.gameengine.recording.RecordingStorage storage = new com.gameengine.recording.FileRecordingStorage();
        try {
            for (String line : storage.readLines(path)) {
                if (line.contains("\"type\":\"sample\"") || line.contains("\"type\":\"keyframe\"")) {
                    double t = com.gameengine.recording.RecordingJson.parseDouble(com.gameengine.recording.RecordingJson.field(line, "t"));
                    int idx = line.indexOf("\"entities\":[");
                    if (idx >= 0) {
                        int bracket = line.indexOf('[', idx);
                        String arr = bracket >= 0 ? com.gameengine.recording.RecordingJson.extractArray(line, bracket) : "";
                        String[] parts = com.gameengine.recording.RecordingJson.splitTopLevel(arr);
                        for (String p : parts) {
                            Keyframe.EntityInfo ei = new Keyframe.EntityInfo();
                            ei.id = com.gameengine.recording.RecordingJson.stripQuotes(com.gameengine.recording.RecordingJson.field(p, "id"));
                            ei.name = com.gameengine.recording.RecordingJson.stripQuotes(com.gameengine.recording.RecordingJson.field(p, "name"));
                            ei.ownerId = com.gameengine.recording.RecordingJson.stripQuotes(com.gameengine.recording.RecordingJson.field(p, "ownerId"));
                            ei.ownerName = com.gameengine.recording.RecordingJson.stripQuotes(com.gameengine.recording.RecordingJson.field(p, "ownerName"));
                            double x = com.gameengine.recording.RecordingJson.parseDouble(com.gameengine.recording.RecordingJson.field(p, "x"));
                            double y = com.gameengine.recording.RecordingJson.parseDouble(com.gameengine.recording.RecordingJson.field(p, "y"));
                            ei.pos = new Vector2((float)x, (float)y);
                            String rt = com.gameengine.recording.RecordingJson.stripQuotes(com.gameengine.recording.RecordingJson.field(p, "rt"));
                            ei.rt = rt;
                            ei.w = (float)com.gameengine.recording.RecordingJson.parseDouble(com.gameengine.recording.RecordingJson.field(p, "w"));
                            ei.h = (float)com.gameengine.recording.RecordingJson.parseDouble(com.gameengine.recording.RecordingJson.field(p, "h"));
                            String colorArr = com.gameengine.recording.RecordingJson.field(p, "color");
                            if (colorArr != null && colorArr.startsWith("[")) {
                                String c = colorArr.substring(1, Math.max(1, colorArr.indexOf(']', 1)));
                                String[] cs = c.split(",");
                                if (cs.length >= 3) {
                                    try {
                                        ei.r = Float.parseFloat(cs[0].trim());
                                        ei.g = Float.parseFloat(cs[1].trim());
                                        ei.b = Float.parseFloat(cs[2].trim());
                                        if (cs.length >= 4) ei.a = Float.parseFloat(cs[3].trim());
                                    } catch (Exception ignored) {}
                                }
                            }
                            if (ei.id == null) continue;
                            entityTimelines.computeIfAbsent(ei.id, k -> new ArrayList<>()).add(new EntityInfoWithTime(t, ei));
                        }
                    }
                }
            }
            for (List<EntityInfoWithTime> list : entityTimelines.values()) list.sort(Comparator.comparingDouble(x -> x.t));
        } catch (Exception e) {
            // ignore parse errors
        }
    }

    private void buildObjectsFromTimelines() {
        clear();
        idToObject.clear();
        alphaMap.clear();
        for (String id : entityTimelines.keySet()) {
            List<EntityInfoWithTime> list = entityTimelines.get(id);
            if (list == null || list.isEmpty()) continue;
            Keyframe.EntityInfo ei = list.get(0).info;
            GameObject obj = buildObjectFromEntity(ei, 0);
            obj.setName(id);
            addGameObject(obj);
            idToObject.put(id, obj);
            alphaMap.put(id, 0f);
        }
        time = 0f;
    }

    private GameObject buildObjectFromEntity(Keyframe.EntityInfo ei, int index) {
        GameObject obj;
        String lowered = (ei.name != null ? ei.name : "").toLowerCase();
        // Determine visual by name first, then fallback to id-based heuristics
        if (lowered.contains("player") || lowered.contains("you") || "player".equalsIgnoreCase(ei.id)) {
            obj = EntityFactory.createPlayerVisual(renderer);
        } else if (lowered.contains("bullet") || lowered.contains("proj") || lowered.contains("shot") || lowered.contains("missile")) {
            // create small projectile visual; color depends on owner (player vs enemy)
            GameObject tmp = new GameObject(ei.name != null ? ei.name : ("Proj#" + index));
            tmp.addComponent(new TransformComponent(new Vector2(0,0)));
            // choose color: player bullets -> cyan/blue, enemy bullets -> red/yellow
            float cr = 1.0f, cg = 0.25f, cb = 0.25f, ca = ei.a;
            String ownerLower = "";
            if (ei.ownerName != null && !ei.ownerName.isEmpty()) ownerLower = ei.ownerName.toLowerCase();
            else if (ei.ownerId != null && idToObject.containsKey(ei.ownerId)) {
                GameObject ownerObj = idToObject.get(ei.ownerId);
                if (ownerObj != null && ownerObj.getName() != null) ownerLower = ownerObj.getName().toLowerCase();
            }
            if (ownerLower.contains("player") || ownerLower.contains("you")) {
                cr = 0.25f; cg = 0.9f; cb = 1.0f; // cyan-ish for player
            } else {
                cr = 1.0f; cg = 0.25f; cb = 0.25f; // red for enemy
            }
            com.gameengine.components.RenderComponent rc = tmp.addComponent(
                new com.gameengine.components.RenderComponent(
                    com.gameengine.components.RenderComponent.RenderType.RECTANGLE,
                    new Vector2(Math.max(2, ei.w>0?ei.w:6), Math.max(2, ei.h>0?ei.h:6)),
                    new com.gameengine.components.RenderComponent.Color(cr, cg, cb, ca)
                )
            );
            rc.setRenderer(renderer);
            obj = tmp;
        } else if ("AIPlayer".equalsIgnoreCase(ei.id) || lowered.contains("enemy") || lowered.contains("hulu") || lowered.contains("creature")) {
            float w2 = (ei.w > 0 ? ei.w : 20);
            float h2 = (ei.h > 0 ? ei.h : 20);
            // enemy visual: use AI visual but prefer recorded color if present
            obj = EntityFactory.createAIVisual(renderer, w2, h2, ei.r, ei.g, ei.b, ei.a);
        } else {
            if ("CIRCLE".equals(ei.rt)) {
                GameObject tmp = new GameObject(ei.id == null ? ("Obj#"+index) : ei.id);
                tmp.addComponent(new TransformComponent(new Vector2(0,0)));
                com.gameengine.components.RenderComponent rc = tmp.addComponent(
                    new com.gameengine.components.RenderComponent(
                        com.gameengine.components.RenderComponent.RenderType.CIRCLE,
                        new Vector2(Math.max(1, ei.w), Math.max(1, ei.h)),
                        new com.gameengine.components.RenderComponent.Color(ei.r, ei.g, ei.b, ei.a)
                    )
                );
                rc.setRenderer(renderer);
                obj = tmp;
            } else {
                obj = EntityFactory.createAIVisual(renderer, Math.max(1, ei.w>0?ei.w:10), Math.max(1, ei.h>0?ei.h:10), ei.r, ei.g, ei.b, ei.a);
            }
            obj.setName(ei.id == null ? ("Obj#"+index) : ei.id);
        }
        TransformComponent tc = obj.getComponent(TransformComponent.class);
        if (tc == null) obj.addComponent(new TransformComponent(new Vector2(ei.pos)));
        else tc.setPosition(new Vector2(ei.pos));
        return obj;
    }

    private void ensureFilesListed() {
        if (recordingFiles != null) return;
        com.gameengine.recording.RecordingStorage storage = new com.gameengine.recording.FileRecordingStorage();
        recordingFiles = storage.listRecordings();
    }

    private void handleFileSelection() {
        ensureFilesListed();
        if (input.isKeyJustPressed(38) || input.isKeyJustPressed(265)) { // up
            selectedIndex = (selectedIndex - 1 + Math.max(1, recordingFiles.size())) % Math.max(1, recordingFiles.size());
        } else if (input.isKeyJustPressed(40) || input.isKeyJustPressed(264)) { // down
            selectedIndex = (selectedIndex + 1) % Math.max(1, recordingFiles.size());
        } else if (input.isKeyJustPressed(10) || input.isKeyJustPressed(32) || input.isKeyJustPressed(257) || input.isKeyJustPressed(335)) {
            if (recordingFiles.size() > 0) {
                String path = recordingFiles.get(selectedIndex).getAbsolutePath();
                this.recordingPath = path;
                clear();
                initialize();
            }
        } else if (input.isKeyJustPressed(GLFW.GLFW_KEY_M) || input.isKeyJustPressed('M') || input.isKeyJustPressed(77)) {
            engine.setScene(new MenuScene(engine, "MainMenu"));
        }
    }

    private void renderFileList() {
        ensureFilesListed();
        int w = renderer.getWidth();
        int h = renderer.getHeight();
        String title = "SELECT RECORDING";
        float tw = title.length() * 16f;
        renderer.drawText(title, (int)(w/2f - tw/2f), 80, new java.awt.Color(255,255,255));

        if (recordingFiles.isEmpty()) {
            String none = "NO RECORDINGS FOUND";
            float nw = none.length() * 14f;
            renderer.drawText(none, (int)(w/2f - nw/2f), (int)(h/2f), new java.awt.Color(230,200,50));
            String back = "ESC TO RETURN";
            float bw = back.length() * 12f;
            renderer.drawText(back, (int)(w/2f - bw/2f), h - 60, new java.awt.Color(180,180,180));
            return;
        }

        float startY = 140f;
        float itemH = 28f;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (int i = 0; i < recordingFiles.size(); i++) {
            File f = recordingFiles.get(i);
            String name = f.getName();
            String display = name;
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{10,})").matcher(name);
                if (m.find()) {
                    long ms = Long.parseLong(m.group(1));
                    if (String.valueOf(ms).length() == 10) ms = ms * 1000L;
                    display = sdf.format(new java.util.Date(ms));
                } else {
                    display = sdf.format(new java.util.Date(f.lastModified()));
                }
            } catch (Exception ignored) {
                display = name;
            }

            float x = 100f;
            float y = startY + i * itemH;
            if (i == selectedIndex) {
                renderer.drawRect(x - 10, y - 6, 600, 24, 0.3f,0.3f,0.4f,0.8f);
            }
            renderer.drawText(display, (int)x, (int)y, new java.awt.Color(230,230,230));
        }

        String hint = "UP/DOWN SELECT, ENTER PLAY, M RETURN";
        float hw = hint.length() * 12f;
        renderer.drawText(hint, (int)(w/2f - hw/2f), h - 60, new java.awt.Color(180,180,180));
    }

}
