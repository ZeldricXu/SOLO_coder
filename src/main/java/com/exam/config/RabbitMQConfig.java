package com.exam.config;

import com.exam.common.Constants;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Bean
    public Queue gradingQueue() {
        return QueueBuilder.durable(Constants.MQ_QUEUE_GRADING)
                .deadLetterExchange("exam.dead.exchange")
                .deadLetterRoutingKey("exam.dead.grading")
                .build();
    }

    @Bean
    public DirectExchange gradingExchange() {
        return new DirectExchange(Constants.MQ_EXCHANGE_GRADING, true, false);
    }

    @Bean
    public Binding gradingBinding(Queue gradingQueue, DirectExchange gradingExchange) {
        return BindingBuilder.bind(gradingQueue)
                .to(gradingExchange)
                .with(Constants.MQ_ROUTING_KEY_GRADING);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable("exam.dead.queue").build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange("exam.dead.exchange", true, false);
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue)
                .to(deadLetterExchange)
                .with("exam.dead.grading");
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                System.err.println("消息发送失败: " + cause);
            }
        });
        rabbitTemplate.setReturnsCallback(returned -> {
            System.err.println("消息被退回: " + returned.getMessage());
        });
        return rabbitTemplate;
    }
}
