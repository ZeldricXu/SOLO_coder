package com.enterprise.risk.engine.config;

import com.enterprise.risk.engine.parser.RuleExpressionCompiler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 风险引擎Spring配置
 * 配置异步执行器、表达式编译器等核心组件
 */
@Configuration
@EnableAsync
public class RiskEngineConfig {

    /**
     * 规则表达式编译器Bean
     */
    @Bean
    public RuleExpressionCompiler ruleExpressionCompiler() {
        return new RuleExpressionCompiler();
    }

    /**
     * 规则异步执行线程池
     * 用于异步写入命中日志等操作
     */
    @Bean(name = "ruleAsyncExecutor")
    public Executor ruleAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("rule-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
