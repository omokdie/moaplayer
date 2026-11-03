package com.moa.player;

import java.util.ArrayList;
import java.util.List;

public final class FolderItem {
    public String path;
    public String name;
    public final List<VideoItem> videos = new ArrayList<>();
    public long totalSize;
    public long lastModified;
    public int newCount;
}
