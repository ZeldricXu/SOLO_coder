package com.projmanage.service;

import com.projmanage.config.Constants;
import com.projmanage.model.MilestoneReminderConfig;
import com.projmanage.repository.MilestoneReminderConfigRepository;
import com.projmanage.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
public class MilestoneReminderConfigService {

    private final MilestoneReminderConfigRepository configRepository;

    public MilestoneReminderConfigService(MilestoneReminderConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    @Transactional
    public MilestoneReminderConfig createDefaultConfig(String projectId, String milestoneId) {
        MilestoneReminderConfig config = new MilestoneReminderConfig();
        config.setConfigId(IdGenerator.generateProjectId());
        config.setProjectId(projectId);
        config.setMilestoneId(milestoneId);
        config.setReminderDaysBefore(Constants.DEFAULT_REMINDER_DAYS_BEFORE);
        config.setReminderDaysList(Arrays.asList(7, 3, 1));
        config.setEnableMultipleReminders(true);
        config.setReminderIntervalHours(Constants.DEFAULT_REMINDER_INTERVAL_HOURS);
        config.setMaxReminderCount(Constants.DEFAULT_MAX_REMINDER_COUNT);
        config.setCurrentReminderCount(0);
        config.setLastReminderTime(null);
        config.setActive(true);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());

        return configRepository.save(config);
    }

    @Transactional
    public MilestoneReminderConfig createCustomConfig(String projectId, String milestoneId,
                                                      Integer reminderDaysBefore,
                                                      List<Integer> reminderDaysList,
                                                      Boolean enableMultipleReminders,
                                                      Integer reminderIntervalHours,
                                                      Integer maxReminderCount) {
        MilestoneReminderConfig config = new MilestoneReminderConfig();
        config.setConfigId(IdGenerator.generateProjectId());
        config.setProjectId(projectId);
        config.setMilestoneId(milestoneId);
        config.setReminderDaysBefore(reminderDaysBefore != null ? reminderDaysBefore : Constants.DEFAULT_REMINDER_DAYS_BEFORE);
        config.setReminderDaysList(reminderDaysList != null ? reminderDaysList : Arrays.asList(7, 3, 1));
        config.setEnableMultipleReminders(enableMultipleReminders != null ? enableMultipleReminders : true);
        config.setReminderIntervalHours(reminderIntervalHours != null ? reminderIntervalHours : Constants.DEFAULT_REMINDER_INTERVAL_HOURS);
        config.setMaxReminderCount(maxReminderCount != null ? maxReminderCount : Constants.DEFAULT_MAX_REMINDER_COUNT);
        config.setCurrentReminderCount(0);
        config.setLastReminderTime(null);
        config.setActive(true);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());

        return configRepository.save(config);
    }

    public Optional<MilestoneReminderConfig> getConfigByMilestoneId(String milestoneId) {
        return configRepository.findByMilestoneId(milestoneId);
    }

    public List<MilestoneReminderConfig> getConfigsByProjectId(String projectId) {
        return configRepository.findByProjectId(projectId);
    }

    public List<MilestoneReminderConfig> getActiveConfigsByProjectId(String projectId) {
        return configRepository.findByProjectIdAndIsActive(projectId, true);
    }

    @Transactional
    public MilestoneReminderConfig updateConfig(String configId,
                                                 Integer reminderDaysBefore,
                                                 List<Integer> reminderDaysList,
                                                 Boolean enableMultipleReminders,
                                                 Integer reminderIntervalHours,
                                                 Integer maxReminderCount,
                                                 Boolean isActive) {
        Optional<MilestoneReminderConfig> existingOpt = configRepository.findById(configId);
        if (!existingOpt.isPresent()) {
            return null;
        }

        MilestoneReminderConfig config = existingOpt.get();

        if (reminderDaysBefore != null) {
            config.setReminderDaysBefore(reminderDaysBefore);
        }
        if (reminderDaysList != null) {
            config.setReminderDaysList(reminderDaysList);
        }
        if (enableMultipleReminders != null) {
            config.setEnableMultipleReminders(enableMultipleReminders);
        }
        if (reminderIntervalHours != null) {
            config.setReminderIntervalHours(reminderIntervalHours);
        }
        if (maxReminderCount != null) {
            config.setMaxReminderCount(maxReminderCount);
        }
        if (isActive != null) {
            config.setActive(isActive);
        }

        config.setUpdatedAt(LocalDateTime.now());
        return configRepository.save(config);
    }

    @Transactional
    public void incrementReminderCount(String milestoneId) {
        Optional<MilestoneReminderConfig> configOpt = configRepository.findByMilestoneId(milestoneId);
        if (configOpt.isPresent()) {
            MilestoneReminderConfig config = configOpt.get();
            config.setCurrentReminderCount(config.getCurrentReminderCount() + 1);
            config.setLastReminderTime(LocalDateTime.now());
            config.setUpdatedAt(LocalDateTime.now());
            configRepository.save(config);
        }
    }

    public boolean shouldSendReminder(String milestoneId, long daysUntil) {
        Optional<MilestoneReminderConfig> configOpt = configRepository.findByMilestoneId(milestoneId);
        if (!configOpt.isPresent()) {
            return daysUntil <= 3 && daysUntil >= 0;
        }

        MilestoneReminderConfig config = configOpt.get();

        if (!config.getActive()) {
            return false;
        }

        if (config.getCurrentReminderCount() >= config.getMaxReminderCount()) {
            return false;
        }

        if (!config.getEnableMultipleReminders() && config.getLastReminderTime() != null) {
            return false;
        }

        List<Integer> reminderDaysList = config.getReminderDaysList();
        if (reminderDaysList != null && !reminderDaysList.isEmpty()) {
            for (Integer day : reminderDaysList) {
                if (daysUntil == day) {
                    return true;
                }
            }
            return false;
        }

        return daysUntil <= config.getReminderDaysBefore() && daysUntil >= 0;
    }

    public List<Integer> getUpcomingReminderDays(String milestoneId, long daysUntil) {
        List<Integer> result = new ArrayList<>();
        Optional<MilestoneReminderConfig> configOpt = configRepository.findByMilestoneId(milestoneId);
        if (!configOpt.isPresent()) {
            if (daysUntil <= 3 && daysUntil >= 0) {
                result.add((int) daysUntil);
            }
            return result;
        }

        MilestoneReminderConfig config = configOpt.get();
        if (!config.getActive()) {
            return result;
        }

        if (config.getCurrentReminderCount() >= config.getMaxReminderCount()) {
            return result;
        }

        List<Integer> reminderDaysList = config.getReminderDaysList();
        if (reminderDaysList != null && !reminderDaysList.isEmpty()) {
            for (Integer day : reminderDaysList) {
                if (day >= 0 && day <= daysUntil) {
                    result.add(day);
                }
            }
        } else {
            for (int i = 0; i <= config.getReminderDaysBefore(); i++) {
                result.add(i);
            }
        }

        return result;
    }

    @Transactional
    public void deleteConfig(String configId) {
        configRepository.deleteById(configId);
    }

    @Transactional
    public void resetReminderCount(String milestoneId) {
        Optional<MilestoneReminderConfig> configOpt = configRepository.findByMilestoneId(milestoneId);
        if (configOpt.isPresent()) {
            MilestoneReminderConfig config = configOpt.get();
            config.setCurrentReminderCount(0);
            config.setLastReminderTime(null);
            config.setUpdatedAt(LocalDateTime.now());
            configRepository.save(config);
        }
    }
}
