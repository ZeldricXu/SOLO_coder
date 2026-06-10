package com.exam.event;

public class ExamEventType {

    public static final String EXAM_START = "exam_start";
    public static final String EXAM_SUBMIT = "exam_submit";
    public static final String EXAM_FORCE_SUBMIT = "exam_force_submit";

    public static final String ANSWER_SAVE = "answer_save";
    public static final String ANSWER_CHANGE = "answer_change";

    public static final String HEARTBEAT = "heartbeat";

    public static final String SCREEN_SWITCH = "screen_switch";
    public static final String ABNORMAL = "abnormal";

    public static final String SESSION_RECONNECT = "session_reconnect";
    public static final String SESSION_DISCONNECT = "session_disconnect";

    public static final String GRADING_START = "grading_start";
    public static final String GRADING_COMPLETE = "grading_complete";

    private ExamEventType() {
    }
}
