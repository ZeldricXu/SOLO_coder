package com.nftindexer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nftindexer.entity.ChainBlock;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChainBlockMapper extends BaseMapper<ChainBlock> {
}
