package com.meshcontrol.common.base;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

public abstract class BaseService<M extends BaseMapper<T>, T extends BaseEntity> extends ServiceImpl<M, T> {

    @Autowired
    protected M baseMapper;

    public T getById(Serializable id) {
        return baseMapper.selectById(id);
    }

    public List<T> listByIds(Collection<? extends Serializable> idList) {
        return baseMapper.selectBatchIds(idList);
    }

    public boolean save(T entity) {
        return super.save(entity);
    }

    public boolean updateById(T entity) {
        return super.updateById(entity);
    }

    public boolean removeById(Serializable id) {
        return super.removeById(id);
    }

    public IPage<T> page(int pageNum, int pageSize, LambdaQueryWrapper<T> wrapper) {
        Page<T> page = new Page<>(pageNum, pageSize);
        return baseMapper.selectPage(page, wrapper);
    }

    public List<T> list(LambdaQueryWrapper<T> wrapper) {
        return baseMapper.selectList(wrapper);
    }

    public T getOne(LambdaQueryWrapper<T> wrapper) {
        return baseMapper.selectOne(wrapper);
    }

    public long count(LambdaQueryWrapper<T> wrapper) {
        return baseMapper.selectCount(wrapper);
    }
}
