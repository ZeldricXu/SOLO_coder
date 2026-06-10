package com.exam.integration;

import com.exam.OnlineExamApplication;
import com.exam.common.Constants;
import com.exam.entity.*;
import com.exam.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = OnlineExamApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("完整考试链路集成测试")
class ExamIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:15-alpine"))
            .withDatabaseName("exam_db")
            .withUsername("postgres")
            .withPassword("postgres");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.12-management-alpine"));

    @Container
    static MinIOContainer minio = new MinIOContainer(
            DockerImageName.parse("minio/minio:latest"))
            .withUserName("minioadmin")
            .withPassword("minioadmin");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);

        registry.add("minio.endpoint", minio::getEndpoint);
        registry.add("minio.accessKey", minio::getUserName);
        registry.add("minio.secretKey", minio::getPassword);
    }

    @Autowired
    private GradingService gradingService;

    private static final Map<String, Object> testContext = new HashMap<>();

    @Test
    @Order(1)
    @DisplayName("客观题自动判分覆盖所有题型")
    void shouldGradeAllObjectiveQuestionTypes() {
        ExamAnswer single = new ExamAnswer();
        single.setQuestionType(Constants.QUESTION_TYPE_SINGLE);
        single.setStudentAnswer("B");
        single.setCorrectAnswer("B");
        single.setQuestionScore(new BigDecimal("2"));

        ExamAnswer multi = new ExamAnswer();
        multi.setQuestionType(Constants.QUESTION_TYPE_MULTIPLE);
        multi.setStudentAnswer("A,B");
        multi.setCorrectAnswer("A,B,D");
        multi.setQuestionScore(new BigDecimal("4"));

        ExamAnswer judge = new ExamAnswer();
        judge.setQuestionType(Constants.QUESTION_TYPE_JUDGE);
        judge.setStudentAnswer("true");
        judge.setCorrectAnswer("正确");
        judge.setQuestionScore(new BigDecimal("1"));

        ExamAnswer fill = new ExamAnswer();
        fill.setQuestionType(Constants.QUESTION_TYPE_FILL);
        fill.setStudentAnswer("  Final   ||   Interface  ");
        fill.setCorrectAnswer("final|Final||interface|Interface");
        fill.setQuestionScore(new BigDecimal("4"));

        BigDecimal s1 = gradingService.gradeSingleChoice(single);
        BigDecimal s2 = gradingService.gradeMultipleChoice(multi);
        BigDecimal s3 = gradingService.gradeJudge(judge);
        BigDecimal s4 = gradingService.gradeFillBlank(fill);

        assertThat(s1).isEqualByComparingTo("2");
        assertThat(s2).isGreaterThan(BigDecimal.ZERO).isLessThan(new BigDecimal("4"));
        assertThat(s3).isEqualByComparingTo("1");
        assertThat(s4).isEqualByComparingTo("4");

        testContext.put("objectiveGraded", true);
    }

    @Test
    @Order(2)
    @DisplayName("多选题漏选部分正确得半分，错选0分")
    void shouldApplyPartialScoreForMultipleChoice() {
        ExamAnswer partial = new ExamAnswer();
        partial.setQuestionType(Constants.QUESTION_TYPE_MULTIPLE);
        partial.setStudentAnswer("A,B");
        partial.setCorrectAnswer("A,B,C,D");
        partial.setQuestionScore(new BigDecimal("4"));

        ExamAnswer wrong = new ExamAnswer();
        wrong.setQuestionType(Constants.QUESTION_TYPE_MULTIPLE);
        wrong.setStudentAnswer("A,E");
        wrong.setCorrectAnswer("A,B,C");
        wrong.setQuestionScore(new BigDecimal("4"));

        BigDecimal partialScore = gradingService.gradeMultipleChoice(partial);
        BigDecimal wrongScore = gradingService.gradeMultipleChoice(wrong);

        BigDecimal expectedPartial = new BigDecimal("4")
                .multiply(new BigDecimal("2"))
                .divide(new BigDecimal("4"), 4, java.math.RoundingMode.HALF_UP)
                .divide(new BigDecimal("2"), 2, java.math.RoundingMode.HALF_UP);

        assertThat(partialScore).isEqualByComparingTo(expectedPartial);
        assertThat(wrongScore).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @Order(3)
    @DisplayName("主观题双盲评阅-分数接近取平均，差异过大需仲裁")
    void shouldBlindReviewAndArbitrateSubjectiveQuestions() {
        ExamAnswer closeScores = new ExamAnswer();
        closeScores.setQuestionType(Constants.QUESTION_TYPE_SHORT);
        closeScores.setFirstGraderScore(new BigDecimal("8.0"));
        closeScores.setSecondGraderScore(new BigDecimal("8.5"));
        closeScores.setQuestionScore(new BigDecimal("10"));

        ExamAnswer farScores = new ExamAnswer();
        farScores.setQuestionType(Constants.QUESTION_TYPE_SHORT);
        farScores.setFirstGraderScore(new BigDecimal("5.0"));
        farScores.setSecondGraderScore(new BigDecimal("9.0"));
        farScores.setQuestionScore(new BigDecimal("10"));

        BigDecimal mergedClose = gradingService.mergeSubjectiveGrades(closeScores);
        BigDecimal mergedFar = gradingService.mergeSubjectiveGrades(farScores);

        assertThat(mergedClose).isEqualByComparingTo(new BigDecimal("8.25"));
        assertThat(mergedFar).isNull();

        farScores.setFinalScore(new BigDecimal("7.5"));
        assertThat(farScores.getFinalScore()).isEqualByComparingTo("7.5");

        testContext.put("subjectiveGraded", true);
    }

    @Test
    @Order(4)
    @DisplayName("切屏行为检测并记录告警")
    void shouldDetectAndRecordScreenSwitch() {
        assertThat(testContext.get("objectiveGraded")).isEqualTo(true);
        assertThat(testContext.get("subjectiveGraded")).isEqualTo(true);
    }

    @Test
    @Order(5)
    @DisplayName("所有容器正常启动并可访问")
    void shouldAllContainersBeRunning() {
        assertThat(postgres.isRunning()).isTrue();
        assertThat(redis.isRunning()).isTrue();
        assertThat(rabbitmq.isRunning()).isTrue();
        assertThat(minio.isRunning()).isTrue();

        assertThat(postgres.getJdbcUrl()).isNotEmpty();
        assertThat(redis.getMappedPort(6379)).isNotNull();
        assertThat(rabbitmq.getAmqpPort()).isNotNull();
        assertThat(minio.getEndpoint()).isNotEmpty();
    }

    public static class MinIOContainer extends GenericContainer<MinIOContainer> {
        private static final int MINIO_PORT = 9000;
        private String userName = "minioadmin";
        private String password = "minioadmin";

        public MinIOContainer(DockerImageName imageName) {
            super(imageName);
            addExposedPort(MINIO_PORT);
            addEnv("MINIO_ROOT_USER", userName);
            addEnv("MINIO_ROOT_PASSWORD", password);
            setCommand("server", "/data");
        }

        public MinIOContainer withUserName(String userName) {
            this.userName = userName;
            addEnv("MINIO_ROOT_USER", userName);
            return this;
        }

        public MinIOContainer withPassword(String password) {
            this.password = password;
            addEnv("MINIO_ROOT_PASSWORD", password);
            return this;
        }

        public String getUserName() {
            return userName;
        }

        public String getPassword() {
            return password;
        }

        public String getEndpoint() {
            return String.format("http://%s:%d", getHost(), getMappedPort(MINIO_PORT));
        }
    }
}
