package com.exam.common;

public class Constants {
    public static final String REDIS_TOKEN_PREFIX = "exam:token:";
    public static final String REDIS_EXAM_SESSION_PREFIX = "exam:session:";
    public static final String REDIS_EXAM_ONLINE_PREFIX = "exam:online:";
    public static final String REDIS_QUESTION_CACHE_PREFIX = "exam:question:";

    public static final String MQ_QUEUE_GRADING = "exam.grading.queue";
    public static final String MQ_EXCHANGE_GRADING = "exam.grading.exchange";
    public static final String MQ_ROUTING_KEY_GRADING = "exam.grading";

    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_TEACHER = "ROLE_TEACHER";
    public static final String ROLE_GRADER = "ROLE_GRADER";
    public static final String ROLE_STUDENT = "ROLE_STUDENT";

    public static final Integer QUESTION_TYPE_SINGLE = 1;
    public static final Integer QUESTION_TYPE_MULTIPLE = 2;
    public static final Integer QUESTION_TYPE_JUDGE = 3;
    public static final Integer QUESTION_TYPE_FILL = 4;
    public static final Integer QUESTION_TYPE_SHORT = 5;
    public static final Integer QUESTION_TYPE_PROGRAM = 6;

    public static final Integer DIFFICULTY_EASY = 1;
    public static final Integer DIFFICULTY_MEDIUM = 2;
    public static final Integer DIFFICULTY_HARD = 3;

    public static final Integer PAPER_MODE_FIXED = 1;
    public static final Integer PAPER_MODE_RANDOM = 2;

    public static final Integer EXAM_STATUS_NOT_STARTED = 0;
    public static final Integer EXAM_STATUS_IN_PROGRESS = 1;
    public static final Integer EXAM_STATUS_ENDED = 2;
    public static final Integer EXAM_STATUS_GRADING = 3;
    public static final Integer EXAM_STATUS_COMPLETED = 4;

    public static final Integer ANSWER_STATUS_NOT_ANSWERED = 0;
    public static final Integer ANSWER_STATUS_ANSWERED = 1;
    public static final Integer ANSWER_STATUS_MARKED = 2;

    public static final Integer GRADING_STATUS_PENDING = 0;
    public static final Integer GRADING_STATUS_AUTO_GRADED = 1;
    public static final Integer GRADING_STATUS_GRADING = 2;
    public static final Integer GRADING_STATUS_GRADED = 3;
    public static final Integer GRADING_STATUS_ARBITRATION = 4;
    public static final Integer GRADING_STATUS_COMPLETED = 5;

    public static final Integer ABNORMAL_TYPE_SCREEN_SWITCH = 1;
    public static final Integer ABNORMAL_TYPE_DISCONNECT = 2;
    public static final Integer ABNORMAL_TYPE_FOCUS_LOST = 3;
    public static final Integer ABNORMAL_TYPE_COPY_PASTE = 4;

    public static final Long EXAM_HEARTBEAT_TIMEOUT = 60000L;
}
