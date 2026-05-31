package com.delivery.tracker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.delivery.tracker.entity.AsyncTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 异步任务Mapper
 */
@Mapper
public interface AsyncTaskMapper extends BaseMapper<AsyncTask> {
}
