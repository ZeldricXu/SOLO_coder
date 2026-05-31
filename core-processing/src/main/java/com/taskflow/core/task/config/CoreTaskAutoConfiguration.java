package com.taskflow.core.task.config;

import com.taskflow.core.task.api.TaskExecutor;
import com.taskflow.core.task.api.TaskRegistry;
import com.taskflow.core.task.api.TaskScheduler;
import com.taskflow.core.task.internal.executor.DefaultTaskExecutor;
import com.taskflow.core.task.internal.registry.DefaultTaskRegistry;
import com.taskflow.core.task.internal.scheduler.DefaultTaskScheduler;
import com.taskflow.core.task.internal.scheduler.TaskScheduleTrigger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 核心任务模块自动配置
 * 对外暴露的Bean都通过接口类型声明，实现依赖倒置
 */
@Configuration
public class CoreTaskAutoConfiguration {

    @Bean
    public TaskRegistry taskRegistry() {
        return new DefaultTaskRegistry();
    }

    @Bean
    public TaskExecutor taskExecutor() {
        return new DefaultTaskExecutor();
    }

    @Bean
    public TaskScheduler taskScheduler() {
        return new DefaultTaskScheduler();
    }

    @Bean
    public TaskScheduleTrigger taskScheduleTrigger() {
        return new TaskScheduleTrigger();
    }
}
