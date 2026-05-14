package com.assetmanage.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum AssetStatus {

    IDLE("idle", "闲置"),
    IN_USE("in_use", "使用中"),
    MAINTENANCE("maintenance", "维护中"),
    SCRAPPED("scrapped", "已报废");

    private final String code;
    private final String description;
}
