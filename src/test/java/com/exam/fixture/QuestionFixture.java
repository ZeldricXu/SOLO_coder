package com.exam.fixture;

import com.exam.common.Constants;
import com.exam.entity.Question;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class QuestionFixture {

    public static QuestionBuilder singleChoice() {
        return new QuestionBuilder()
                .questionType(Constants.QUESTION_TYPE_SINGLE)
                .difficulty(Constants.DIFFICULTY_MEDIUM)
                .questionContent("Java中以下哪个是基本数据类型？")
                .optionA("String")
                .optionB("int")
                .optionC("Integer")
                .optionD("Object")
                .correctAnswer("B")
                .score(new BigDecimal("2"))
                .analysis("int是Java基本数据类型，其他都是引用类型");
    }

    public static QuestionBuilder multipleChoice() {
        return new QuestionBuilder()
                .questionType(Constants.QUESTION_TYPE_MULTIPLE)
                .difficulty(Constants.DIFFICULTY_MEDIUM)
                .questionContent("以下哪些是Java的访问修饰符？")
                .optionA("public")
                .optionB("private")
                .optionC("static")
                .optionD("protected")
                .correctAnswer("A,B,D")
                .score(new BigDecimal("4"))
                .analysis("static不是访问修饰符，是静态修饰符");
    }

    public static QuestionBuilder judge() {
        return new QuestionBuilder()
                .questionType(Constants.QUESTION_TYPE_JUDGE)
                .difficulty(Constants.DIFFICULTY_EASY)
                .questionContent("Java是一门纯面向对象的编程语言")
                .correctAnswer("正确")
                .score(new BigDecimal("1"))
                .analysis("Java包含基本数据类型，不是纯面向对象");
    }

    public static QuestionBuilder fillBlank() {
        return new QuestionBuilder()
                .questionType(Constants.QUESTION_TYPE_FILL)
                .difficulty(Constants.DIFFICULTY_MEDIUM)
                .questionContent("Java中定义常量使用关键字_____，定义接口使用关键字_____")
                .correctAnswer("final||interface")
                .score(new BigDecimal("4"));
    }

    public static QuestionBuilder shortAnswer() {
        return new QuestionBuilder()
                .questionType(Constants.QUESTION_TYPE_SHORT)
                .difficulty(Constants.DIFFICULTY_HARD)
                .questionContent("请简述Java中HashMap和ConcurrentHashMap的区别")
                .score(new BigDecimal("10"));
    }

    public static QuestionBuilder programming() {
        return new QuestionBuilder()
                .questionType(Constants.QUESTION_TYPE_PROGRAM)
                .difficulty(Constants.DIFFICULTY_MEDIUM)
                .questionContent("实现一个方法，判断一个整数是否是质数")
                .score(new BigDecimal("15"))
                .programmingLanguage("java")
                .testCases("2->true\n3->true\n4->false\n9->false\n17->true")
                .timeLimit(5000);
    }

    public static List<Question> createQuestionPool(int count, int type, int difficulty, Long subjectId) {
        List<Question> result = new ArrayList<>();
        for (long i = 1; i <= count; i++) {
            Question q = new Question();
            q.setId(10000L + i);
            q.setSubjectId(subjectId);
            q.setQuestionType(type);
            q.setDifficulty(difficulty);
            q.setQuestionContent("题目" + i + "-题型" + type + "-难度" + difficulty);
            q.setOptionA("A选项");
            q.setOptionB("B选项");
            q.setOptionC("C选项");
            q.setOptionD("D选项");
            q.setCorrectAnswer("A");
            q.setScore(new BigDecimal("2"));
            q.setKnowledgePoints("KP" + (i % 5 + 1));
            result.add(q);
        }
        return result;
    }

    public static class QuestionBuilder {
        private Long id;
        private Long subjectId = 1L;
        private Integer questionType;
        private Integer difficulty;
        private String questionContent;
        private String questionImage;
        private String optionA;
        private String optionB;
        private String optionC;
        private String optionD;
        private String optionE;
        private String optionF;
        private String correctAnswer;
        private String analysis;
        private BigDecimal score;
        private String knowledgePoints;
        private Integer version = 1;
        private String programmingLanguage;
        private String testCases;
        private String codeTemplate;
        private Integer timeLimit;
        private Integer memoryLimit;

        public QuestionBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public QuestionBuilder subjectId(Long subjectId) {
            this.subjectId = subjectId;
            return this;
        }

        public QuestionBuilder questionType(Integer questionType) {
            this.questionType = questionType;
            return this;
        }

        public QuestionBuilder difficulty(Integer difficulty) {
            this.difficulty = difficulty;
            return this;
        }

        public QuestionBuilder questionContent(String questionContent) {
            this.questionContent = questionContent;
            return this;
        }

        public QuestionBuilder optionA(String optionA) {
            this.optionA = optionA;
            return this;
        }

        public QuestionBuilder optionB(String optionB) {
            this.optionB = optionB;
            return this;
        }

        public QuestionBuilder optionC(String optionC) {
            this.optionC = optionC;
            return this;
        }

        public QuestionBuilder optionD(String optionD) {
            this.optionD = optionD;
            return this;
        }

        public QuestionBuilder correctAnswer(String correctAnswer) {
            this.correctAnswer = correctAnswer;
            return this;
        }

        public QuestionBuilder analysis(String analysis) {
            this.analysis = analysis;
            return this;
        }

        public QuestionBuilder score(BigDecimal score) {
            this.score = score;
            return this;
        }

        public QuestionBuilder knowledgePoints(String knowledgePoints) {
            this.knowledgePoints = knowledgePoints;
            return this;
        }

        public QuestionBuilder programmingLanguage(String programmingLanguage) {
            this.programmingLanguage = programmingLanguage;
            return this;
        }

        public QuestionBuilder testCases(String testCases) {
            this.testCases = testCases;
            return this;
        }

        public QuestionBuilder timeLimit(Integer timeLimit) {
            this.timeLimit = timeLimit;
            return this;
        }

        public Question build() {
            Question q = new Question();
            q.setId(id);
            q.setSubjectId(subjectId);
            q.setQuestionType(questionType);
            q.setDifficulty(difficulty);
            q.setQuestionContent(questionContent);
            q.setQuestionImage(questionImage);
            q.setOptionA(optionA);
            q.setOptionB(optionB);
            q.setOptionC(optionC);
            q.setOptionD(optionD);
            q.setOptionE(optionE);
            q.setOptionF(optionF);
            q.setCorrectAnswer(correctAnswer);
            q.setAnalysis(analysis);
            q.setScore(score);
            q.setKnowledgePoints(knowledgePoints);
            q.setVersion(version);
            q.setProgrammingLanguage(programmingLanguage);
            q.setTestCases(testCases);
            q.setCodeTemplate(codeTemplate);
            q.setTimeLimit(timeLimit);
            q.setMemoryLimit(memoryLimit);
            return q;
        }
    }
}
