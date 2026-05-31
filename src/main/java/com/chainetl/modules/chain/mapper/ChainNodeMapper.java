package com.chainetl.modules.chain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chainetl.modules.chain.model.ChainNode;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChainNodeMapper extends BaseMapper<ChainNode> {
}
