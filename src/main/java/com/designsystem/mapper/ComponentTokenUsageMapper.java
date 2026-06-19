package com.designsystem.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.designsystem.entity.ComponentTokenUsage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ComponentTokenUsageMapper extends BaseMapper<ComponentTokenUsage> {
    List<ComponentTokenUsage> selectByTokenId(@Param("tokenId") Long tokenId);

    List<ComponentTokenUsage> selectByComponentId(@Param("componentId") Long componentId);

    int deleteByTokenIdAndComponentId(@Param("tokenId") Long tokenId, @Param("componentId") Long componentId);

    default int deleteByComponentId(@Param("componentId") Long componentId) {
        return delete(new LambdaQueryWrapper<ComponentTokenUsage>()
                .eq(ComponentTokenUsage::getComponentId, componentId));
    }
}
