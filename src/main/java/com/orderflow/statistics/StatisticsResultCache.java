package com.orderflow.statistics;

import com.alibaba.fastjson.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class StatisticsResultCache {

    private static final Logger logger = LoggerFactory.getLogger(StatisticsResultCache.class);

    private static final String TASK_INFO_PREFIX = "statistics:task:info:";
    private static final String TASK_RESULT_PREFIX = "statistics:task:result:";
    private static final long CACHE_EXPIRE_HOURS = 6;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    public void saveTaskInfo(StatisticsTaskInfo taskInfo) {
        String key = TASK_INFO_PREFIX + taskInfo.getTaskId();
        String value = JSON.toJSONString(taskInfo);
        redisTemplate.opsForValue().set(key, value, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
        logger.debug("缓存统计任务信息，任务ID: {}", taskInfo.getTaskId());
    }

    public StatisticsTaskInfo getTaskInfo(String taskId) {
        String key = TASK_INFO_PREFIX + taskId;
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        try {
            return JSON.parseObject(value, StatisticsTaskInfo.class);
        } catch (Exception e) {
            logger.warn("解析统计任务信息失败，任务ID: {}", taskId, e);
            return null;
        }
    }

    public void saveTaskResult(StatisticsTaskResult taskResult) {
        String key = TASK_RESULT_PREFIX + taskResult.getTaskId();
        String value = JSON.toJSONString(taskResult);
        redisTemplate.opsForValue().set(key, value, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
        logger.debug("缓存统计任务结果，任务ID: {}", taskResult.getTaskId());
    }

    public StatisticsTaskResult getTaskResult(String taskId) {
        String key = TASK_RESULT_PREFIX + taskId;
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        try {
            return JSON.parseObject(value, StatisticsTaskResult.class);
        } catch (Exception e) {
            logger.warn("解析统计任务结果失败，任务ID: {}", taskId, e);
            return null;
        }
    }
}
