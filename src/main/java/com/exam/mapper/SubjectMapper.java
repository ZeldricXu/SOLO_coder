package com.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.entity.Subject;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SubjectMapper extends BaseMapper<Subject> {
}
