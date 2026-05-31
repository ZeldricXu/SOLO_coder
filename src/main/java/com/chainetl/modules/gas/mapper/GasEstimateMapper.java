package com.chainetl.modules.gas.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chainetl.modules.gas.model.GasEstimate;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GasEstimateMapper extends BaseMapper<GasEstimate> {
}
