package com.gamestats.realtime.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnlineStats implements Serializable {
    private String statId;
    private String gameId;
    private int onlineCount;
    private Map<String, Integer> serverDistribution;
    private long sampleTime;
    private int peakToday;

    public void incrementServer(String serverId) {
        if (serverDistribution == null) {
            serverDistribution = new HashMap<>();
        }
        serverDistribution.put(serverId, serverDistribution.getOrDefault(serverId, 0) + 1);
    }

    public void decrementServer(String serverId) {
        if (serverDistribution == null) {
            serverDistribution = new HashMap<>();
            return;
        }
        int current = serverDistribution.getOrDefault(serverId, 0);
        if (current > 0) {
            serverDistribution.put(serverId, current - 1);
        }
    }
}
