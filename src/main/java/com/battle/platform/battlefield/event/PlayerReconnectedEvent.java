package com.battle.platform.battlefield.event;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PlayerReconnectedEvent {
    private String battleId;
    private Long playerId;
    private io.netty.channel.Channel channel;
}
