package com.gamestats.realtime.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameEvent implements Serializable {
    private String eventId;
    private String playerId;
    private String gameId;
    private String serverId;
    private String eventType;
    private String eventTime;
    private Map<String, Object> eventData;
}
