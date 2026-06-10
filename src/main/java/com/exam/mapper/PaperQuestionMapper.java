package com.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.entity.PaperQuestion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PaperQuestionMapper extends BaseMapper<PaperQuestion> {

    @Select("SELECT pq.* FROM exam_paper_question pq " +
            "WHERE pq.paper_id = #{paperId} AND pq.deleted = 0 " +
            "ORDER BY pq.sort_order ASC")
    List<PaperQuestion> selectByPaperId(@Param("paperId") Long paperId);
}
