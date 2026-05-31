package com.example.configmanager.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.configmanager.entity.Config;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConfigMapper extends BaseMapper<Config> {
}
