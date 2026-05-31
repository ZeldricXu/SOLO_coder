package com.solocoder.platform.featurestore.service.impl;

import com.solocoder.platform.featurestore.model.ConsistencyReport;
import com.solocoder.platform.featurestore.model.FeatureValue;
import com.solocoder.platform.featurestore.service.ConsistencyValidator;
import com.solocoder.platform.featurestore.service.OfflineFeatureService;
import com.solocoder.platform.featurestore.service.OnlineFeatureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConsistencyValidatorImpl implements ConsistencyValidator {

    private final OnlineFeatureService onlineService;
    private final OfflineFeatureService offlineService;

    @Override
    public ConsistencyReport validate(String featureId) {
        return validate(featureId, List.of());
    }

    @Override
    public ConsistencyReport validate(String featureId, List<String> entityIds) {
        int passed = 0;
        int failed = 0;
        List<ConsistencyReport.ConsistencyViolation> violations = new ArrayList<>();

        for (String entityId : entityIds) {
            var onlineOpt = onlineService.get(featureId, entityId);
            var offlineOpt = offlineService.query(featureId, entityId, System.currentTimeMillis());

            if (onlineOpt.isEmpty() && offlineOpt.isEmpty()) {
                passed++;
                continue;
            }

            if (onlineOpt.isEmpty() || offlineOpt.isEmpty()) {
                failed++;
                violations.add(ConsistencyReport.ConsistencyViolation.builder()
                        .entityId(entityId)
                        .onlineValue(onlineOpt.map(FeatureValue::getValue).orElse(null))
                        .offlineValue(offlineOpt.map(FeatureValue::getValue).orElse(null))
                        .description(onlineOpt.isEmpty() ? "Missing in online store" : "Missing in offline store")
                        .build());
                continue;
            }

            Object onlineVal = onlineOpt.get().getValue();
            Object offlineVal = offlineOpt.get().getValue();
            if (Objects.equals(onlineVal, offlineVal)) {
                passed++;
            } else {
                failed++;
                violations.add(ConsistencyReport.ConsistencyViolation.builder()
                        .entityId(entityId)
                        .onlineValue(onlineVal)
                        .offlineValue(offlineVal)
                        .description("Value mismatch")
                        .build());
            }
        }

        int total = passed + failed;
        double rate = total > 0 ? (double) passed / total * 100 : 100.0;

        log.info("Consistency check: feature={}, total={}, passed={}, failed={}, rate={}%",
                featureId, total, passed, failed, rate);

        return ConsistencyReport.builder()
                .featureId(featureId)
                .totalChecks(total)
                .passedChecks(passed)
                .failedChecks(failed)
                .consistencyRate(rate)
                .violations(violations)
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
