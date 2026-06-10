package com.exam.service;

import com.exam.entity.ExamAnswer;
import com.exam.entity.Question;
import com.exam.mapper.QuestionMapper;
import com.exam.service.arbitration.ArbitrationStrategyManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GradingService {

    private final QuestionMapper questionMapper;
    private final ArbitrationStrategyManager arbitrationManager;

    public GradingService(QuestionMapper questionMapper) {
        this(questionMapper, new ArbitrationStrategyManager());
    }

    public GradingService(QuestionMapper questionMapper, ArbitrationStrategyManager arbitrationManager) {
        this.questionMapper = questionMapper;
        this.arbitrationManager = arbitrationManager != null ? arbitrationManager : new ArbitrationStrategyManager();
    }

    public BigDecimal gradeObjectiveQuestion(ExamAnswer answer) {
        if (answer == null) return BigDecimal.ZERO;

        Integer questionType = answer.getQuestionType();
        if (questionType == null) return BigDecimal.ZERO;

        return switch (questionType) {
            case 1 -> gradeSingleChoice(answer);
            case 2 -> gradeMultipleChoice(answer);
            case 3 -> gradeJudge(answer);
            case 4 -> gradeFillBlank(answer);
            default -> BigDecimal.ZERO;
        };
    }

    public BigDecimal gradeSingleChoice(ExamAnswer answer) {
        if (answer.getStudentAnswer() == null || answer.getCorrectAnswer() == null) {
            return BigDecimal.ZERO;
        }
        String student = answer.getStudentAnswer().trim();
        String correct = answer.getCorrectAnswer().trim();
        return student.equalsIgnoreCase(correct) ? answer.getQuestionScore() : BigDecimal.ZERO;
    }

    public BigDecimal gradeMultipleChoice(ExamAnswer answer) {
        if (answer.getStudentAnswer() == null || answer.getCorrectAnswer() == null) {
            return BigDecimal.ZERO;
        }

        Set<String> studentAnswers = parseMultipleAnswer(answer.getStudentAnswer());
        Set<String> correctAnswers = parseMultipleAnswer(answer.getCorrectAnswer());

        if (studentAnswers.isEmpty()) return BigDecimal.ZERO;
        if (studentAnswers.equals(correctAnswers)) return answer.getQuestionScore();

        boolean hasWrongAnswer = studentAnswers.stream()
                .anyMatch(a -> !correctAnswers.contains(a));
        if (hasWrongAnswer) return BigDecimal.ZERO;

        long correctCount = studentAnswers.stream()
                .filter(correctAnswers::contains)
                .count();
        BigDecimal ratio = new BigDecimal(correctCount)
                .divide(new BigDecimal(correctAnswers.size()), 4, RoundingMode.HALF_UP);

        return answer.getQuestionScore().multiply(ratio)
                .divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
    }

    public BigDecimal gradeJudge(ExamAnswer answer) {
        if (answer.getStudentAnswer() == null || answer.getCorrectAnswer() == null) {
            return BigDecimal.ZERO;
        }

        String student = answer.getStudentAnswer().trim().toLowerCase();
        String correct = answer.getCorrectAnswer().trim().toLowerCase();

        Set<String> trueSet = Set.of("true", "t", "1", "对", "正确", "yes", "y");
        Set<String> falseSet = Set.of("false", "f", "0", "错", "错误", "no", "n");

        Boolean studentBool = trueSet.contains(student) ? Boolean.TRUE
                : falseSet.contains(student) ? Boolean.FALSE : null;
        Boolean correctBool = trueSet.contains(correct) ? Boolean.TRUE
                : falseSet.contains(correct) ? Boolean.FALSE : null;

        if (studentBool == null || correctBool == null) {
            return student.equalsIgnoreCase(correct) ? answer.getQuestionScore() : BigDecimal.ZERO;
        }

        return studentBool.equals(correctBool) ? answer.getQuestionScore() : BigDecimal.ZERO;
    }

    public BigDecimal gradeFillBlank(ExamAnswer answer) {
        if (answer.getStudentAnswer() == null || answer.getCorrectAnswer() == null) {
            return BigDecimal.ZERO;
        }

        String[] studentArr = answer.getStudentAnswer().split("\\|\\|");
        String[] correctArr = answer.getCorrectAnswer().split("\\|\\|");

        if (studentArr.length != correctArr.length) {
            return gradeSingleFill(answer.getStudentAnswer(), answer.getCorrectAnswer(), answer.getQuestionScore());
        }

        BigDecimal perBlankScore = answer.getQuestionScore()
                .divide(new BigDecimal(correctArr.length), 4, RoundingMode.HALF_UP);
        BigDecimal totalScore = BigDecimal.ZERO;

        for (int i = 0; i < correctArr.length; i++) {
            String student = i < studentArr.length ? studentArr[i].trim() : "";
            String correct = correctArr[i].trim();

            String[] acceptableAnswers = correct.split("\\|");
            boolean matched = Arrays.stream(acceptableAnswers)
                    .map(String::trim)
                    .anyMatch(a -> normalizeFillAnswer(a).equals(normalizeFillAnswer(student)));

            if (matched) {
                totalScore = totalScore.add(perBlankScore);
            }
        }

        return totalScore.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal gradeSingleFill(String studentAnswer, String correctAnswer, BigDecimal fullScore) {
        String student = normalizeFillAnswer(studentAnswer);
        String[] acceptableAnswers = correctAnswer.split("\\|");
        boolean matched = Arrays.stream(acceptableAnswers)
                .map(String::trim)
                .anyMatch(a -> normalizeFillAnswer(a).equals(student));
        return matched ? fullScore : BigDecimal.ZERO;
    }

    private String normalizeFillAnswer(String answer) {
        if (answer == null) return "";
        return answer.trim()
                .replaceAll("\\s+", " ")
                .replaceAll("，", ",")
                .replaceAll("。", ".")
                .toLowerCase();
    }

    private Set<String> parseMultipleAnswer(String answer) {
        if (answer == null) return Collections.emptySet();
        return Arrays.stream(answer.split("[,，;；、\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Map<String, Object> gradeProgrammingQuestion(ExamAnswer answer, Question question) {
        Map<String, Object> result = new HashMap<>();
        result.put("score", BigDecimal.ZERO);
        result.put("passed", 0);
        result.put("total", 0);
        result.put("logs", "");
        result.put("output", "");

        if (answer.getStudentAnswer() == null || answer.getStudentAnswer().trim().isEmpty()) {
            result.put("logs", "代码为空");
            return result;
        }

        try {
            return executeCodeInSandbox(answer.getStudentAnswer(), question);
        } catch (Exception e) {
            log.error("编程题执行异常", e);
            result.put("logs", "执行异常: " + e.getMessage());
            return result;
        }
    }

    private Map<String, Object> executeCodeInSandbox(String code, Question question) {
        Map<String, Object> result = new HashMap<>();
        List<String> logs = new ArrayList<>();
        int passed = 0;
        int total = 0;

        String testCasesStr = question.getTestCases();
        if (testCasesStr == null || testCasesStr.isEmpty()) {
            result.put("score", BigDecimal.ZERO);
            result.put("passed", 0);
            result.put("total", 0);
            result.put("logs", "无测试用例");
            result.put("output", "");
            return result;
        }

        List<TestCase> testCases = parseTestCases(testCasesStr);
        total = testCases.size();

        for (TestCase tc : testCases) {
            SandboxResult sr = runSingleTestCase(code, tc, question.getTimeLimit());
            logs.add(String.format("用例[%s]: %s, 输出=%s, 期望=%s",
                    tc.name, sr.passed ? "通过" : "失败", sr.output, tc.expectedOutput));
            if (sr.passed) passed++;
        }

        BigDecimal score = total > 0
                ? question.getScore().multiply(new BigDecimal(passed))
                .divide(new BigDecimal(total), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        result.put("score", score);
        result.put("passed", passed);
        result.put("total", total);
        result.put("logs", String.join("\n", logs));
        return result;
    }

    private SandboxResult runSingleTestCase(String code, TestCase testCase, Integer timeLimitMs) {
        SandboxResult result = new SandboxResult();
        Process process = null;
        ProcessHandle processHandle = null;
        try {
            int timeout = timeLimitMs != null && timeLimitMs > 0 ? timeLimitMs : 30000;

            ProcessBuilder pb = new ProcessBuilder("timeout", String.valueOf(timeout / 1000),
                    "bash", "-c", "echo '" + testCase.input + "' | java -cp /tmp ");
            pb.redirectErrorStream(true);
            process = pb.start();
            processHandle = process.toHandle();

            java.io.InputStream is = process.getInputStream();
            String output = new String(is.readAllBytes()).trim();

            boolean finished = process.waitFor(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);

            if (!finished) {
                result.passed = false;
                result.output = "执行超时";
                return result;
            }

            result.output = output;
            result.passed = normalizeOutput(output).equals(normalizeOutput(testCase.expectedOutput));

        } catch (Exception e) {
            result.passed = false;
            result.output = "执行错误: " + e.getMessage();
        } finally {
            if (processHandle != null) {
                processHandle.descendants().forEach(ph -> {
                    try {
                        ph.destroyForcibly();
                    } catch (Exception ignored) {
                    }
                });
                try {
                    processHandle.destroyForcibly();
                } catch (Exception ignored) {
                }
            }
            if (process != null) {
                process.destroyForcibly();
            }
        }
        return result;
    }

    private String normalizeOutput(String output) {
        if (output == null) return "";
        return output.trim()
                .replaceAll("\\r\\n", "\n")
                .replaceAll("\\s+$", "");
    }

    private List<TestCase> parseTestCases(String testCasesStr) {
        List<TestCase> result = new ArrayList<>();
        String[] cases = testCasesStr.split("\n");
        for (int i = 0; i < cases.length; i++) {
            String[] parts = cases[i].split("->");
            if (parts.length >= 2) {
                TestCase tc = new TestCase();
                tc.name = "用例" + (i + 1);
                tc.input = parts[0].trim();
                tc.expectedOutput = parts[1].trim();
                result.add(tc);
            }
        }
        return result;
    }

    public BigDecimal mergeSubjectiveGrades(ExamAnswer answer) {
        return arbitrationManager.arbitrate(answer);
    }

    public BigDecimal mergeSubjectiveGrades(ExamAnswer answer, String strategyCode) {
        return arbitrationManager.arbitrate(answer, strategyCode);
    }

    private static class TestCase {
        String name;
        String input;
        String expectedOutput;
    }

    private static class SandboxResult {
        boolean passed;
        String output;
    }
}
