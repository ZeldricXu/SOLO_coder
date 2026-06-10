package com.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.entity.Question;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QuestionMapper extends BaseMapper<Question> {

    @Select("SELECT q.* FROM exam_question q " +
            "WHERE q.subject_id = #{subjectId} " +
            "AND q.question_type = #{questionType} " +
            "AND q.difficulty = #{difficulty} " +
            "AND q.deleted = 0 " +
            "ORDER BY RANDOM() " +
            "LIMIT #{count}")
    List<Question> selectRandomQuestions(@Param("subjectId") Long subjectId,
                                         @Param("questionType") Integer questionType,
                                         @Param("difficulty") Integer difficulty,
                                         @Param("count") Integer count);

    @Select("SELECT q.* FROM exam_question q " +
            "INNER JOIN exam_question_knowledge kq ON q.id = kq.question_id " +
            "WHERE kq.knowledge_point_id = #{knowledgePointId} " +
            "AND q.question_type = #{questionType} " +
            "AND q.difficulty = #{difficulty} " +
            "AND q.deleted = 0 " +
            "ORDER BY RANDOM() " +
            "LIMIT #{count}")
    List<Question> selectRandomQuestionsByKnowledgePoint(
            @Param("knowledgePointId") Long knowledgePointId,
            @Param("questionType") Integer questionType,
            @Param("difficulty") Integer difficulty,
            @Param("count") Integer count);
}
