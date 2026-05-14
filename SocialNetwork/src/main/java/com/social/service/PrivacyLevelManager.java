package com.social.service;

import com.social.config.PrivacyLevelConfig;
import com.social.config.PrivacyLevelConfig.PrivacyLevelDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class PrivacyLevelManager {

    @Autowired
    private PrivacyLevelConfig privacyLevelConfig;

    public List<PrivacyLevelDefinition> getAllEnabledFriendRequestPolicies() {
        List<PrivacyLevelDefinition> list = new ArrayList<>();
        for (Map.Entry<String, PrivacyLevelDefinition> entry : 
                privacyLevelConfig.getFriendRequestPolicies().entrySet()) {
            if (entry.getValue().isEnabled()) {
                list.add(entry.getValue());
            }
        }
        list.sort(Comparator.comparingInt(PrivacyLevelDefinition::getPriority));
        return list;
    }

    public List<PrivacyLevelDefinition> getAllEnabledMessagePolicies() {
        List<PrivacyLevelDefinition> list = new ArrayList<>();
        for (Map.Entry<String, PrivacyLevelDefinition> entry : 
                privacyLevelConfig.getMessagePolicies().entrySet()) {
            if (entry.getValue().isEnabled()) {
                list.add(entry.getValue());
            }
        }
        list.sort(Comparator.comparingInt(PrivacyLevelDefinition::getPriority));
        return list;
    }

    public List<PrivacyLevelDefinition> getAllEnabledPostVisibilities() {
        List<PrivacyLevelDefinition> list = new ArrayList<>();
        for (Map.Entry<String, PrivacyLevelDefinition> entry : 
                privacyLevelConfig.getPostVisibilities().entrySet()) {
            if (entry.getValue().isEnabled()) {
                list.add(entry.getValue());
            }
        }
        list.sort(Comparator.comparingInt(PrivacyLevelDefinition::getPriority));
        return list;
    }

    public List<PrivacyLevelDefinition> getAllEnabledProfileVisibilities() {
        List<PrivacyLevelDefinition> list = new ArrayList<>();
        for (Map.Entry<String, PrivacyLevelDefinition> entry : 
                privacyLevelConfig.getProfileVisibilities().entrySet()) {
            if (entry.getValue().isEnabled()) {
                list.add(entry.getValue());
            }
        }
        list.sort(Comparator.comparingInt(PrivacyLevelDefinition::getPriority));
        return list;
    }

    public PrivacyLevelDefinition getFriendRequestPolicy(String code) {
        PrivacyLevelDefinition def = privacyLevelConfig.getFriendRequestPolicies().get(code);
        if (def != null && def.isEnabled()) {
            return def;
        }
        return null;
    }

    public PrivacyLevelDefinition getMessagePolicy(String code) {
        PrivacyLevelDefinition def = privacyLevelConfig.getMessagePolicies().get(code);
        if (def != null && def.isEnabled()) {
            return def;
        }
        return null;
    }

    public PrivacyLevelDefinition getPostVisibility(String code) {
        PrivacyLevelDefinition def = privacyLevelConfig.getPostVisibilities().get(code);
        if (def != null && def.isEnabled()) {
            return def;
        }
        return null;
    }

    public PrivacyLevelDefinition getProfileVisibility(String code) {
        PrivacyLevelDefinition def = privacyLevelConfig.getProfileVisibilities().get(code);
        if (def != null && def.isEnabled()) {
            return def;
        }
        return null;
    }

    public boolean isValidFriendRequestPolicy(String code) {
        return getFriendRequestPolicy(code) != null;
    }

    public boolean isValidMessagePolicy(String code) {
        return getMessagePolicy(code) != null;
    }

    public boolean isValidPostVisibility(String code) {
        return getPostVisibility(code) != null;
    }

    public boolean isValidProfileVisibility(String code) {
        return getProfileVisibility(code) != null;
    }

    public String getDefaultFriendRequestPolicy() {
        List<PrivacyLevelDefinition> policies = getAllEnabledFriendRequestPolicies();
        if (!policies.isEmpty()) {
            return policies.get(0).getCode();
        }
        return "all";
    }

    public String getDefaultMessagePolicy() {
        List<PrivacyLevelDefinition> policies = getAllEnabledMessagePolicies();
        if (!policies.isEmpty()) {
            return policies.get(0).getCode();
        }
        return "all";
    }

    public String getDefaultPostVisibility() {
        List<PrivacyLevelDefinition> visibilities = getAllEnabledPostVisibilities();
        if (!visibilities.isEmpty()) {
            return visibilities.get(0).getCode();
        }
        return "public";
    }

    public String getDefaultProfileVisibility() {
        List<PrivacyLevelDefinition> visibilities = getAllEnabledProfileVisibilities();
        if (!visibilities.isEmpty()) {
            return visibilities.get(0).getCode();
        }
        return "public";
    }

    public Map<String, PrivacyLevelDefinition> getAllFriendRequestPolicies() {
        return privacyLevelConfig.getFriendRequestPolicies();
    }

    public Map<String, PrivacyLevelDefinition> getAllMessagePolicies() {
        return privacyLevelConfig.getMessagePolicies();
    }

    public Map<String, PrivacyLevelDefinition> getAllPostVisibilities() {
        return privacyLevelConfig.getPostVisibilities();
    }

    public Map<String, PrivacyLevelDefinition> getAllProfileVisibilities() {
        return privacyLevelConfig.getProfileVisibilities();
    }
}
