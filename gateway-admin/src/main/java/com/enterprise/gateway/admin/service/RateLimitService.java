package com.enterprise.gateway.admin.service;

import com.alibaba.nacos.api.config.ConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.gateway.admin.mapper.RateLimitRuleMapper;
import com.enterprise.gateway.common.model.RateLimitRule;
import com.enterprise.gateway.common.util.JacksonUtil;
import com.enterprise.gateway.ratelimit.filter.RateLimitFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RateLimitRuleMapper rateLimitRuleMapper;
    private final RateLimitFilter rateLimitFilter;
    private final ConfigService configService;

    private static final String DATA_ID = "gateway-ratelimit-rules";
    private static final String GROUP_ID = "DEFAULT_GROUP";

    public RateLimitRule getByRouteId(String routeId) {
        return rateLimitRuleMapper.selectOne(new LambdaQueryWrapper<RateLimitRule>()
                .eq(RateLimitRule::getRouteId, routeId));
    }

    public RateLimitRule createRule(RateLimitRule rule) {
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        if (rule.getStatus() == null) {
            rule.setStatus(1);
        }
        rateLimitRuleMapper.insert(rule);
        if (rule.getStatus() == 1) {
            rateLimitFilter.updateRule(rule);
        }
        publishRulesToNacos();
        return rule;
    }

    public RateLimitRule updateRule(RateLimitRule rule) {
        rule.setUpdatedAt(LocalDateTime.now());
        rateLimitRuleMapper.updateById(rule);
        RateLimitRule updated = rateLimitRuleMapper.selectById(rule.getId());
        if (updated.getStatus() == 1) {
            rateLimitFilter.updateRule(updated);
        } else {
            rateLimitFilter.removeRule(updated.getRouteId());
        }
        publishRulesToNacos();
        return updated;
    }

    public void deleteRule(Long id) {
        RateLimitRule rule = rateLimitRuleMapper.selectById(id);
        if (rule != null) {
            rateLimitRuleMapper.deleteById(id);
            rateLimitFilter.removeRule(rule.getRouteId());
            publishRulesToNacos();
        }
    }

    public RateLimitRule toggleRule(Long id) {
        RateLimitRule rule = rateLimitRuleMapper.selectById(id);
        if (rule != null) {
            rule.setStatus(rule.getStatus() == 1 ? 0 : 1);
            rule.setUpdatedAt(LocalDateTime.now());
            rateLimitRuleMapper.updateById(rule);
            if (rule.getStatus() == 1) {
                rateLimitFilter.updateRule(rule);
            } else {
                rateLimitFilter.removeRule(rule.getRouteId());
            }
            publishRulesToNacos();
        }
        return rule;
    }

    public void refreshAll() {
        List<RateLimitRule> rules = rateLimitRuleMapper.selectList(new LambdaQueryWrapper<RateLimitRule>()
                .eq(RateLimitRule::getStatus, 1));
        for (RateLimitRule rule : rules) {
            rateLimitFilter.updateRule(rule);
        }
        publishRulesToNacos();
    }

    private void publishRulesToNacos() {
        try {
            List<RateLimitRule> rules = rateLimitRuleMapper.selectList(new LambdaQueryWrapper<RateLimitRule>()
                    .eq(RateLimitRule::getStatus, 1));
            String config = JacksonUtil.toJson(rules);
            configService.publishConfig(DATA_ID, GROUP_ID, config);
            log.info("Published {} rate limit rules to Nacos", rules.size());
        } catch (Exception e) {
            log.error("Failed to publish rate limit rules to Nacos", e);
        }
    }
}
