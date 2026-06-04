package com.battle.platform.matching;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MatchingTicket implements Comparable<MatchingTicket> {
    private MatchingPlayer player;
    private int bracket;
    private double priority;

    @Override
    public int compareTo(MatchingTicket other) {
        return Double.compare(other.priority, this.priority);
    }
}
