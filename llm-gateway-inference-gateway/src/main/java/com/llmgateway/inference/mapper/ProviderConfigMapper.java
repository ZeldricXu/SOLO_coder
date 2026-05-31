package com.llmgateway.inference.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.inference.entity.ProviderConfig;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProviderConfigMapper extends BaseMapper<ProviderConfig> {
}
