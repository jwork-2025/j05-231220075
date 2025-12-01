package com.gameengine.net;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class NetState {
    public static final char KIND_PLAYER = 'p';
    public static final char KIND_BULLET = 'b';

    public static class EntitySnapshot {
        public int id;
        public float x;
        public float y;
        public char kind; // 'p' player, 'b' bullet
        public int hp;    // for player
        public int owner; // for bullet
        public EntitySnapshot(int id, float x, float y, char kind, int hp, int owner) {
            this.id = id; this.x = x; this.y = y; this.kind = kind; this.hp = hp; this.owner = owner;
        }
    }

    private long serverTimeMillis;
    private final Map<Integer, EntitySnapshot> entities = new HashMap<>();

    public synchronized void setServerTimeMillis(long t) { this.serverTimeMillis = t; }
    public synchronized long getServerTimeMillis() { return serverTimeMillis; }

    public synchronized void setEntity(EntitySnapshot e) {
        entities.put(e.id, e);
    }

    public synchronized Map<Integer, EntitySnapshot> getEntities() {
        return Collections.unmodifiableMap(new HashMap<>(entities));
    }
}
