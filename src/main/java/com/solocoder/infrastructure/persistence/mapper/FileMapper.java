package com.solocoder.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solocoder.infrastructure.persistence.entity.FileEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

@Mapper
public interface FileMapper extends BaseMapper<FileEntity> {

    @Select("SELECT * FROM files WHERE expires_at < #{expirationTime} AND status = 'active'")
    List<FileEntity> findExpiredFiles(@Param("expirationTime") Instant expirationTime);

    @Select("SELECT * FROM files WHERE file_name LIKE #{prefix}% LIMIT #{limit} OFFSET #{offset}")
    List<FileEntity> findByPrefix(@Param("prefix") String prefix,
                                   @Param("offset") int offset,
                                   @Param("limit") int limit);
}
