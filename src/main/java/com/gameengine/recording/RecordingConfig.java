package com.gameengine.recording;

public class RecordingConfig {
    public String outputPath = "recordings/recording.jsonl";
    public double keyframeIntervalSec = 0.5; // 每隔多少秒写一个 keyframe
    public double sampleIntervalSec = 0.05; // 更频繁的采样，用于精细回放（子弹等）
    public int queueCapacity = 1024;
    public int quantizeDecimals = 3;
    public int maxRecordFiles = 10; // 最多保存的回放文件数，超出则删除最老的

    public RecordingConfig() {}

    public RecordingConfig(String outputPath) {
        if (outputPath != null && !outputPath.isEmpty()) this.outputPath = outputPath;
    }
}
