package com.chain.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chain.infrastructure.persistence.entity.RpcNode;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RpcNodeMapper extends BaseMapper<RpcNode> {
}
