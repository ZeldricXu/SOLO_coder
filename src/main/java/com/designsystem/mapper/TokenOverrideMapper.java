package com.designsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.designsystem.entity.TokenOverride;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TokenOverrideMapper extends BaseMapper<TokenOverride> {
    List<TokenOverride> selectByTokenId(@Param("tokenId") Long tokenId);

    List<TokenOverride> selectByTokenIdAndScope(@Param("tokenId") Long tokenId, @Param("scope") String scope, @Param("theme") String theme);
}
