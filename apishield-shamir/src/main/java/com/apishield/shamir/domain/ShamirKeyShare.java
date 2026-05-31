package com.apishield.shamir.domain;

import com.apishield.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ShamirKeyShare extends BaseEntity {
    private String keyId;
    private int shareIndex;
    private String shareValue;
    private String ownerId;
    private int threshold;
    private int totalShares;
    private String status;
}
