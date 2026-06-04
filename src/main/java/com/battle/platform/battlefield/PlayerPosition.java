package com.battle.platform.battlefield;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlayerPosition {
    private Long playerId;
    private double x;
    private double y;
    private double z;
    private float rotation;
    private long timestamp;

    public int getGridX(int gridSize) {
        return (int) (x / gridSize);
    }

    public int getGridZ(int gridSize) {
        return (int) (z / gridSize);
    }
}
