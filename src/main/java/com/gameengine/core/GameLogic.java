package com.gameengine.core;

import com.gameengine.components.TransformComponent;
import com.gameengine.components.PhysicsComponent;
import com.gameengine.components.HealthComponent;
import com.gameengine.components.ProjectileComponent;
import com.gameengine.components.RenderComponent;
import com.gameengine.graphics.IRenderer;
import com.gameengine.core.GameObject;
import com.gameengine.input.InputManager;
import com.gameengine.math.Vector2;
import com.gameengine.scene.Scene;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 游戏逻辑类，处理具体的游戏规则
 */
public class GameLogic {
    private Scene scene;
    private InputManager inputManager;
    private IRenderer renderer;
    // 并行控制（线程池与开关）
    private ExecutorService executor = null;
    private boolean parallelEnabled = false;
    private int parallelThreads = 0;
    // 采样与日志
    private int frameCounter = 0;
    private int sampleInterval = 200; // 每隔 N 帧打印一次采样信息
    private float playerShootCooldown = 0f;
    private float enemyShootCooldown = 0f;
    private float playerHitCooldown = 0f; // to avoid HP dropping every frame during contact
    private boolean gameOver = false;
    private float survivalTime = 0f;
    // enemy spawn control
    private float enemySpawnTimer = 0f;
    private float enemySpawnInterval = 5.0f; // initial seconds between spawns
    private java.util.Random spawnRandom = new java.util.Random();
    // 并行检测阶段使用的碰撞动作容器（compute 阶段生产，apply 阶段主线程消费）
    private static class CollisionAction {
        public final GameObject projectile;
        public final GameObject target;
        public final int damage;
        public CollisionAction(GameObject p, GameObject t, int d) { projectile = p; target = t; damage = d; }
    }
    
    public GameLogic(Scene scene, IRenderer renderer) {
        this.scene = scene;
        this.inputManager = InputManager.getInstance();
        this.renderer = renderer;
    // 读取环境变量以决定是否启用并行执行
        try {
            String env = System.getenv("GAME_PARALLEL");
            if (env != null && (env.equalsIgnoreCase("true") || env.equals("1"))) {
                parallelEnabled = true;
                parallelThreads = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
                String t = System.getenv("GAME_PARALLEL_THREADS");
                if (t != null) {
                    try { parallelThreads = Integer.parseInt(t); } catch (NumberFormatException ignored) {}
                    parallelThreads = Math.max(1, parallelThreads);
                }
                executor = Executors.newFixedThreadPool(parallelThreads);
                System.out.println("[GameLogic] Parallel enabled: threads=" + parallelThreads);
            }
        } catch (SecurityException se) {
            parallelEnabled = false;
        }
    }

