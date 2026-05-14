package com.datamigrate.expression;

import com.datamigrate.entity.MappingRule;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class RuleExpressionParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String PREFIX_EXPRESSION = "expr:";
    private static final String PREFIX_JSON = "json:";
    private static final String PREFIX_CONDITION = "if:";
    private static final String PREFIX_DEFAULT = "default:";

    public List<ParsedRule> parseRules(List<MappingRule> rules) {
        List<ParsedRule> parsedRules = new ArrayList<>();
        
        if (rules == null || rules.isEmpty()) {
            return parsedRules;
        }

        for (MappingRule rule : rules) {
            ParsedRule parsed = parseSingleRule(rule);
            if (parsed != null) {
                parsedRules.add(parsed);
            }
        }

        parsedRules.sort(Comparator.comparingInt(ParsedRule::getOrder));
        return parsedRules;
    }

    public ParsedRule parseSingleRule(MappingRule rule) {
        if (rule == null) {
            return null;
        }

        ParsedRule parsed = new ParsedRule();
        parsed.setSourceField(rule.getSourceField());
        parsed.setTargetField(rule.getTargetField());
        parsed.setOrder(rule.getRuleOrder() != null ? rule.getRuleOrder() : 0);

        String transformation = rule.getTransformation();
        if (transformation != null && !transformation.trim().isEmpty()) {
            parseTransformation(parsed, transformation);
        }

        if (rule.getSourceField() != null && rule.getSourceField().startsWith(PREFIX_EXPRESSION)) {
            parsed.setHasExpression(true);
            parsed.setExpression(rule.getSourceField().substring(PREFIX_EXPRESSION.length()));
        }

        return parsed;
    }

    private void parseTransformation(ParsedRule parsed, String transformation) {
        String[] parts = transformation.split("\\|");
        
        for (String part : parts) {
            String trimmed = part.trim();
            
            if (trimmed.startsWith(PREFIX_CONDITION)) {
                parsed.setCondition(trimmed.substring(PREFIX_CONDITION.length()));
                parsed.setHasCondition(true);
            } else if (trimmed.startsWith(PREFIX_DEFAULT)) {
                parsed.setDefaultValue(trimmed.substring(PREFIX_DEFAULT.length()));
                parsed.setHasDefaultValue(true);
            } else if (trimmed.startsWith(PREFIX_JSON)) {
                parseJsonTransformation(parsed, trimmed.substring(PREFIX_JSON.length()));
            } else {
                parsed.getTransformations().add(trimmed);
            }
        }
    }

    private void parseJsonTransformation(ParsedRule parsed, String json) {
        try {
            Map<String, Object> config = objectMapper.readValue(json, 
                new TypeReference<Map<String, Object>>() {});
            
            if (config.containsKey("valueMapping")) {
                Object valueMapping = config.get("valueMapping");
                if (valueMapping instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> mapping = (Map<String, String>) valueMapping;
                    parsed.setValueMappings(mapping);
                    parsed.setHasValueMapping(true);
                }
            }
            
            if (config.containsKey("concatenate")) {
                parsed.setConcatenateConfig(config.get("concatenate"));
                parsed.setHasConcatenate(true);
            }
            
            if (config.containsKey("format")) {
                parsed.setFormatPattern(String.valueOf(config.get("format")));
                parsed.setHasFormat(true);
            }
            
        } catch (JsonProcessingException e) {
            log.warn("解析JSON转换规则失败: {}", json, e);
        }
    }

    @lombok.Data
    public static class ParsedRule {
        private String sourceField;
        private String targetField;
        private int order;
        
        @lombok.Builder.Default
        private List<String> transformations = new ArrayList<>();
        
        private boolean hasExpression;
        private String expression;
        
        private boolean hasCondition;
        private String condition;
        
        private boolean hasDefaultValue;
        private String defaultValue;
        
        private boolean hasValueMapping;
        private Map<String, String> valueMappings;
        
        private boolean hasConcatenate;
        private Object concatenateConfig;
        
        private boolean hasFormat;
        private String formatPattern;
    }
}
