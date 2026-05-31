package com.parking.platform.feature.entity;

import com.parking.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class FeatureFlag extends BaseEntity {
    private String key;
    private String name;
    private String description;
    private boolean enabled;
    private FlagType type;
    private double rolloutPercentage;
    private List<UserGroup> targetGroups = new ArrayList<>();
    private List<String> targetUserIds = new ArrayList<>();
    private Map<String, String> attributes;
    private Long createdAt;
    private Long updatedAt;

    public enum FlagType {
        BOOLEAN,
        GRADUAL_ROLLOUT,
        USER_SEGMENT
    }
}
