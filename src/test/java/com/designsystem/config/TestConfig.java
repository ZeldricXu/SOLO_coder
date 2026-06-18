package com.designsystem.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@MockBean(classes = {
        org.springframework.amqp.rabbit.core.RabbitTemplate.class,
        org.springframework.data.redis.core.RedisTemplate.class,
        co.elastic.clients.elasticsearch.ElasticsearchClient.class,
        io.minio.MinioClient.class
})
@TestConfiguration
public class TestConfig {
}
