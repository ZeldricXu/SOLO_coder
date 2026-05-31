package com.modelguard.util;

import cn.hutool.core.util.IdUtil;

public final class IdGeneratorUtil {

    private IdGeneratorUtil() {
    }

    public static String generatePromptId() {
        return "prompt_" + IdUtil.simpleUUID();
    }

    public static String generateExperimentId() {
        return "exp_" + IdUtil.simpleUUID();
    }

    public static String generatePipelineId() {
        return "pipe_" + IdUtil.simpleUUID();
    }

    public static String generateDocumentTaskId() {
        return "doctask_" + IdUtil.simpleUUID();
    }

    public static String generateChunkId() {
        return "chunk_" + IdUtil.simpleUUID();
    }

    public static String generateGpuNodeId() {
        return "gpunode_" + IdUtil.simpleUUID();
    }

    public static String generateGpuTaskId() {
        return "gputask_" + IdUtil.simpleUUID();
    }

    public static String generateSimpleId() {
        return IdUtil.simpleUUID();
    }

    public static long generateSnowflakeId() {
        return IdUtil.getSnowflakeNextId();
    }
}
