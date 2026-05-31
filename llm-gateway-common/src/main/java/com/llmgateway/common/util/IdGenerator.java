package com.llmgateway.common.util;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;

public class IdGenerator {

    public static String generateId(String prefix) {
        return prefix + "_" + IdUtil.fastSimpleUUID().substring(0, 16);
    }

    public static String generateTraceId() {
        return IdUtil.fastSimpleUUID();
    }

    public static String generateFeatureId() {
        return generateId("feat");
    }

    public static String generateModelId() {
        return generateId("model");
    }

    public static String generateVersionId() {
        return generateId("ver");
    }

    public static String generateDocumentId() {
        return generateId("doc");
    }

    public static String generateTaskId() {
        return generateId("task");
    }

    public static String generateExperimentId() {
        return generateId("exp");
    }

    public static String generatePromptId() {
        return generateId("prompt");
    }

    public static boolean isValidId(String id, String prefix) {
        return StrUtil.isNotBlank(id) && id.startsWith(prefix + "_");
    }
}
