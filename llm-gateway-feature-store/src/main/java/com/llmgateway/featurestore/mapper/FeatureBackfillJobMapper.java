package com.llmgateway.featurestore.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.featurestore.entity.FeatureBackfillJob;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FeatureBackfillJobMapper extends BaseMapper<FeatureBackfillJob> {
}
