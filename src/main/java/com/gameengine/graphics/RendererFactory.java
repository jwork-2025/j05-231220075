package com.gameengine.graphics;

public class RendererFactory {
    public static IRenderer createRenderer(int width, int height, String title) {
        // 强制使用 GPU 渲染（不再回退到 Swing）。
        // 注意：在 macOS 上必须通过 JVM 参数 -XstartOnFirstThread 启动进程，否则 GLFW/AppKit 会崩溃。
        try {
            System.out.println("RendererFactory: creating GPURenderer (GPU-only mode)");
            return new GPURenderer(width, height, title);
        } catch (Throwable t) {
            // 如果 GPU 初始化失败，抛出运行时异常以便上层明确失败而不是默默回退。
            String msg = "GPURenderer 初始化失败：" + t.getMessage();
            System.err.println(msg);
            throw new RuntimeException(msg, t);
        }
    }
}