    public void shutdown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) executor.shutdownNow();
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 每帧更新，传入 deltaTime（秒）
     */
    public void update(float deltaTime) {
        if (gameOver) {
            return;
        }

        // update survival time
        survivalTime += deltaTime;

        // decrease player shoot cooldown
        playerShootCooldown -= deltaTime;

        // handle input-driven movement
        handlePlayerInput();
        // shooting and cooldowns
        handleShooting();
        // update physics components
        updatePhysics();
        // check projectile collisions
        checkCollisions();
        // check player-obstacle collision using deltaTime for cooldown
        checkPlayerObstacleCollision(deltaTime);

    // spawn enemies dynamically over time
    handleSpawning(deltaTime);

        // detect player death
        List<GameObject> players = scene.findGameObjectsByComponent(HealthComponent.class);
        if (players.isEmpty()) {
            gameOver = true;
        } else {
            GameObject player = players.get(0);
            HealthComponent hc = player.getComponent(HealthComponent.class);
            if (hc == null || hc.isDead()) {
                gameOver = true;
            }
        }
    }

    /**
     * Spawn enemies over time to increase difficulty.
     */
    private void handleSpawning(float deltaTime) {
        // spawn rate increases with survival time
        enemySpawnTimer -= deltaTime;
        // dynamically adjust interval: decrease slowly as time increases
        enemySpawnInterval = Math.max(0.5f, 5.0f - (survivalTime / 30.0f));
        if (enemySpawnTimer <= 0f) {
            enemySpawnTimer = enemySpawnInterval;
            // create a new enemy GameObject and insert into scene
            try {
                GameObject enemy = new GameObject("Enemy") {
                    @Override
                    public void update(float dt) { super.update(dt); updateComponents(dt); }
                    @Override
                    public void render() { renderComponents(); }
                };
                float w = 800f, h = 600f;
                // try to use renderer dimensions if available
                if (renderer != null) { w = renderer.getWidth(); h = renderer.getHeight(); }
                Vector2 position = new Vector2(spawnRandom.nextFloat() * w, spawnRandom.nextFloat() * h);
                enemy.addComponent(new TransformComponent(position));
                RenderComponent rc = enemy.addComponent(new RenderComponent(
                    RenderComponent.RenderType.RECTANGLE,
                    new Vector2(20,20),
                    new RenderComponent.Color(1f,0.45f,0.1f,1f)
                ));
                if (this.renderer != null) rc.setRenderer(this.renderer);
                PhysicsComponent p = enemy.addComponent(new PhysicsComponent(0.5f));
                p.setVelocity(new Vector2(0,0));
                p.setFriction(0.98f);
                enemy.addComponent(new HealthComponent(10));
                // give shooter capability so enemies can fire
                enemy.addComponent(new com.gameengine.components.ShooterComponent(1.5f, 120.0f, 1));
                scene.addGameObject(enemy);
            } catch (Exception ignored) {}
        }
    }
    
    /**
     * 处理玩家输入
     */
    public void handlePlayerInput() {
        List<GameObject> players = scene.findGameObjectsByComponent(TransformComponent.class);
        if (players.isEmpty()) return;
        
        GameObject player = players.get(0);
        TransformComponent transform = player.getComponent(TransformComponent.class);
        PhysicsComponent physics = player.getComponent(PhysicsComponent.class);
        
        if (transform == null || physics == null) return;
        
        Vector2 movement = new Vector2();
        
        if (inputManager.isKeyPressed(87) || inputManager.isKeyPressed(38)) { // W或上箭头
            movement.y -= 1;
        }
        if (inputManager.isKeyPressed(83) || inputManager.isKeyPressed(40)) { // S或下箭头
            movement.y += 1;
        }
        if (inputManager.isKeyPressed(65) || inputManager.isKeyPressed(37)) { // A或左箭头
            movement.x -= 1;
        }
        if (inputManager.isKeyPressed(68) || inputManager.isKeyPressed(39)) { // D或右箭头
            movement.x += 1;
        }
        
        if (movement.magnitude() > 0) {
            movement = movement.normalize().multiply(200);
            physics.setVelocity(movement);
        }
        
        // 边界检查
        Vector2 pos = transform.getPosition();
        if (pos.x < 0) pos.x = 0;
        if (pos.y < 0) pos.y = 0;
        if (pos.x > 800 - 20) pos.x = 800 - 20;
        if (pos.y > 600 - 20) pos.y = 600 - 20;
        transform.setPosition(pos);
    }

    /**
     * 玩家射击：由鼠标控制方向（左键）
     */
    public void handleShooting() {
        // 已并行化：handleShooting（敌人射击）
        // - 计算阶段（可并行）：决定哪些敌人发射及子弹参数
        // - 应用阶段（主线程）：调用 spawnProjectile(...) 和 resetCooldown()
        List<GameObject> players = scene.findGameObjectsByComponent(TransformComponent.class);
        if (players.isEmpty()) return;
        GameObject player = players.get(0);
        TransformComponent pTrans = player.getComponent(TransformComponent.class);
        if (pTrans == null) return;

        // 玩家持续射击（由 playerShootCooldown 控制）
        playerShootCooldown -= 0; // 由外部 deltaTime 控制
        if ((inputManager.isMouseButtonPressed(1) || inputManager.isMouseButtonPressed(0))) {
            if (playerShootCooldown <= 0f) {
                Vector2 mouse = inputManager.getMousePosition();
                Vector2 dir = new Vector2(mouse.x - pTrans.getPosition().x, mouse.y - pTrans.getPosition().y).normalize();
                spawnProjectile(pTrans.getPosition().add(new Vector2(8, 0)), dir.multiply(400), 1, player, true);
                playerShootCooldown = 0.12f;
            }
        }

        // 敌人独立射击：遍历具有 ShooterComponent 的对象
        List<com.gameengine.components.ShooterComponent> shooters = scene.getComponents(com.gameengine.components.ShooterComponent.class);
        if (shooters.isEmpty()) return;

        // 如果启用了并行且射手数量较多：
        // - 并行计算（compute）：判断每个射手是否发射，并生成子弹请求（只读操作）
        // - 主线程应用（apply）：统一在主线程调用 spawnProjectile(...) 与 resetCooldown()，避免并发修改场景
        class ProjectileRequest {
            final Vector2 pos;
            final Vector2 vel;
            final int damage;
            final GameObject owner;
            final com.gameengine.components.ShooterComponent shooterRef;
            ProjectileRequest(Vector2 p, Vector2 v, int d, GameObject o, com.gameengine.components.ShooterComponent s) {
                pos = p; vel = v; damage = d; owner = o; shooterRef = s;
            }
        }

        Vector2 playerPosSnapshot = pTrans.getPosition();
        Queue<ProjectileRequest> requests = new ConcurrentLinkedQueue<>();

        if (!parallelEnabled || executor == null || shooters.size() < 64) {
            for (com.gameengine.components.ShooterComponent shooter : shooters) {
                GameObject owner = shooter.getOwner();
                if (owner == null) continue;
                TransformComponent eTrans = owner.getComponent(TransformComponent.class);
                if (eTrans == null) continue;
                if (shooter.canShoot()) {
                    Vector2 dir = new Vector2(playerPosSnapshot.x - eTrans.getPosition().x, playerPosSnapshot.y - eTrans.getPosition().y).normalize();
                    requests.add(new ProjectileRequest(eTrans.getPosition(), dir.multiply(shooter.getSpeed()), shooter.getDamage(), owner, shooter));
                }
            }
        } else {
            int threadCount = parallelThreads > 0 ? parallelThreads : Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
            int batchSize = Math.max(64, shooters.size() / (threadCount * 4));
            List<Future<?>> futs = new ArrayList<>();
            for (int i = 0; i < shooters.size(); i += batchSize) {
                final int start = i;
                final int end = Math.min(i + batchSize, shooters.size());
                futs.add(executor.submit(() -> {
                    for (int j = start; j < end; j++) {
                        com.gameengine.components.ShooterComponent shooter = shooters.get(j);
                        GameObject owner = shooter.getOwner();
                        if (owner == null) continue;
                        TransformComponent eTrans = owner.getComponent(TransformComponent.class);
                        if (eTrans == null) continue;
                        if (shooter.canShoot()) {
                            Vector2 dir = new Vector2(playerPosSnapshot.x - eTrans.getPosition().x, playerPosSnapshot.y - eTrans.getPosition().y).normalize();
                            requests.add(new ProjectileRequest(eTrans.getPosition(), dir.multiply(shooter.getSpeed()), shooter.getDamage(), owner, shooter));
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : futs) { try { f.get(); } catch (Exception ignored) {} }
        }

        // Apply projectile spawns and reset shooter cooldowns on main thread
        // （应用阶段 - 单线程执行以避免场景并发修改）
        for (ProjectileRequest r : requests) {
            spawnProjectile(r.pos, r.vel, r.damage, r.owner, false);
            if (r.shooterRef != null) r.shooterRef.resetCooldown();
        }
    }
    
    /**
     * 更新物理系统
     */
    public void updatePhysics() {
        // 已并行化：updatePhysics
        // - 仅处理有移动的 PhysicsComponent（velocity > 0）
        // - 并行分支在 moving 列表上计算边界反射与位置修正
        frameCounter++;
        List<PhysicsComponent> physicsComponents = scene.getComponents(PhysicsComponent.class);
        if (!parallelEnabled || executor == null || physicsComponents.size() < 50) {
            // 串行回退（小规模负载使用），避免并行调度开销超过收益
            long t0 = System.nanoTime();
            for (PhysicsComponent physics : physicsComponents) {
                // 边界反弹
                GameObject owner = physics.getOwner();
                if (owner == null) continue;
                TransformComponent transform = owner.getComponent(TransformComponent.class);
                if (transform != null) {
                    Vector2 pos = transform.getPosition();
                    Vector2 velocity = physics.getVelocity();

                    if (pos.x <= 0 || pos.x >= 800 - 15) {
                        velocity.x = -velocity.x;
                        physics.setVelocity(velocity);
                    }
                    if (pos.y <= 0 || pos.y >= 600 - 15) {
                        velocity.y = -velocity.y;
                        physics.setVelocity(velocity);
                    }

                    // 确保在边界内
                    if (pos.x < 0) pos.x = 0;
                    if (pos.y < 0) pos.y = 0;
                    if (pos.x > 800 - 15) pos.x = 800 - 15;
                    if (pos.y > 600 - 15) pos.y = 600 - 15;
                    transform.setPosition(pos);
                }
            }
            long t1 = System.nanoTime();
            // sample print to avoid per-frame IO overhead
            if (physicsComponents.size() > 0 && (frameCounter % sampleInterval == 0)) {
                System.out.println(String.format("[updatePhysics] serial ms=%.3f, components=%d", (t1 - t0) / 1_000_000.0, physicsComponents.size()));
            }
            return;
        }

    // 并行分支（对移动列表分批处理）
    long t0 = System.nanoTime();
        int threadCount = parallelThreads > 0 ? parallelThreads : Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        int batchSize = Math.max(128, physicsComponents.size() / (threadCount * 4));
        batchSize = Math.min(Math.max(1, batchSize), physicsComponents.size());
        List<Future<?>> futs = new ArrayList<>();
        for (int i = 0; i < physicsComponents.size(); i += batchSize) {
            final int start = i;
            final int end = Math.min(i + batchSize, physicsComponents.size());
            futs.add(executor.submit(() -> {
                for (int j = start; j < end; j++) {
                    PhysicsComponent physics = physicsComponents.get(j);
                    GameObject owner = physics.getOwner();
                    if (owner == null) continue;
                    TransformComponent transform = owner.getComponent(TransformComponent.class);
                    if (transform != null) {
                        Vector2 pos = transform.getPosition();
                        Vector2 velocity = physics.getVelocity();

                        if (pos.x <= 0 || pos.x >= 800 - 15) {
                            velocity.x = -velocity.x;
                            physics.setVelocity(velocity);
                        }
                        if (pos.y <= 0 || pos.y >= 600 - 15) {
                            velocity.y = -velocity.y;
                            physics.setVelocity(velocity);
                        }

                        if (pos.x < 0) pos.x = 0;
                        if (pos.y < 0) pos.y = 0;
                        if (pos.x > 800 - 15) pos.x = 800 - 15;
                        if (pos.y > 600 - 15) pos.y = 600 - 15;
                        transform.setPosition(pos);
                    }
                }
                return null;
            }));
        }

        try {
            for (Future<?> f : futs) { try { f.get(); } catch (Exception ignored) {} }
        } catch (Exception e) {
            System.out.println("[updatePhysics] parallel failed, falling back to serial: " + e);
            // 并行失败，回退到串行处理
            for (PhysicsComponent physics : physicsComponents) {
                GameObject owner = physics.getOwner();
                if (owner == null) continue;
                TransformComponent transform = owner.getComponent(TransformComponent.class);
                if (transform != null) {
                    Vector2 pos = transform.getPosition();
                    Vector2 velocity = physics.getVelocity();

                    if (pos.x <= 0 || pos.x >= 800 - 15) {
                        velocity.x = -velocity.x;
                        physics.setVelocity(velocity);
                    }
                    if (pos.y <= 0 || pos.y >= 600 - 15) {
                        velocity.y = -velocity.y;
                        physics.setVelocity(velocity);
                    }

                    if (pos.x < 0) pos.x = 0;
                    if (pos.y < 0) pos.y = 0;
                    if (pos.x > 800 - 15) pos.x = 800 - 15;
                    if (pos.y > 600 - 15) pos.y = 600 - 15;
                    transform.setPosition(pos);
                }
            }
        }

        long t1 = System.nanoTime();
        if (physicsComponents.size() > 0 && (frameCounter % sampleInterval == 0)) {
            System.out.println(String.format("[updatePhysics] parallel ms=%.3f, components=%d, threads=%d", (t1 - t0) / 1_000_000.0, physicsComponents.size(), threadCount));
        }
    }

    /**
     * 玩家与障碍接触检测（近战碰撞造成伤害）
     */
    public void checkPlayerObstacleCollision(float deltaTime) {
        // 已并行化：checkPlayerObstacleCollision（玩家与敌人接触检测）
        // - 检测阶段在敌人较多时并行运行；应用（伤害 + 冷却）在主线程串行执行
        // cooldown decrement
        playerHitCooldown -= deltaTime;

        List<GameObject> players = scene.findGameObjectsByComponent(TransformComponent.class);
        if (players.isEmpty()) return;
        GameObject player = players.get(0);
        TransformComponent pTrans = player.getComponent(TransformComponent.class);
        HealthComponent pHealth = player.getComponent(HealthComponent.class);
        if (pTrans == null || pHealth == null) return;
        // If player is currently invulnerable, skip detection to save work
        if (playerHitCooldown > 0f) return;

        // Separate scene objects into enemies (have HealthComponent) and obstacles (render only)
        List<GameObject> enemies = new ArrayList<>();
        List<GameObject> obstacles = new ArrayList<>();
        for (GameObject obj : scene.getGameObjects()) {
            if (obj == player) continue;
            if (obj.getComponent(HealthComponent.class) != null) {
                enemies.add(obj);
            } else if (obj.getComponent(RenderComponent.class) != null) {
                obstacles.add(obj);
            }
        }

        Vector2 playerPos = pTrans.getPosition();

        // First, check collisions with enemies (parallelizable when many enemies)
        GameObject hitEnemy = null;
        if (!enemies.isEmpty()) {
            if (!parallelEnabled || executor == null || enemies.size() < 64) {
                for (GameObject enemy : enemies) {
                    TransformComponent eTrans = enemy.getComponent(TransformComponent.class);
                    if (eTrans == null) continue;
                    if (playerPos.distance(eTrans.getPosition()) < 16) {
                        hitEnemy = enemy;
                        break;
                    }
                }
            } else {
                final Queue<GameObject> hits = new ConcurrentLinkedQueue<>();
                int threadCount = parallelThreads > 0 ? parallelThreads : Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
                int batchSize = Math.max(64, enemies.size() / (threadCount * 4));
                List<Future<?>> futs = new ArrayList<>();
                for (int i = 0; i < enemies.size(); i += batchSize) {
                    final int start = i;
                    final int end = Math.min(i + batchSize, enemies.size());
                    futs.add(executor.submit(() -> {
                        for (int j = start; j < end; j++) {
                            GameObject enemy = enemies.get(j);
                            TransformComponent eTrans = enemy.getComponent(TransformComponent.class);
                            if (eTrans == null) continue;
                            if (playerPos.distance(eTrans.getPosition()) < 16) {
                                hits.add(enemy);
                                break; // stop within this batch early
                            }
                        }
                        return null;
                    }));
                }
                for (Future<?> f : futs) { try { f.get(); } catch (Exception ignored) {} }
                if (!hits.isEmpty()) hitEnemy = hits.peek();
            }
        }

        if (hitEnemy != null) {
            // apply single hit and set cooldown
            pHealth.applyDamage(1);
            playerHitCooldown = 0.5f;
            if (pHealth.isDead()) player.destroy();
            return;
        }

        // If no enemy hit, fall back to obstacle checks (serial, usually small count)
        for (GameObject obj : obstacles) {
            TransformComponent oTrans = obj.getComponent(TransformComponent.class);
            if (oTrans == null) continue;
            if (playerPos.distance(oTrans.getPosition()) < 16) {
                pHealth.applyDamage(1);
                playerHitCooldown = 0.5f;
                if (pHealth.isDead()) {
                    player.destroy();
                }
                break;
            }
        }
    }
    
    /**
     * 检查碰撞
     */
    public void checkCollisions() {
        // 直接查找玩家对象
        List<GameObject> players = scene.findGameObjectsByComponent(TransformComponent.class);
        if (players.isEmpty()) return;
        
        GameObject player = players.get(0);
        TransformComponent playerTransform = player.getComponent(TransformComponent.class);
        if (playerTransform == null) return;
        
        // 检测投射物与敌人/玩家的碰撞
        List<GameObject> allObjects = scene.getGameObjects();
        List<GameObject> projectiles = new ArrayList<>();
        for (GameObject obj : allObjects) {
            if (obj.getComponent(ProjectileComponent.class) != null) projectiles.add(obj);
        }

        if (!parallelEnabled || executor == null || projectiles.size() < 50) {
            // 串行路径（保留原始行为）
            for (GameObject obj : allObjects) {
                if (obj.getComponent(ProjectileComponent.class) != null) {
                    GameObject projOwner = obj; // projectile object
                    TransformComponent projT = obj.getComponent(TransformComponent.class);
                    ProjectileComponent projC = obj.getComponent(ProjectileComponent.class);
                    if (projT == null || projC == null) continue;

                    // check hit enemies
                    for (GameObject target : allObjects) {
                        if (target == obj) continue;
                        // avoid hitting shooter
                        if (projC.getShooter() != null && projC.getShooter() == target) continue;
                        HealthComponent hc = target.getComponent(HealthComponent.class);
                        TransformComponent tTrans = target.getComponent(TransformComponent.class);
                        if (hc != null && tTrans != null) {
                            if (projT.getPosition().distance(tTrans.getPosition()) < 12) {
                                hc.applyDamage(projC.getDamage());
                                // destroy projectile
                                obj.destroy();
                                if (hc.isDead()) {
                                    target.destroy();
                                }
                                break;
                            }
                        }
                    }
                }
            }
            return;
        }

        // 并行碰撞检测：在并行的计算阶段收集碰撞动作（只读快照），随后在主线程串行应用这些动作以保持场景一致性
        final List<GameObject> targetsSnapshot = new ArrayList<>(allObjects);
        final Queue<CollisionAction> actions = new ConcurrentLinkedQueue<>();

        int threadCount = parallelThreads > 0 ? parallelThreads : Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        int batchSize = Math.max(1, projectiles.size() / threadCount + 1);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < projectiles.size(); i += batchSize) {
            final int start = i;
            final int end = Math.min(i + batchSize, projectiles.size());
            tasks.add(() -> {
                for (int j = start; j < end; j++) {
                    GameObject proj = projectiles.get(j);
                    TransformComponent projT = proj.getComponent(TransformComponent.class);
                    ProjectileComponent projC = proj.getComponent(ProjectileComponent.class);
                    if (projT == null || projC == null) continue;

                    for (GameObject target : targetsSnapshot) {
                        if (target == proj) continue;
                        if (projC.getShooter() != null && projC.getShooter() == target) continue;
                        HealthComponent hc = target.getComponent(HealthComponent.class);
                        TransformComponent tTrans = target.getComponent(TransformComponent.class);
                        if (hc != null && tTrans != null) {
                            if (projT.getPosition().distance(tTrans.getPosition()) < 12) {
                                actions.add(new CollisionAction(proj, target, projC.getDamage()));
                                break; // projectile used
                            }
                        }
                    }
                }
                return null;
            });
        }

        try {
            List<Future<Void>> futs = executor.invokeAll(tasks);
            for (Future<Void> f : futs) { try { f.get(); } catch (Exception ignored) {} }
        } catch (Exception e) {
            System.out.println("[checkCollisions] parallel detection failed: " + e + ", falling back to serial");
            // fallback to serial
            for (GameObject obj : allObjects) {
                if (obj.getComponent(ProjectileComponent.class) != null) {
                    TransformComponent projT = obj.getComponent(TransformComponent.class);
                    ProjectileComponent projC = obj.getComponent(ProjectileComponent.class);
                    if (projT == null || projC == null) continue;
                    for (GameObject target : allObjects) {
                        if (target == obj) continue;
                        if (projC.getShooter() != null && projC.getShooter() == target) continue;
                        HealthComponent hc = target.getComponent(HealthComponent.class);
                        TransformComponent tTrans = target.getComponent(TransformComponent.class);
                        if (hc != null && tTrans != null) {
                            if (projT.getPosition().distance(tTrans.getPosition()) < 12) {
                                hc.applyDamage(projC.getDamage());
                                obj.destroy();
                                if (hc.isDead()) target.destroy();
                                break;
                            }
                        }
                    }
                }
            }
            return;
        }

        // 在主线程串行应用动作，确保每个投射物只被处理一次（避免并发销毁/重复伤害）
        Set<GameObject> usedProjectiles = new HashSet<>();
        for (CollisionAction a : actions) {
            if (a == null) continue;
            GameObject proj = a.projectile;
            GameObject target = a.target;
            if (usedProjectiles.contains(proj)) continue; // already applied
            HealthComponent hc = target.getComponent(HealthComponent.class);
            if (hc != null) {
                hc.applyDamage(a.damage);
                proj.destroy();
                usedProjectiles.add(proj);
                if (hc.isDead()) target.destroy();
            }
        }
    }

    /**
     * spawn projectile helper
     */
    private void spawnProjectile(Vector2 pos, Vector2 velocity, int damage, GameObject owner, boolean isPlayer) {
        GameObject proj = new GameObject("Projectile");
        TransformComponent t = proj.addComponent(new TransformComponent(new Vector2(pos)));
        // 三角形尺寸 - 放大以便可见性更好
        RenderComponent r = proj.addComponent(new RenderComponent(RenderComponent.RenderType.TRIANGLE, new Vector2(12,12), isPlayer ? new RenderComponent.Color(0.0f, 1.0f, 0.6f, 1.0f) : new RenderComponent.Color(1.0f, 0.3f, 0.2f, 1.0f)));
        if (this.renderer != null) r.setRenderer(this.renderer);
        // 将旋转设置为与速度方向一致（使三角形朝向飞行方向）
        float rot = 0.0f;
        if (velocity != null) {
            rot = (float) Math.toDegrees(Math.atan2(velocity.y, velocity.x)) + 90f; // adjust so triangle points along velocity
        }
        r.setRotation(rot);
        proj.addComponent(new PhysicsComponent(0.0f));
        proj.addComponent(new ProjectileComponent(velocity, 3.0f, damage, owner));
        scene.addGameObject(proj);
    }

    /**
     * 渲染 HUD（玩家HP）
     */
    public void renderHUD() {
        List<GameObject> players = scene.findGameObjectsByComponent(HealthComponent.class);
        if (!players.isEmpty()) {
            GameObject player = players.get(0);
            HealthComponent hc = player.getComponent(HealthComponent.class);
            if (hc != null) {
                String hpText = "HP: " + hc.getHp() + " / " + hc.getMaxHp();
                renderer.drawText(hpText, 8, 18, java.awt.Color.RED);
            }
        }

        if (gameOver) {
            String over = "GAME OVER";
            renderer.drawText(over, 320, 200, java.awt.Color.WHITE);
            String t = String.format("Survived: %.2f s", survivalTime);
            renderer.drawText(t, 320, 220, java.awt.Color.WHITE);
            renderer.drawText("Press R to restart", 320, 240, java.awt.Color.WHITE);
        }
    }

    public boolean isGameOver() { return gameOver; }
    public float getSurvivalTime() { return survivalTime; }
}
