package com.cdcsync.quality.scheduler;

import com.cdcsync.quality.domain.QualityRule;
import com.cdcsync.quality.service.QualityRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class QualityScheduler {

    private final QualityRuleService qualityRuleService;

    @Scheduled(cron = "0 0 * * * *")
    public void executeScheduledRules() {
        log.info("Starting scheduled quality rule execution...");
        LocalDateTime now = LocalDateTime.now();

        List<QualityRule> rules = qualityRuleService.findAll();
        int executedCount = 0;

        for (QualityRule rule : rules) {
            try {
                if (shouldExecute(rule, now)) {
                    qualityRuleService.executeRule(rule.getId());
                    executedCount++;
                }
            } catch (Exception e) {
                log.error("Failed to execute scheduled rule: {}", rule.getName(), e);
            }
        }

        log.info("Scheduled quality rule execution completed: total={}, executed={}", rules.size(), executedCount);
    }

    private boolean shouldExecute(QualityRule rule, LocalDateTime now) {
        if (rule.getEnabled() == null || rule.getEnabled() != 1) {
            return false;
        }

        if (rule.getScheduleCron() == null || rule.getScheduleCron().isEmpty()) {
            return false;
        }

        try {
            CronExpression cron = CronExpression.parse(rule.getScheduleCron());
            LocalDateTime nextExecution = cron.next(now);
            if (nextExecution == null) {
                return false;
            }

            LocalDateTime lastCheck = rule.getLastCheckAt();
            if (lastCheck == null) {
                return true;
            }

            LocalDateTime lastScheduled = cron.next(lastCheck);
            return lastScheduled != null && !lastScheduled.isAfter(now);
        } catch (Exception e) {
            log.warn("Invalid cron expression for rule {}: {}", rule.getName(), rule.getScheduleCron());
            return false;
        }
    }
}
