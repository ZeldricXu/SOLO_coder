package com.cardgame.common.entity;

import lombok.Getter;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Getter
public class BuffTriggerContext {
    private static final int MAX_DEPTH = 5;

    private final int depth;
    private final Set<String> triggeredBuffIds;
    private final String rootBuffId;
    private final String rootSourceId;

    public BuffTriggerContext(String rootBuffId, String rootSourceId) {
        this.depth = 0;
        this.triggeredBuffIds = new HashSet<>();
        this.rootBuffId = rootBuffId;
        this.rootSourceId = rootSourceId;
    }

    private BuffTriggerContext(int depth, Set<String> triggeredBuffIds, String rootBuffId, String rootSourceId) {
        this.depth = depth;
        this.triggeredBuffIds = Collections.unmodifiableSet(new HashSet<>(triggeredBuffIds));
        this.rootBuffId = rootBuffId;
        this.rootSourceId = rootSourceId;
    }

    public boolean canTrigger(String buffInstanceId) {
        if (depth >= MAX_DEPTH) {
            return false;
        }
        return !triggeredBuffIds.contains(buffInstanceId);
    }

    public BuffTriggerContext nextLevel(String buffInstanceId) {
        Set<String> newTriggered = new HashSet<>(triggeredBuffIds);
        newTriggered.add(buffInstanceId);
        return new BuffTriggerContext(depth + 1, newTriggered, rootBuffId, rootSourceId);
    }

    public boolean isMaxDepthReached() {
        return depth >= MAX_DEPTH;
    }

    public static int getMaxDepth() {
        return MAX_DEPTH;
    }
}
