package com.solo.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.config.entity.Config;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConfigMapper extends BaseMapper<Config> {
}
