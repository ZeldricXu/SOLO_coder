package com.datastandard.modules.core;

import cn.hutool.core.util.StrUtil;
import com.datastandard.modules.core.dto.StandardizationConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Service
public class DataCleaningService {

    private final MeterRegistry meterRegistry;

    private final Counter cleanSuccessCounter;
    private final Counter cleanFailureCounter;
    private final Counter nullRemovalCounter;
    private final Counter duplicateRemovalCounter;
    private final Counter formatNormalizationCounter;

    public DataCleaningService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.cleanSuccessCounter = Counter.builder("data.cleaning.success")
                .description("数据清洗成功次数")
                .register(meterRegistry);
        this.cleanFailureCounter = Counter.builder("data.cleaning.failure")
                .description("数据清洗失败次数")
                .register(meterRegistry);
        this.nullRemovalCounter = Counter.builder("data.cleaning.null.removal")
                .description("空值移除次数")
                .register(meterRegistry);
        this.duplicateRemovalCounter = Counter.builder("data.cleaning.duplicate.removal")
                .description("重复值移除次数")
                .register(meterRegistry);
        this.formatNormalizationCounter = Counter.builder("data.cleaning.format.normalization")
                .description("格式标准化次数")
                .register(meterRegistry);
    }

    public Mono<Map<String, Object>> clean(Map<String, Object> record, StandardizationConfig.FieldRule rule, ProcessingContext context) {
        return Mono.fromCallable(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                String fieldName = rule.getSourceField();
                Object value = record.get(fieldName);

                if (value == null) {
                    nullRemovalCounter.increment();
                    if (rule.isRequired()) {
                        context.addError(fieldName, null, "NULL_VALUE",
                                "必填字段 '" + fieldName + "' 为空值");
                    }
                    cleanSuccessCounter.increment();
                    return record;
                }

                if (value instanceof String strValue) {
                    String cleaned = cleanString(strValue, rule);
                    if (StrUtil.isBlank(cleaned) && rule.isRequired()) {
                        context.addError(fieldName, strValue, "EMPTY_STRING",
                                "必填字段 '" + fieldName + "' 为空字符串");
                    }
                    record.put(rule.getTargetField(), cleaned);
                    formatNormalizationCounter.increment();
                }

                if (shouldRemoveDuplicate(value, rule)) {
                    duplicateRemovalCounter.increment();
                    context.addError(fieldName, String.valueOf(value), "DUPLICATE_VALUE",
                            "字段 '" + fieldName + "' 存在重复值");
                }

                cleanSuccessCounter.increment();
                return record;
            } catch (Exception e) {
                log.error("数据清洗失败: field={}", rule.getSourceField(), e);
                cleanFailureCounter.increment();
                context.addError(rule.getSourceField(), String.valueOf(record.get(rule.getSourceField())),
                        "CLEANING_ERROR", "数据清洗失败: " + e.getMessage());
                return record;
            } finally {
                sample.stop(meterRegistry.timer("data.cleaning.duration",
                        "field", rule.getSourceField()));
            }
        });
    }

    private String cleanString(String value, StandardizationConfig.FieldRule rule) {
        if (value == null) {
            return null;
        }

        String cleaned = value;

        if (rule.isTrim()) {
            cleaned = cleaned.trim();
        }

        cleaned = removeControlCharacters(cleaned);

        cleaned = normalizeWhitespace(cleaned);

        cleaned = removeInvisibleCharacters(cleaned);

        if (rule.getParams() != null && Boolean.TRUE.equals(rule.getParams().get("removeHtml"))) {
            cleaned = removeHtmlTags(cleaned);
        }

        if (rule.getParams() != null && Boolean.TRUE.equals(rule.getParams().get("asciiOnly"))) {
            cleaned = keepAsciiOnly(cleaned);
        }

        return cleaned;
    }

    private String removeControlCharacters(String value) {
        return value.replaceAll("[\\p{Cntrl}&&[^\n\t\r]]", "");
    }

    private String normalizeWhitespace(String value) {
        return value.replaceAll("\\s+", " ");
    }

    private String removeInvisibleCharacters(String value) {
        return value.replaceAll("[\\p{So}\\p{Mn}]", "");
    }

    private String removeHtmlTags(String value) {
        return value.replaceAll("<[^>]*>", "");
    }

    private String keepAsciiOnly(String value) {
        return value.replaceAll("[^\\x00-\\x7F]", "");
    }

    private boolean shouldRemoveDuplicate(Object value, StandardizationConfig.FieldRule rule) {
        if (rule.getParams() == null) {
            return false;
        }
        Boolean dedup = (Boolean) rule.getParams().get("deduplicate");
        if (dedup == null || !dedup) {
            return false;
        }
        return false;
    }

    public Mono<Map<String, Object>> cleanAll(Map<String, Object> record, ProcessingContext context) {
        return Mono.fromCallable(() -> {
            StandardizationConfig config = context.getConfig();
            if (config == null || !config.isEnableDataCleaning()) {
                return record;
            }

            for (StandardizationConfig.FieldRule rule : config.getFieldRules()) {
                clean(record, rule, context).subscribe();
            }
            return record;
        });
    }
}
