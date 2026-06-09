package com.cardgame.common.protocol.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {
    private String playerId;
    private String token;
    private int playerLevel;
    private int totalGames;
    private int wins;
}
