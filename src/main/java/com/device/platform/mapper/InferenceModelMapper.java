package com.device.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.device.platform.entity.InferenceModel;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InferenceModelMapper extends BaseMapper<InferenceModel> {
}
