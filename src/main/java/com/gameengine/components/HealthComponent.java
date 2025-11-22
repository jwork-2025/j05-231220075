package com.gameengine.components;

import com.gameengine.core.Component;

/**
 * Health component to track HP and handle damage
 */
public class HealthComponent extends Component<HealthComponent> {
    private int maxHp;
    private int hp;

    public HealthComponent(int maxHp) {
        this.maxHp = Math.max(1, maxHp);
        this.hp = this.maxHp;
    }

    @Override
    public void initialize() {
        // nothing
    }

    @Override
    public void update(float deltaTime) {
        // nothing per-frame here
    }

    @Override
    public void render() {
        // not rendered; UI will show HP
    }

    public void applyDamage(int dmg) {
        this.hp -= dmg;
        if (this.hp < 0) this.hp = 0;
    }

    public boolean isDead() {
        return this.hp <= 0;
    }

    public int getHp() {
        return hp;
    }

    public int getMaxHp() {
        return maxHp;
    }
}
