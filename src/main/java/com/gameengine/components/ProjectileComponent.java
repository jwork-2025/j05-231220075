package com.gameengine.components;

import com.gameengine.core.Component;
import com.gameengine.core.GameObject;
import com.gameengine.math.Vector2;

/**
 * Simple projectile component that moves straight and has damage
 */
public class ProjectileComponent extends Component<ProjectileComponent> {
    private Vector2 velocity;
    private float lifeTime;
    private int damage;
    private float age;
    private GameObject shooter; // the original shooter to avoid friendly fire

    public ProjectileComponent(Vector2 velocity, float lifeTime, int damage, GameObject shooter) {
        this.velocity = new Vector2(velocity);
        this.lifeTime = lifeTime;
        this.damage = damage;
        this.age = 0f;
        this.shooter = shooter;
    }

    @Override
    public void initialize() {
    }

    @Override
    public void update(float deltaTime) {
        if (!enabled) return;
        age += deltaTime;
        TransformComponent transform = owner.getComponent(TransformComponent.class);
        if (transform != null) {
            transform.translate(velocity.multiply(deltaTime));
        }
        if (age >= lifeTime) {
            owner.destroy();
            return;
        }

        // remove if out of screen bounds (simple guard)
        if (transform != null) {
            Vector2 p = transform.getPosition();
            if (p.x < -50 || p.y < -50 || p.x > 850 || p.y > 650) {
                owner.destroy();
            }
        }
    }

    @Override
    public void render() {
        TransformComponent t = owner.getComponent(TransformComponent.class);
        RenderComponent r = owner.getComponent(RenderComponent.class);
        if (t != null && r != null && r.isVisible()) {
            // projectile render delegated to render component
        }
    }

    public int getDamage() {
        return damage;
    }

    public GameObject getShooter() {
        return shooter;
    }
}
