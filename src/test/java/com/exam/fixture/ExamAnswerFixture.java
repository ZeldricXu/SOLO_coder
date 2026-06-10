package com.exam.fixture;

import com.exam.common.Constants;
import com.exam.entity.ExamAnswer;

import java.math.BigDecimal;

public class ExamAnswerFixture {

    public static ExamAnswer examAnswerForGrading(Integer questionType, String studentAnswer, String correctAnswer, BigDecimal fullScore) {
        ExamAnswer answer = new ExamAnswer();
        answer.setId(1L);
        answer.setQuestionType(questionType);
        answer.setStudentAnswer(studentAnswer);
        answer.setCorrectAnswer(correctAnswer);
        answer.setQuestionScore(fullScore);
        return answer;
    }

    public static ExamAnswer singleChoiceCorrect() {
        return examAnswerForGrading(Constants.QUESTION_TYPE_SINGLE, "B", "B", new BigDecimal("2"));
    }

    public static ExamAnswer singleChoiceWrong() {
        return examAnswerForGrading(Constants.QUESTION_TYPE_SINGLE, "A", "B", new BigDecimal("2"));
    }

    public static ExamAnswer multipleChoiceFull() {
        return examAnswerForGrading(Constants.QUESTION_TYPE_MULTIPLE, "A,B,D", "A,B,D", new BigDecimal("4"));
    }

    public static ExamAnswer multipleChoicePartial() {
        return examAnswerForGrading(Constants.QUESTION_TYPE_MULTIPLE, "A,B", "A,B,D", new BigDecimal("4"));
    }

    public static ExamAnswer multipleChoiceWrong() {
        return examAnswerForGrading(Constants.QUESTION_TYPE_MULTIPLE, "A,C", "A,B,D", new BigDecimal("4"));
    }

    public static ExamAnswer judgeCorrect() {
        return examAnswerForGrading(Constants.QUESTION_TYPE_JUDGE, "正确", "正确", new BigDecimal("1"));
    }

    public static ExamAnswer judgeCorrectYes() {
        return examAnswerForGrading(Constants.QUESTION_TYPE_JUDGE, "true", "正确", new BigDecimal("1"));
    }

    public static ExamAnswer judgeWrong() {
        return examAnswerForGrading(Constants.QUESTION_TYPE_JUDGE, "错误", "正确", new BigDecimal("1"));
    }

    public static ExamAnswer fillBlankCorrect() {
        return examAnswerForGrading(Constants.QUESTION_TYPE_FILL, " final  ||  interface  ", "final||interface", new BigDecimal("4"));
    }

    public static ExamAnswer fillBlankWithSpaces() {
        return examAnswerForGrading(Constants.QUESTION_TYPE_FILL, "  Final  ||  Interface ", "final|Final||interface|Interface", new BigDecimal("4"));
    }

    public static ExamAnswer subjectiveForMerge(BigDecimal grader1, BigDecimal grader2, BigDecimal fullScore) {
        ExamAnswer answer = new ExamAnswer();
        answer.setId(1L);
        answer.setQuestionType(Constants.QUESTION_TYPE_SHORT);
        answer.setFirstGraderScore(grader1);
        answer.setSecondGraderScore(grader2);
        answer.setQuestionScore(fullScore);
        return answer;
    }

    public static ExamAnswer subjectiveWithArbitration(BigDecimal grader1, BigDecimal grader2, BigDecimal arbitration, BigDecimal fullScore) {
        ExamAnswer answer = subjectiveForMerge(grader1, grader2, fullScore);
        answer.setFinalScore(arbitration);
        return answer;
    }
}
