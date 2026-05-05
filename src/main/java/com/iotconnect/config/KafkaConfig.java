package com.iotconnect.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public NewTopic deviceDataTopic() {
        return TopicBuilder.name("iot-device-data")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic controlCommandsTopic() {
        return TopicBuilder.name("iot-control-commands")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic alertNotificationsTopic() {
        return TopicBuilder.name("iot-alert-notifications")
                .partitions(2)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic notificationEventsTopic() {
        return TopicBuilder.name("iot-notification-events")
                .partitions(2)
                .replicas(1)
                .build();
    }
}
