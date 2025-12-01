package com.gameengine.net;

import java.util.*;

/**
 * Client-side buffer for server keyframes with simple linear interpolation.
 */
public class NetworkBuffer {
    public static class Keyframe {
        public long tMillis;
        public Map<Integer, NetState.EntitySnapshot> entities; // keyed by id
        public Keyframe(long tMillis, Map<Integer, NetState.EntitySnapshot> entities) {
            this.tMillis = tMillis; this.entities = entities;
        }
    }

    private final Deque<Keyframe> frames = new ArrayDeque<>();
    private int delayMs = 120;

    public synchronized void setDelayMs(int delayMs) { this.delayMs = delayMs; }

    public synchronized void push(Keyframe kf) {
        frames.addLast(kf);
        while (frames.size() > 120) frames.removeFirst();
    }

    /**
     * Interpolated sample keyed by id. Linear on x/y if both frames present.
     */
    public synchronized Map<Integer, NetState.EntitySnapshot> sampleMap(long nowMillis) {
        long target = nowMillis - delayMs;
        Keyframe a = null, b = null;
        for (Keyframe f : frames) {
            if (f.tMillis <= target) a = f; else { b = f; break; }
        }
        if (a == null) a = frames.peekFirst();
        if (b == null) b = frames.peekLast();
        if (a == null) return Collections.emptyMap();
        if (b == null || a == b) return new HashMap<>(a.entities);
        long dt = b.tMillis - a.tMillis;
        float alpha = dt <= 0 ? 1f : (float)(target - a.tMillis) / (float)dt;
        Map<Integer, NetState.EntitySnapshot> result = new HashMap<>();
        // Interpolate common ids; take from nearer frame for others
        for (Map.Entry<Integer, NetState.EntitySnapshot> e : a.entities.entrySet()) {
            int id = e.getKey();
            NetState.EntitySnapshot ea = e.getValue();
            NetState.EntitySnapshot eb = b.entities.get(id);
            if (eb != null) {
                float x = ea.x + (eb.x - ea.x) * alpha;
                float y = ea.y + (eb.y - ea.y) * alpha;
                result.put(id, new NetState.EntitySnapshot(id, x, y, ea.kind, ea.hp, ea.owner));
            } else {
                result.put(id, ea);
            }
        }
        for (Map.Entry<Integer, NetState.EntitySnapshot> e : b.entities.entrySet()) {
            int id = e.getKey();
            if (!result.containsKey(id)) result.put(id, e.getValue());
        }
        return result;
    }
}
