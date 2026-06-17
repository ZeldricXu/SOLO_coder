package com.designsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.designsystem.entity.Component;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ComponentMapper extends BaseMapper<Component> {
    IPage<Component> selectComponentPage(Page<Component> page, @Param("keyword") String keyword, @Param("category") String category, @Param("framework") String framework);

    List<Component> selectComponentsByTokenId(@Param("tokenId") Long tokenId);

    IPage<Component> selectMarketplacePage(Page<Component> page, @Param("keyword") String keyword, @Param("category") String category, @Param("framework") String framework, @Param("tags") List<String> tags);
}
