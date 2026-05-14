package com.meeting.exception;

public class MeetingException extends RuntimeException {
    private final int code;

    public MeetingException(int code, String message) {
        super(message);
        this.code = code;
    }

    public MeetingException(String message) {
        this(400, message);
    }

    public int getCode() {
        return code;
    }
}
