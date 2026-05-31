package com.llmgateway.modelregistry.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.modelregistry.entity.Model;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ModelMapper extends BaseMapper<Model> {
}
