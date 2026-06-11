package com.cardgame.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingAction {
    private String playerId;
    private String cardId;
    private List<String> targetIds;
    private String actionType;
}
