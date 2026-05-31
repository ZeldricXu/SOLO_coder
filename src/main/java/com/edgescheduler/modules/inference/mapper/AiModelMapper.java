package com.edgescheduler.modules.inference.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.modules.inference.domain.AiModel;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiModelMapper extends BaseMapper<AiModel> {
}
