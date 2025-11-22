package com.gameengine.recording;

import java.io.File;
import java.io.IOException;
import java.util.List;

public interface RecordingStorage {
    void openWriter(String path) throws IOException;
    void writeLine(String line) throws IOException;
    void closeWriter();
    Iterable<String> readLines(String path) throws IOException;
    List<File> listRecordings();
    /**
     * 清理旧的回放文件，确保 recordings 目录中最多保留 maxFiles 个文件（按修改时间从新到旧保留）。
     */
    void cleanupOldRecordings(int maxFiles);
}
