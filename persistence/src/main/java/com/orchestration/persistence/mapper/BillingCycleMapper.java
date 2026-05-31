package com.orchestration.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orchestration.persistence.entity.BillingCycle;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BillingCycleMapper extends BaseMapper<BillingCycle> {
}
