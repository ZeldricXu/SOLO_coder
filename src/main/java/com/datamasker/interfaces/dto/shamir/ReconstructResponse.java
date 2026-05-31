package com.datamasker.interfaces.dto.shamir;

import lombok.Data;

@Data
public class ReconstructResponse {
    private String secretId;
    private String recoveredSecret;
    private int participantCount;
    private String recoveredAt;
}
