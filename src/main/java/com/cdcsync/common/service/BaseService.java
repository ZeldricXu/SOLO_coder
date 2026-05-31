package com.cdcsync.common.service;

import com.cdcsync.common.api.PageResult;

import java.io.Serializable;
import java.util.List;

public interface BaseService<T, ID extends Serializable> {

    T create(T entity);

    T update(T entity);

    void delete(ID id);

    T findById(ID id);

    List<T> findAll();

    PageResult<T> findPage(int pageNum, int pageSize);

    boolean exists(ID id);
}
