package com.solocoder.dns.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solocoder.dns.common.entity.RunInstance;
import com.solocoder.dns.persistence.entity.RunInstancePO;
import com.solocoder.dns.persistence.mapper.RunInstanceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class RunInstanceRepository {
    private final RunInstanceMapper mapper;

    public RunInstance save(RunInstance instance) {
        RunInstancePO po = toPO(instance);
        mapper.insert(po);
        return instance;
    }

    public RunInstance update(RunInstance instance) {
        RunInstancePO po = toPO(instance);
        mapper.updateById(po);
        return instance;
    }

    public Optional<RunInstance> findById(String runId) {
        return Optional.ofNullable(mapper.selectById(runId)).map(this::toDomain);
    }

    public List<RunInstance> findByEntityId(String entityId) {
        LambdaQueryWrapper<RunInstancePO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RunInstancePO::getEntityId, entityId);
        wrapper.orderByDesc(RunInstancePO::getStartedAt);
        return mapper.selectList(wrapper).stream().map(this::toDomain).collect(Collectors.toList());
    }

    public List<RunInstance> findByPhase(String phase) {
        LambdaQueryWrapper<RunInstancePO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RunInstancePO::getPhase, phase);
        return mapper.selectList(wrapper).stream().map(this::toDomain).collect(Collectors.toList());
    }

    public Page<RunInstance> findAll(int page, int size) {
        Page<RunInstancePO> poPage = mapper.selectPage(new Page<>(page, size), null);
        Page<RunInstance> result = new Page<>(page, size, poPage.getTotal());
        result.setRecords(poPage.getRecords().stream().map(this::toDomain).collect(Collectors.toList()));
        return result;
    }

    private RunInstancePO toPO(RunInstance instance) {
        RunInstancePO po = new RunInstancePO();
        po.setRunId(instance.getRunId());
        po.setEntityId(instance.getEntityId());
        po.setPhase(instance.getPhase());
        po.setProgress(instance.getProgress());
        po.setStartedAt(instance.getStartedAt());
        po.setCompletedAt(instance.getCompletedAt());
        po.setErrorDetail(instance.getErrorDetail());
        return po;
    }

    private RunInstance toDomain(RunInstancePO po) {
        RunInstance instance = new RunInstance();
        instance.setRunId(po.getRunId());
        instance.setEntityId(po.getEntityId());
        instance.setPhase(po.getPhase());
        instance.setProgress(po.getProgress());
        instance.setStartedAt(po.getStartedAt());
        instance.setCompletedAt(po.getCompletedAt());
        instance.setErrorDetail(po.getErrorDetail());
        return instance;
    }
}
