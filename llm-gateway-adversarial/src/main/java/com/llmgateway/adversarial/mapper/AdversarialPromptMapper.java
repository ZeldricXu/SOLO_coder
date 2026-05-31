package com.llmgateway.adversarial.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.adversarial.entity.AdversarialPrompt;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdversarialPromptMapper extends BaseMapper<AdversarialPrompt> {
}
