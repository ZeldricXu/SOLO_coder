package com.datamasker.domain.shamir.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigInteger;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class KeyShard {
    private String secretId;
    private int shardIndex;
    private BigInteger shardData;
    private int threshold;
    private int totalShares;
    private String owner;
}
