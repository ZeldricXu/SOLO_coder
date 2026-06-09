package com.cardgame.common.protocol.request;

import com.cardgame.common.enums.PlayerClass;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateRoomRequest {
    private String roomName;
    private int maxPlayers;
    private PlayerClass playerClass;
    private String password;
    private boolean privateRoom;
}
