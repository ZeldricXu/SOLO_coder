package com.tsdbproxy.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tsdbproxy.common.entity.QueryPlan;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QueryPlanMapper extends BaseMapper<QueryPlan> {
}
