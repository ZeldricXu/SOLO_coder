package com.logmanager.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.logmanager.common.enums.TaskStatus;
import com.logmanager.domain.model.Task;
import com.logmanager.domain.repository.TaskRepository;
import com.logmanager.infrastructure.persistence.entity.TaskPO;
import com.logmanager.infrastructure.persistence.mapper.TaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class TaskRepositoryImpl implements TaskRepository {

    private final TaskMapper mapper;

    @Override
    public Mono<Task> save(Task task) {
        TaskPO po = toPO(task);
        if (po.getId() == null) {
            po.setId(UUID.randomUUID().toString());
        }
        mapper.insert(po);
        return Mono.just(toDomain(po));
    }

    @Override
    public Mono<Task> findById(String taskId) {
        TaskPO po = mapper.selectById(taskId);
        return po != null ? Mono.just(toDomain(po)) : Mono.empty();
    }

    @Override
    public Flux<Task> findByStatus(TaskStatus status) {
        LambdaQueryWrapper<TaskPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TaskPO::getStatus, status.getCode());
        List<TaskPO> pos = mapper.selectList(wrapper);
        return Flux.fromIterable(pos).map(this::toDomain);
    }

    @Override
    public Flux<Task> findByType(String type) {
        LambdaQueryWrapper<TaskPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TaskPO::getType, type);
        List<TaskPO> pos = mapper.selectList(wrapper);
        return Flux.fromIterable(pos).map(this::toDomain);
    }

    @Override
    public Flux<Task> findByScheduledTimeRange(Instant start, Instant end) {
        LambdaQueryWrapper<TaskPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.between(TaskPO::getScheduledAt, start, end);
        List<TaskPO> pos = mapper.selectList(wrapper);
        return Flux.fromIterable(pos).map(this::toDomain);
    }

    @Override
    public Mono<Long> countByStatus(TaskStatus status) {
        LambdaQueryWrapper<TaskPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TaskPO::getStatus, status.getCode());
        Long count = mapper.selectCount(wrapper);
        return Mono.just(count);
    }

    private TaskPO toPO(Task domain) {
        TaskPO po = new TaskPO();
        po.setId(domain.getId());
        po.setTaskId(domain.getTaskId());
        po.setName(domain.getName());
        po.setType(domain.getType());
        po.setStatus(domain.getStatus() != null ? domain.getStatus().getCode() : null);
        po.setParameters(domain.getParameters());
        po.setScheduledBy(domain.getScheduledBy());
        po.setScheduledAt(domain.getScheduledAt());
        po.setStartedAt(domain.getStartedAt());
        po.setCompletedAt(domain.getCompletedAt());
        po.setDurationMs(domain.getDurationMs());
        po.setResult(domain.getResult());
        po.setErrorMessage(domain.getErrorMessage());
        po.setRetryCount(domain.getRetryCount());
        po.setMaxRetries(domain.getMaxRetries());
        po.setAttributes(domain.getAttributes());
        po.setCreatedAt(domain.getCreatedAt());
        po.setUpdatedAt(domain.getUpdatedAt());
        return po;
    }

    private Task toDomain(TaskPO po) {
        Task domain = new Task();
        domain.setId(po.getId());
        domain.setTaskId(po.getTaskId());
        domain.setName(po.getName());
        domain.setType(po.getType());
        domain.setStatus(po.getStatus() != null ? TaskStatus.valueOf(po.getStatus().toUpperCase()) : null);
        domain.setParameters(po.getParameters());
        domain.setScheduledBy(po.getScheduledBy());
        domain.setScheduledAt(po.getScheduledAt());
        domain.setStartedAt(po.getStartedAt());
        domain.setCompletedAt(po.getCompletedAt());
        domain.setDurationMs(po.getDurationMs());
        domain.setResult(po.getResult());
        domain.setErrorMessage(po.getErrorMessage());
        domain.setRetryCount(po.getRetryCount());
        domain.setMaxRetries(po.getMaxRetries());
        domain.setAttributes(po.getAttributes());
        domain.setCreatedAt(po.getCreatedAt());
        domain.setUpdatedAt(po.getUpdatedAt());
        return domain;
    }
}
