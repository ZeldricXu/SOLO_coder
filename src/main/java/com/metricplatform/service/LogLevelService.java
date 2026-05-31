package com.metricplatform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.dto.LogLevelDTO;
import com.metricplatform.entity.SysLogLevel;
import com.metricplatform.mapper.SysLogLevelMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricplatform.datasource.ReadOnly;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogLevelService extends ServiceImpl<SysLogLevelMapper, SysLogLevel> {

    private final Map<String, String> originalLogLevels = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("初始化动态日志级别模块...");
        loadEffectiveLogLevels();
    }

    public void loadEffectiveLogLevels() {
        List<SysLogLevel> effectiveLevels = this.list(new LambdaQueryWrapper<SysLogLevel>()
                .eq(SysLogLevel::getEffective, true)
                .and(w -> w.isNull(SysLogLevel::getExpireAt)
                        .or()
                        .gt(SysLogLevel::getExpireAt, LocalDateTime.now())));

        for (SysLogLevel config : effectiveLevels) {
            try {
                applyLogLevel(config.getLoggerName(), config.getLevel());
                log.info("已应用日志级别配置: {} -> {}", config.getLoggerName(), config.getLevel());
            } catch (Exception e) {
                log.error("应用日志级别配置失败: {} -> {}", config.getLoggerName(), config.getLevel(), e);
            }
        }
        log.info("动态日志级别模块初始化完成，共加载 {} 条配置", effectiveLevels.size());
    }

    @Scheduled(fixedRate = 60000)
    public void checkExpiredLogLevels() {
        LocalDateTime now = LocalDateTime.now();
        List<SysLogLevel> expiredLevels = this.list(new LambdaQueryWrapper<SysLogLevel>()
                .eq(SysLogLevel::getEffective, true)
                .isNotNull(SysLogLevel::getExpireAt)
                .lt(SysLogLevel::getExpireAt, now));

        for (SysLogLevel config : expiredLevels) {
            try {
                resetLogLevel(config.getLoggerName());
                config.setEffective(false);
                this.updateById(config);
                log.info("日志级别配置已过期并重置: {} -> {}", config.getLoggerName(), config.getLevel());
            } catch (Exception e) {
                log.error("重置过期日志级别配置失败: {}", config.getLoggerName(), e);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = "logLevels", allEntries = true)
    public SysLogLevel setLogLevel(LogLevelDTO dto) {
        SysLogLevel existing = this.getOne(new LambdaQueryWrapper<SysLogLevel>()
                .eq(SysLogLevel::getLoggerName, dto.getLoggerName()));

        SysLogLevel config;
        if (existing != null) {
            config = existing;
        } else {
            config = new SysLogLevel();
            config.setLoggerName(dto.getLoggerName());
        }

        config.setLevel(dto.getLevel());
        config.setEffective(true);
        config.setExpireAt(dto.getExpireAt());
        config.setCreatedBy(dto.getCreatedBy());

        this.saveOrUpdate(config);
        applyLogLevel(dto.getLoggerName(), dto.getLevel());

        log.info("日志级别已更新: {} -> {}", dto.getLoggerName(), dto.getLevel());
        return config;
    }

    @ReadOnly
    @Cacheable(value = "logLevels", key = "#root.methodName")
    public List<SysLogLevel> getAllLogLevels() {
        return this.list();
    }

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = "logLevels", allEntries = true)
    public boolean resetLogLevel(String loggerName) {
        SysLogLevel config = this.getOne(new LambdaQueryWrapper<SysLogLevel>()
                .eq(SysLogLevel::getLoggerName, loggerName));

        if (config != null) {
            config.setEffective(false);
            this.updateById(config);
        }

        boolean result = doResetLogLevel(loggerName);
        log.info("日志级别已重置: {}", loggerName);
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = "logLevels", allEntries = true)
    public void resetAllLogLevels() {
        List<SysLogLevel> configs = this.list(new LambdaQueryWrapper<SysLogLevel>()
                .eq(SysLogLevel::getEffective, true));

        for (SysLogLevel config : configs) {
            try {
                doResetLogLevel(config.getLoggerName());
                config.setEffective(false);
                this.updateById(config);
            } catch (Exception e) {
                log.error("重置日志级别失败: {}", config.getLoggerName(), e);
            }
        }

        originalLogLevels.clear();
        log.info("所有日志级别已重置");
    }

    private void applyLogLevel(String loggerName, String level) {
        Logger logger = LoggerFactory.getLogger(loggerName);

        if (logger instanceof ch.qos.logback.classic.Logger) {
            ch.qos.logback.classic.Logger logbackLogger = (ch.qos.logback.classic.Logger) logger;
            ch.qos.logback.classic.Level originalLevel = logbackLogger.getLevel();

            if (!originalLogLevels.containsKey(loggerName)) {
                originalLogLevels.put(loggerName, originalLevel != null ? originalLevel.levelStr : "INFO");
            }

            ch.qos.logback.classic.Level newLevel = ch.qos.logback.classic.Level.toLevel(level);
            logbackLogger.setLevel(newLevel);
        } else if (logger instanceof org.apache.logging.log4j.core.Logger) {
            org.apache.logging.log4j.core.Logger log4jLogger = (org.apache.logging.log4j.core.Logger) logger;
            org.apache.logging.log4j.Level originalLevel = log4jLogger.getLevel();

            if (!originalLogLevels.containsKey(loggerName)) {
                originalLogLevels.put(loggerName, originalLevel != null ? originalLevel.name() : "INFO");
            }

            org.apache.logging.log4j.Level newLevel = org.apache.logging.log4j.Level.toLevel(level);
            log4jLogger.setLevel(newLevel);
        } else {
            throw new UnsupportedOperationException("不支持的日志框架: " + logger.getClass().getName());
        }
    }

    private boolean doResetLogLevel(String loggerName) {
        Logger logger = LoggerFactory.getLogger(loggerName);
        String originalLevel = originalLogLevels.getOrDefault(loggerName, "INFO");

        if (logger instanceof ch.qos.logback.classic.Logger) {
            ch.qos.logback.classic.Logger logbackLogger = (ch.qos.logback.classic.Logger) logger;
            logbackLogger.setLevel(ch.qos.logback.classic.Level.toLevel(originalLevel));
            originalLogLevels.remove(loggerName);
            return true;
        } else if (logger instanceof org.apache.logging.log4j.core.Logger) {
            org.apache.logging.log4j.core.Logger log4jLogger = (org.apache.logging.log4j.core.Logger) logger;
            log4jLogger.setLevel(org.apache.logging.log4j.Level.toLevel(originalLevel));
            originalLogLevels.remove(loggerName);
            return true;
        }

        return false;
    }

    @ReadOnly
    public String getCurrentLogLevel(String loggerName) {
        Logger logger = LoggerFactory.getLogger(loggerName);

        if (logger instanceof ch.qos.logback.classic.Logger) {
            ch.qos.logback.classic.Level level = ((ch.qos.logback.classic.Logger) logger).getLevel();
            return level != null ? level.levelStr : "INFO";
        } else if (logger instanceof org.apache.logging.log4j.core.Logger) {
            org.apache.logging.log4j.Level level = ((org.apache.logging.log4j.core.Logger) logger).getLevel();
            return level != null ? level.name() : "INFO";
        }

        return "INFO";
    }
}
