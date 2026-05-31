package com.datamasker.interfaces.dto.tee;

import lombok.Data;

@Data
public class ChannelResponse {

    private String channelId;

    private String enclaveId;

    private String sessionKey;

    private boolean active;
}
