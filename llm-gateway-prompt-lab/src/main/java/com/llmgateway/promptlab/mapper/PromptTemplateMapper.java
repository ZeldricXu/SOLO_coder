package com.llmgateway.promptlab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.promptlab.entity.PromptTemplate;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplate> {
}
