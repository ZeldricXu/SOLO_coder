package com.cdcsync.common.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cdcsync.common.api.PageResult;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@RequiredArgsConstructor
public abstract class AbstractBaseService<T, ID extends Serializable, M extends BaseMapper<T>>
        implements BaseService<T, ID> {

    @Getter
    protected final M mapper;

    protected abstract void setId(T entity, ID id);

    protected abstract ID getId(T entity);

    protected void preCreate(T entity) {
    }

    protected void preUpdate(T entity) {
    }

    @Override
    public T create(T entity) {
        preCreate(entity);
        mapper.insert(entity);
        return entity;
    }

    @Override
    public T update(T entity) {
        preUpdate(entity);
        mapper.updateById(entity);
        return entity;
    }

    @Override
    public void delete(ID id) {
        mapper.deleteById(id);
    }

    @Override
    public T findById(ID id) {
        return mapper.selectById(id);
    }

    @Override
    public List<T> findAll() {
        return mapper.selectList(new QueryWrapper<>());
    }

    @Override
    public PageResult<T> findPage(int pageNum, int pageSize) {
        Page<T> page = mapper.selectPage(new Page<>(pageNum, pageSize), new QueryWrapper<>());
        return PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords());
    }

    @Override
    public boolean exists(ID id) {
        return mapper.selectById(id) != null;
    }
}
