package com.exam.event;

public interface ExamEventListener {

    String getName();

    default boolean supports(String eventType) {
        return true;
    }

    void onEvent(ExamEvent event);

    default int getOrder() {
        return 0;
    }
}
