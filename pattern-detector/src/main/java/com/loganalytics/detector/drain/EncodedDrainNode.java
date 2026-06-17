package com.loganalytics.detector.drain;

import com.loganalytics.common.model.LogPattern;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

class EncodedDrainNode {
    final int depth;
    final int tokenCode;
    final Int2ObjectMap<EncodedDrainNode> children;
    LogPattern pattern;
    long lastAccessTime;

    EncodedDrainNode(int depth, int tokenCode) {
        this.depth = depth;
        this.tokenCode = tokenCode;
        this.children = new Int2ObjectOpenHashMap<>();
        this.lastAccessTime = System.currentTimeMillis();
    }
}
