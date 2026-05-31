package com.nftindexer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nftindexer.entity.NftMetadata;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NftMetadataMapper extends BaseMapper<NftMetadata> {
}
