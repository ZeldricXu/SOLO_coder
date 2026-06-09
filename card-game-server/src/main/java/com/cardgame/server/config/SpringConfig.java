package com.cardgame.server.config;

import com.cardgame.common.config.*;
import com.cardgame.netty.dispatcher.MessageDispatcher;
import com.cardgame.netty.dispatcher.MessageHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;

import java.util.Map;

@Configuration
@ComponentScan(basePackages = {
        "com.cardgame.common",
        "com.cardgame.netty",
        "com.cardgame.room",
        "com.cardgame.deck",
        "com.cardgame.battle",
        "com.cardgame.map",
        "com.cardgame.ai",
        "com.cardgame.save",
        "com.cardgame.replay",
        "com.cardgame.rank"
})
@Import({
        com.cardgame.save.config.MyBatisConfig.class,
        com.cardgame.rank.config.RedisRankConfig.class,
        com.cardgame.rank.config.RankMyBatisConfig.class
})
public class SpringConfig {

    @Bean
    public GameConfig gameConfig() {
        return new GameConfig();
    }

    @Bean
    public NettyConfig nettyConfig() {
        return new NettyConfig();
    }

    @Bean
    public RedisConfig redisConfig() {
        return new RedisConfig();
    }

    @Bean
    public MysqlConfig mysqlConfig() {
        return new MysqlConfig();
    }

    @Bean
    public KafkaConfig kafkaConfig() {
        return new KafkaConfig();
    }

    @Bean
    @Autowired
    public MessageDispatcher messageDispatcher(ApplicationContext context) {
        MessageDispatcher dispatcher = new MessageDispatcher();
        Map<String, MessageHandler> handlers = context.getBeansOfType(MessageHandler.class);
        for (MessageHandler handler : handlers.values()) {
            dispatcher.registerHandler(handler);
        }
        return dispatcher;
    }
}
