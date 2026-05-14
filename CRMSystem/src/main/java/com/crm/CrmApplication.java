package com.crm;

import com.crm.config.CategoryQueueProperties;
import com.crm.config.CustomerTypeProperties;
import com.crm.config.OpportunityAlertProperties;
import com.crm.config.ReminderTimeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties({
    ReminderTimeProperties.class,
    OpportunityAlertProperties.class,
    CustomerTypeProperties.class,
    CategoryQueueProperties.class
})
public class CrmApplication {
    public static void main(String[] args) {
        SpringApplication.run(CrmApplication.class, args);
    }
}
