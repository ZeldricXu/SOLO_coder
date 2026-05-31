package com.logmanager.domain.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class Entity extends BaseEntity {
    private String type;
    private String status;
}
