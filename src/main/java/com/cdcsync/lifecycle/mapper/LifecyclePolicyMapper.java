package com.cdcsync.lifecycle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdcsync.lifecycle.domain.LifecyclePolicy;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LifecyclePolicyMapper extends BaseMapper<LifecyclePolicy> {
}
