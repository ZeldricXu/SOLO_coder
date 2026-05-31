package com.solocoder.dns.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solocoder.dns.common.entity.CoreEntity;
import com.solocoder.dns.common.util.JsonUtils;
import com.solocoder.dns.persistence.entity.CoreEntityPO;
import com.solocoder.dns.persistence.mapper.CoreEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class CoreEntityRepository {
    private final CoreEntityMapper mapper;

    public CoreEntity save(CoreEntity entity) {
        CoreEntityPO po = toPO(entity);
        mapper.insert(po);
        return entity;
    }

    public CoreEntity update(CoreEntity entity) {
        CoreEntityPO po = toPO(entity);
        mapper.updateById(po);
        return entity;
    }

    public Optional<CoreEntity> findById(String id) {
        return Optional.ofNullable(mapper.selectById(id)).map(this::toDomain);
    }

    public List<CoreEntity> findByType(String type) {
        LambdaQueryWrapper<CoreEntityPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CoreEntityPO::getType, type);
        return mapper.selectList(wrapper).stream().map(this::toDomain).collect(Collectors.toList());
    }

    public List<CoreEntity> findByStatus(String status) {
        LambdaQueryWrapper<CoreEntityPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CoreEntityPO::getStatus, status);
        return mapper.selectList(wrapper).stream().map(this::toDomain).collect(Collectors.toList());
    }

    public Page<CoreEntity> findAll(int page, int size) {
        Page<CoreEntityPO> poPage = mapper.selectPage(new Page<>(page, size), null);
        Page<CoreEntity> result = new Page<>(page, size, poPage.getTotal());
        result.setRecords(poPage.getRecords().stream().map(this::toDomain).collect(Collectors.toList()));
        return result;
    }

    public void deleteById(String id) {
        mapper.deleteById(id);
    }

    private CoreEntityPO toPO(CoreEntity entity) {
        CoreEntityPO po = new CoreEntityPO();
        po.setId(entity.getId());
        po.setType(entity.getType());
        po.setStatus(entity.getStatus());
        po.setAttributes(JsonUtils.toJson(entity.getAttributes()));
        po.setCreatedAt(entity.getCreatedAt());
        po.setUpdatedAt(entity.getUpdatedAt());
        return po;
    }

    @SuppressWarnings("unchecked")
    private CoreEntity toDomain(CoreEntityPO po) {
        CoreEntity entity = new CoreEntity();
        entity.setId(po.getId());
        entity.setType(po.getType());
        entity.setStatus(po.getStatus());
        entity.setAttributes(JsonUtils.fromJson(po.getAttributes(), Map.class));
        entity.setCreatedAt(po.getCreatedAt());
        entity.setUpdatedAt(po.getUpdatedAt());
        return entity;
    }
}
