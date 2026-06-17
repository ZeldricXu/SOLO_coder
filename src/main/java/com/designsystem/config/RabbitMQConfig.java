package com.designsystem.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {
    public static final String EXCHANGE_DESIGN_SYSTEM = "design.system.exchange";

    public static final String QUEUE_COMPONENT_PUBLISH = "design.system.component.publish";
    public static final String QUEUE_TOKEN_CHANGE = "design.system.token.change";
    public static final String QUEUE_DOC_INDEX = "design.system.document.index";
    public static final String QUEUE_CODE_GENERATE = "design.system.code.generate";
    public static final String QUEUE_NOTIFICATION = "design.system.notification";
    public static final String QUEUE_CHANGELOG_GENERATE = "design.system.changelog.generate";

    public static final String ROUTING_KEY_COMPONENT_PUBLISH = "component.publish";
    public static final String ROUTING_KEY_TOKEN_CHANGE = "token.change";
    public static final String ROUTING_KEY_DOC_INDEX = "document.index";
    public static final String ROUTING_KEY_CODE_GENERATE = "code.generate";
    public static final String ROUTING_KEY_NOTIFICATION = "notification";
    public static final String ROUTING_KEY_CHANGELOG_GENERATE = "changelog.generate";

    @Bean
    public TopicExchange designSystemExchange() {
        return new TopicExchange(EXCHANGE_DESIGN_SYSTEM, true, false);
    }

    @Bean
    public Queue componentPublishQueue() {
        return new Queue(QUEUE_COMPONENT_PUBLISH, true);
    }

    @Bean
    public Queue tokenChangeQueue() {
        return new Queue(QUEUE_TOKEN_CHANGE, true);
    }

    @Bean
    public Queue documentIndexQueue() {
        return new Queue(QUEUE_DOC_INDEX, true);
    }

    @Bean
    public Queue codeGenerateQueue() {
        return new Queue(QUEUE_CODE_GENERATE, true);
    }

    @Bean
    public Queue notificationQueue() {
        return new Queue(QUEUE_NOTIFICATION, true);
    }

    @Bean
    public Queue changelogGenerateQueue() {
        return new Queue(QUEUE_CHANGELOG_GENERATE, true);
    }

    @Bean
    public Binding componentPublishBinding() {
        return BindingBuilder.bind(componentPublishQueue())
                .to(designSystemExchange())
                .with(ROUTING_KEY_COMPONENT_PUBLISH);
    }

    @Bean
    public Binding tokenChangeBinding() {
        return BindingBuilder.bind(tokenChangeQueue())
                .to(designSystemExchange())
                .with(ROUTING_KEY_TOKEN_CHANGE);
    }

    @Bean
    public Binding documentIndexBinding() {
        return BindingBuilder.bind(documentIndexQueue())
                .to(designSystemExchange())
                .with(ROUTING_KEY_DOC_INDEX);
    }

    @Bean
    public Binding codeGenerateBinding() {
        return BindingBuilder.bind(codeGenerateQueue())
                .to(designSystemExchange())
                .with(ROUTING_KEY_CODE_GENERATE);
    }

    @Bean
    public Binding notificationBinding() {
        return BindingBuilder.bind(notificationQueue())
                .to(designSystemExchange())
                .with(ROUTING_KEY_NOTIFICATION);
    }

    @Bean
    public Binding changelogGenerateBinding() {
        return BindingBuilder.bind(changelogGenerateQueue())
                .to(designSystemExchange())
                .with(ROUTING_KEY_CHANGELOG_GENERATE);
    }
}
