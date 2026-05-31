package com.monitoring.alert.config;

import com.monitoring.alert.notification.NotificationChannel;
import com.monitoring.alert.service.AlertEvaluationService;
import com.monitoring.alert.parser.AlertRuleParser;
import com.monitoring.dal.repository.AlertHistoryRepository;
import com.monitoring.dal.repository.AlertRuleRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

@Configuration
@ComponentScan(basePackages = "com.monitoring.alert")
@EnableScheduling
public class AlertAutoConfiguration {

    @Bean
    public AlertEvaluationService alertEvaluationService(
            AlertRuleParser ruleParser,
            AlertRuleRepository alertRuleRepository,
            AlertHistoryRepository alertHistoryRepository,
            List<NotificationChannel> notificationChannels) {
        AlertEvaluationService service = new AlertEvaluationService(
                ruleParser, alertRuleRepository, alertHistoryRepository);
        notificationChannels.forEach(service::registerNotificationChannel);
        return service;
    }
}
