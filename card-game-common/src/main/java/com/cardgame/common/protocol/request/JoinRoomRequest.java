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
public class JoinRoomRequest {
    private String roomId;
    private String inviteCode;
    private String password;
    private PlayerClass playerClass;
}
