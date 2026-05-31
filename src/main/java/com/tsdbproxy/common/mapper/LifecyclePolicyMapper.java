package com.tsdbproxy.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tsdbproxy.common.entity.LifecyclePolicy;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LifecyclePolicyMapper extends BaseMapper<LifecyclePolicy> {
}
