package com.llmgateway.inference.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.inference.entity.InferenceRequest;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InferenceRequestMapper extends BaseMapper<InferenceRequest> {
}
