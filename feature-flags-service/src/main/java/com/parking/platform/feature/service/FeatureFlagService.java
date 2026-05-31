package com.parking.platform.feature.service;

import com.parking.platform.common.exception.ResourceNotFoundException;
import com.parking.platform.common.util.IdGenerator;
import com.parking.platform.feature.dto.EvaluationRequest;
import com.parking.platform.feature.dto.EvaluationResponse;
import com.parking.platform.feature.entity.FeatureFlag;
import com.parking.platform.feature.entity.UserGroup;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FeatureFlagService {

    @Getter
    private final Map<String, FeatureFlag> flags = new ConcurrentHashMap<>();
    @Getter
    private final Map<String, UserGroup> groups = new ConcurrentHashMap<>();

    public FeatureFlag createFlag(FeatureFlag flag) {
        String id = IdGenerator.generate("ff");
        flag.setId(id);
        flag.setKey(flag.getKey());
        flag.setCreatedAt(System.currentTimeMillis());
        flag.setUpdatedAt(System.currentTimeMillis());
        flags.put(id, flag);
        return flag;
    }

    public FeatureFlag updateFlag(String id, FeatureFlag flagUpdates) {
        FeatureFlag existing = flags.get(id);
        if (existing == null) {
            throw new ResourceNotFoundException("Feature flag not found: " + id);
        }
        if (flagUpdates.getName() != null) {
            existing.setName(flagUpdates.getName());
        }
        if (flagUpdates.getDescription() != null) {
            existing.setDescription(flagUpdates.getDescription());
        }
        existing.setEnabled(flagUpdates.isEnabled());
        if (flagUpdates.getType() != null) {
            existing.setType(flagUpdates.getType());
        }
        existing.setRolloutPercentage(flagUpdates.getRolloutPercentage());
        if (flagUpdates.getTargetGroups() != null) {
            existing.setTargetGroups(flagUpdates.getTargetGroups());
        }
        if (flagUpdates.getTargetUserIds() != null) {
            existing.setTargetUserIds(flagUpdates.getTargetUserIds());
        }
        if (flagUpdates.getAttributes() != null) {
            existing.setAttributes(flagUpdates.getAttributes());
        }
        existing.setUpdatedAt(System.currentTimeMillis());
        flags.put(id, existing);
        return existing;
    }

    public void deleteFlag(String id) {
        if (!flags.containsKey(id)) {
            throw new ResourceNotFoundException("Feature flag not found: " + id);
        }
        flags.remove(id);
    }

    public FeatureFlag getFlag(String id) {
        FeatureFlag flag = flags.get(id);
        if (flag == null) {
            throw new ResourceNotFoundException("Feature flag not found: " + id);
        }
        return flag;
    }

    public List<FeatureFlag> getAllFlags() {
        return List.copyOf(flags.values());
    }

    public EvaluationResponse evaluate(EvaluationRequest request) {
        FeatureFlag flag = flags.values().stream()
                .filter(f -> f.getKey().equals(request.getFlagKey()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Feature flag not found: " + request.getFlagKey()));

        EvaluationResponse response = new EvaluationResponse();
        response.setFlagKey(flag.getKey());
        response.setRolloutPercentage(flag.getRolloutPercentage());

        if (!flag.isEnabled()) {
            response.setEnabled(false);
            response.setReason("FLAG_DISABLED");
            return response;
        }

        if (flag.getType() == FeatureFlag.FlagType.BOOLEAN) {
            response.setEnabled(true);
            response.setReason("BOOLEAN_FLAG");
            return response;
        }

        if (flag.getTargetUserIds() != null && flag.getTargetUserIds().contains(request.getUserId())) {
            response.setEnabled(true);
            response.setReason("USER_TARGETED");
            return response;
        }

        if (flag.getTargetGroups() != null) {
            for (UserGroup group : flag.getTargetGroups()) {
                if (group.matches(request.getAttributes())) {
                    response.setEnabled(true);
                    response.setReason("GROUP_MATCHED: " + group.getName());
                    return response;
                }
            }
        }

        if (flag.getType() == FeatureFlag.FlagType.GRADUAL_ROLLOUT) {
            double hash = hashUserId(request.getUserId());
            if (hash <= flag.getRolloutPercentage()) {
                response.setEnabled(true);
                response.setReason("GRADUAL_ROLLOUT");
            } else {
                response.setEnabled(false);
                response.setReason("GRADUAL_ROLLOUT_NOT_INCLUDED");
            }
            return response;
        }

        response.setEnabled(false);
        response.setReason("NO_MATCHING_RULE");
        return response;
    }

    public UserGroup createGroup(UserGroup group) {
        String id = IdGenerator.generate("grp");
        group.setId(id);
        groups.put(id, group);
        return group;
    }

    public UserGroup updateGroup(String id, UserGroup updates) {
        UserGroup existing = groups.get(id);
        if (existing == null) {
            throw new ResourceNotFoundException("User group not found: " + id);
        }
        if (updates.getName() != null) {
            existing.setName(updates.getName());
        }
        if (updates.getDescription() != null) {
            existing.setDescription(updates.getDescription());
        }
        if (updates.getRules() != null) {
            existing.setRules(updates.getRules());
        }
        groups.put(id, existing);
        return existing;
    }

    public void deleteGroup(String id) {
        groups.remove(id);
    }

    public List<UserGroup> getAllGroups() {
        return List.copyOf(groups.values());
    }

    private double hashUserId(String userId) {
        if (userId == null) {
            userId = "anonymous";
        }
        int hash = 0;
        for (char c : userId.toCharArray()) {
            hash = 31 * hash + c;
        }
        return Math.abs(hash) % 100;
    }
}
