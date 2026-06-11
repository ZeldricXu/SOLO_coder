package com.cardgame.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineEntry implements Comparable<TimelineEntry> {
    private String characterId;
    private String characterName;
    private boolean isPlayer;
    private int speed;
    private int turnOrder;
    private boolean hasActed;
    private long actionTime;
    private GameCharacter character;

    @Override
    public int compareTo(TimelineEntry other) {
        if (other.speed != this.speed) {
            return Integer.compare(other.speed, this.speed);
        }
        if (other.isPlayer != this.isPlayer) {
            return this.isPlayer ? -1 : 1;
        }
        return Integer.compare(this.turnOrder, other.turnOrder);
    }
}
