package com.edgeplatform.dataaccess.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.edgeplatform.common.dto.PagedRequest;
import com.edgeplatform.common.dto.PagedResult;
import com.edgeplatform.common.exception.NotFoundException;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.List;
import java.util.function.Function;

public abstract class BaseService<M extends BaseMapper<T>, T> extends ServiceImpl<M, T> {

    @Cacheable(value = "entityCache", key = "#id")
    public T getByIdWithCache(Serializable id) {
        T entity = getById(id);
        if (entity == null) {
            throw new NotFoundException(getEntityName(), String.valueOf(id));
        }
        return entity;
    }

    @Override
    @CacheEvict(value = "entityCache", key = "#entity.id")
    @Transactional(rollbackFor = Exception.class)
    public boolean updateById(T entity) {
        return super.updateById(entity);
    }

    @Override
    @CacheEvict(value = "entityCache", key = "#id")
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id) {
        return super.removeById(id);
    }

    public PagedResult<T> findByPage(PagedRequest request, Function<LambdaQueryWrapper<T>, LambdaQueryWrapper<T>> wrapperBuilder) {
        LambdaQueryWrapper<T> wrapper = new LambdaQueryWrapper<>();
        if (wrapperBuilder != null) {
            wrapper = wrapperBuilder.apply(wrapper);
        }

        if (request.getSortBy() != null && !request.getSortBy().isEmpty()) {
            boolean ascending = "asc".equalsIgnoreCase(request.getSortDir());
            wrapper.orderBy(true, ascending, getSortColumn(request.getSortBy()));
        }

        Page<T> page = new Page<>(request.getPageNum(), request.getPageSize());
        IPage<T> result = page(page, wrapper);

        return new PagedResult<>(result.getTotal(), request.getPageNum(), request.getPageSize(), result.getRecords());
    }

    public List<T> findAll(Function<LambdaQueryWrapper<T>, LambdaQueryWrapper<T>> wrapperBuilder) {
        LambdaQueryWrapper<T> wrapper = new LambdaQueryWrapper<>();
        if (wrapperBuilder != null) {
            wrapper = wrapperBuilder.apply(wrapper);
        }
        return list(wrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean saveBatchWithTransaction(List<T> entityList) {
        return saveBatch(entityList);
    }

    protected abstract String getEntityName();

    protected abstract String getSortColumn(String sortBy);
}
