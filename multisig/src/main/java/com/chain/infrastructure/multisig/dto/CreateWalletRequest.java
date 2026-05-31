package com.chain.infrastructure.multisig.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class CreateWalletRequest {

    private String chainType;

    private String walletAddress;

    private Integer threshold;

    private List<String> owners;

    private String name;

    private String description;
}
