package com.datamasker.interfaces.dto.mpc;

import lombok.Data;

@Data
public class MpcResultResponse {

    private String sessionId;
    private String result;
    private int participantCount;
    private boolean verified;
}
