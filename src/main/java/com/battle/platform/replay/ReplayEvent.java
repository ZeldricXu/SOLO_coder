package com.battle.platform.replay;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplayEvent {
    private long timestamp;
    private int eventType;
    private Long playerId;
    private byte[] payload;

    public static final int TYPE_MOVE = 1;
    public static final int TYPE_SKILL = 2;
    public static final int TYPE_DAMAGE = 3;
    public static final int TYPE_DEATH = 4;
    public static final int TYPE_RESPAWN = 5;
    public static final int TYPE_CAPTURE = 6;

    public int estimateSize() {
        return 8 + 4 + 8 + (payload != null ? payload.length : 0) + 4;
    }
}
