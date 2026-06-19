package com.designsystem.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.designsystem.entity.ComponentDoc;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ComponentDocMapper extends BaseMapper<ComponentDoc> {
    List<ComponentDoc> selectByVersionId(@Param("versionId") Long versionId);

    List<ComponentDoc> selectUnindexedDocs();

    default IPage<ComponentDoc> searchDocs(Page<ComponentDoc> page,
                                          @Param("keyword") String keyword,
                                          @Param("framework") String framework,
                                          @Param("docType") String docType) {
        LambdaQueryWrapper<ComponentDoc> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(ComponentDoc::getTitle, keyword)
                    .or()
                    .like(ComponentDoc::getContent, keyword));
        }
        if (docType != null && !docType.isEmpty()) {
            wrapper.eq(ComponentDoc::getDocType, docType);
        }
        wrapper.orderByDesc(ComponentDoc::getUpdatedAt);
        return selectPage(page, wrapper);
    }

    default int deleteByVersionId(@Param("versionId") Long versionId) {
        return delete(new LambdaQueryWrapper<ComponentDoc>()
                .eq(ComponentDoc::getComponentVersionId, versionId));
    }
}
