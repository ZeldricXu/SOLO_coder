package com.datamasker.interfaces.dto.mpc;

import lombok.Data;

@Data
public class SubmitInputRequest {

    private String partyId;
    private String encryptedInput;
}
