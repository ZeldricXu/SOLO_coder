package com.projmanage.aop;

import com.projmanage.model.Task;
import com.projmanage.repository.TaskRepository;
import com.projmanage.service.RiskDetectionQueueService;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Aspect
@Component
public class RiskDetectionAspect {

    private static final Logger logger = LoggerFactory.getLogger(RiskDetectionAspect.class);

    private final RiskDetectionQueueService riskDetectionQueueService;
    private final TaskRepository taskRepository;

    public RiskDetectionAspect(RiskDetectionQueueService riskDetectionQueueService,
                               TaskRepository taskRepository) {
        this.riskDetectionQueueService = riskDetectionQueueService;
        this.taskRepository = taskRepository;
    }

    @Pointcut("execution(* com.projmanage.service.TaskService.createTask(..))")
    public void createTaskPointcut() {}

    @Pointcut("execution(* com.projmanage.service.TaskService.updateTaskProgress(..))")
    public void updateTaskProgressPointcut() {}

    @Pointcut("execution(* com.projmanage.service.TaskService.updateTaskStatus(..))")
    public void updateTaskStatusPointcut() {}

    @AfterReturning(pointcut = "createTaskPointcut()", returning = "result")
    @Async
    public void afterCreateTask(Object result) {
        if (result instanceof String) {
            String taskId = (String) result;
            submitRiskDetectionForTask(taskId);
        }
    }

    @AfterReturning("updateTaskProgressPointcut()")
    @Async
    public void afterUpdateTaskProgress(JoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        if (args.length > 0 && args[0] instanceof String) {
            String taskId = (String) args[0];
            submitRiskDetectionForTask(taskId);
        }
    }

    @AfterReturning("updateTaskStatusPointcut()")
    @Async
    public void afterUpdateTaskStatus(JoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        if (args.length > 0 && args[0] instanceof String) {
            String taskId = (String) args[0];
            submitRiskDetectionForTask(taskId);
        }
    }

    private void submitRiskDetectionForTask(String taskId) {
        try {
            Optional<Task> taskOpt = taskRepository.findById(taskId);
            if (taskOpt.isPresent()) {
                Task task = taskOpt.get();
                riskDetectionQueueService.submitRiskDetectionTask(task);
                logger.debug("风险检测任务已通过AOP切面提交: taskId={}", taskId);
            }
        } catch (Exception e) {
            logger.error("AOP切面提交风险检测任务失败: taskId={}", taskId, e);
        }
    }
}
