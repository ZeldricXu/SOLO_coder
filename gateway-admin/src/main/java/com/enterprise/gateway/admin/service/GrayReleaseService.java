package com.enterprise.gateway.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.gateway.admin.mapper.GrayReleaseRuleMapper;
import com.enterprise.gateway.admin.mapper.TrafficMirrorRuleMapper;
import com.enterprise.gateway.common.model.GrayReleaseRule;
import com.enterprise.gateway.common.model.TrafficMirrorRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrayReleaseService {

    private final GrayReleaseRuleMapper grayReleaseRuleMapper;
    private final TrafficMirrorRuleMapper trafficMirrorRuleMapper;

    private final Map<String, GrayReleaseRule> grayRuleCache = new ConcurrentHashMap<>();
    private final Map<String, TrafficMirrorRule> mirrorRuleCache = new ConcurrentHashMap<>();

    public GrayReleaseRule getGrayRuleByRouteId(String routeId) {
        return grayReleaseRuleMapper.selectOne(new LambdaQueryWrapper<GrayReleaseRule>()
                .eq(GrayReleaseRule::getRouteId, routeId));
    }

    public GrayReleaseRule createGrayRule(GrayReleaseRule rule) {
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        if (rule.getEnabled() == null) {
            rule.setEnabled(true);
        }
        grayReleaseRuleMapper.insert(rule);
        if (rule.getEnabled()) {
            grayRuleCache.put(rule.getRouteId(), rule);
        }
        return rule;
    }

    public GrayReleaseRule updateGrayRule(GrayReleaseRule rule) {
        rule.setUpdatedAt(LocalDateTime.now());
        grayReleaseRuleMapper.updateById(rule);
        GrayReleaseRule updated = grayReleaseRuleMapper.selectById(rule.getId());
        if (updated.getEnabled()) {
            grayRuleCache.put(updated.getRouteId(), updated);
        } else {
            grayRuleCache.remove(updated.getRouteId());
        }
        return updated;
    }

    public void deleteGrayRule(Long id) {
        GrayReleaseRule rule = grayReleaseRuleMapper.selectById(id);
        if (rule != null) {
            grayReleaseRuleMapper.deleteById(id);
            grayRuleCache.remove(rule.getRouteId());
        }
    }

    public TrafficMirrorRule createMirrorRule(TrafficMirrorRule rule) {
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        if (rule.getEnabled() == null) {
            rule.setEnabled(true);
        }
        trafficMirrorRuleMapper.insert(rule);
        if (rule.getEnabled()) {
            mirrorRuleCache.put(rule.getRouteId(), rule);
        }
        return rule;
    }

    public TrafficMirrorRule updateMirrorRule(TrafficMirrorRule rule) {
        rule.setUpdatedAt(LocalDateTime.now());
        trafficMirrorRuleMapper.updateById(rule);
        TrafficMirrorRule updated = trafficMirrorRuleMapper.selectById(rule.getId());
        if (updated.getEnabled()) {
            mirrorRuleCache.put(updated.getRouteId(), updated);
        } else {
            mirrorRuleCache.remove(updated.getRouteId());
        }
        return updated;
    }

    public void deleteMirrorRule(Long id) {
        TrafficMirrorRule rule = trafficMirrorRuleMapper.selectById(id);
        if (rule != null) {
            trafficMirrorRuleMapper.deleteById(id);
            mirrorRuleCache.remove(rule.getRouteId());
        }
    }

    public void loadAllActive() {
        List<GrayReleaseRule> grayRules = grayReleaseRuleMapper.selectList(new LambdaQueryWrapper<GrayReleaseRule>()
                .eq(GrayReleaseRule::getEnabled, true));
        for (GrayReleaseRule rule : grayRules) {
            grayRuleCache.put(rule.getRouteId(), rule);
        }
        List<TrafficMirrorRule> mirrorRules = trafficMirrorRuleMapper.selectList(new LambdaQueryWrapper<TrafficMirrorRule>()
                .eq(TrafficMirrorRule::getEnabled, true));
        for (TrafficMirrorRule rule : mirrorRules) {
            mirrorRuleCache.put(rule.getRouteId(), rule);
        }
        log.info("Loaded {} gray rules and {} mirror rules", grayRules.size(), mirrorRules.size());
    }
}
