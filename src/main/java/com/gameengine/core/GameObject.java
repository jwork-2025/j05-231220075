package com.gameengine.core;

import com.gameengine.math.Vector2;
import java.util.UUID;
import java.util.*;

/**
 * 游戏对象基类，使用泛型组件系统
 */
public class GameObject {
    protected boolean active;
    protected String name;
    // 唯一 id（用于回放/网络等需要稳定标识符的场景）
    protected final String uid;
    protected final List<Component<?>> components;
    
    public GameObject() {
        this.active = true;
        this.name = "GameObject";
        this.components = new ArrayList<>();
        this.uid = UUID.randomUUID().toString();
    }
    
    public GameObject(String name) {
        this();
        this.name = name;
    }

    /**
     * 获取对象唯一 id（回放系统使用此 id 来追踪对象生命期）
     */
    public String getId() {
        return uid;
    }
    
    /**
     * 更新游戏对象逻辑
     */
    public void update(float deltaTime) {
        updateComponents(deltaTime);
    }
    
    /**
     * 渲染游戏对象
     */
    public void render() {
        renderComponents();
    }
    
    /**
     * 初始化游戏对象
     */
    public void initialize() {
        // 子类可以重写此方法进行初始化
    }
    
    /**
     * 销毁游戏对象
     */
    public void destroy() {
        // 标记为非活跃并禁用组件，但不要立即清除组件集合，
        // 防止在组件循环中被其它组件销毁时发生 ConcurrentModificationException。
        this.active = false;
        // 仅标记并禁用组件
        for (Component<?> component : components) {
            component.destroy();
            // 不在此处清除组件的 owner，以保证在本帧遍历组件时不出现 null 指针。
            // 真正的清理由 Scene.cleanup() 在安全时机完成。
        }
    }

    /**
     * 在安全时机清理组件（由 Scene 在移除对象时调用）
     */
    public void cleanup() {
        components.clear();
    }
    
    /**
     * 添加组件
     */
    public <T extends Component<T>> T addComponent(T component) {
        component.setOwner(this);
        components.add(component);
        component.initialize();
        return component;
    }
    
    /**
     * 获取组件
     */
    @SuppressWarnings("unchecked")
    public <T extends Component<T>> T getComponent(Class<T> componentType) {
        for (Component<?> component : components) {
            if (componentType.isInstance(component)) {
                return (T) component;
            }
        }
        return null;
    }
    
    /**
     * 检查是否有指定类型的组件
     */
    public <T extends Component<T>> boolean hasComponent(Class<T> componentType) {
        for (Component<?> component : components) {
            if (componentType.isInstance(component)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 更新所有组件
     */
    public void updateComponents(float deltaTime) {
        for (Component<?> component : components) {
            if (component.isEnabled()) {
                component.update(deltaTime);
            }
        }
    }
    
    /**
     * 渲染所有组件
     */
    public void renderComponents() {
        for (Component<?> component : components) {
            if (component.isEnabled()) {
                component.render();
            }
        }
    }
    
    // Getters and Setters
    
    public boolean isActive() {
        return active;
    }
    
    public void setActive(boolean active) {
        this.active = active;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
}