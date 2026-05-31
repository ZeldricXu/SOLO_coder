package com.example.configmanager.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.configmanager.entity.ConfigVersion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConfigVersionMapper extends BaseMapper<ConfigVersion> {
}
