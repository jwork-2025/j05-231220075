package com.gameengine.components;

import com.gameengine.core.Component;
import com.gameengine.math.Vector2;

/**
 * ShooterComponent gives an object ability to shoot with independent cooldown.
 */
public class ShooterComponent extends Component<ShooterComponent> {
    private float cooldown; // seconds between shots
    private float timer;
    private int damage;
    private float speed;

    public ShooterComponent(float cooldown, float speed, int damage) {
        this.cooldown = cooldown;
        this.timer = 0f;
        this.speed = speed;
        this.damage = damage;
    }

    @Override
    public void initialize() {
    }

    @Override
    public void update(float deltaTime) {
        if (!enabled) return;
        timer -= deltaTime;
    }

    @Override
    public void render() {
    }

    public boolean canShoot() {
        return timer <= 0f;
    }

    public void resetCooldown() {
        this.timer = cooldown;
    }

    // Getters
    public float getSpeed() { return speed; }
    public int getDamage() { return damage; }
}
