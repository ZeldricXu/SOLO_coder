package com.cardgame.common.protocol.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconnectRequest {
    private String accountId;
    private String roomId;
    private String token;
}
