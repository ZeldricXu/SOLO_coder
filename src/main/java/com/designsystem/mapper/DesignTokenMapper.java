package com.designsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.designsystem.entity.DesignToken;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DesignTokenMapper extends BaseMapper<DesignToken> {
    IPage<DesignToken> selectTokenPage(Page<DesignToken> page, @Param("keyword") String keyword, @Param("tokenType") String tokenType, @Param("tokenLevel") String tokenLevel, @Param("category") String category);

    List<DesignToken> selectByParentId(@Param("inheritsFrom") String inheritsFrom);

    List<DesignToken> selectByLevel(@Param("tokenLevel") String tokenLevel);

    List<DesignToken> selectByType(@Param("tokenType") String tokenType);

    DesignToken selectByName(@Param("tokenName") String tokenName);

    List<DesignToken> selectTokenChain(@Param("tokenId") Long tokenId);
}
