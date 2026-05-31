package com.tsdbproxy.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tsdbproxy.common.entity.CdcTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CdcTaskMapper extends BaseMapper<CdcTask> {
}
