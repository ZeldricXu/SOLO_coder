package com.observability.profiling.model;

import lombok.Data;

import java.io.Serializable;

@Data
public class StackFrame implements Serializable {

    private static final long serialVersionUID = 1L;

    private String method;
    private String className;
    private String fileName;
    private int lineNumber;
    private long samples;
    private long totalSamples;
    private double percentage;
    private long selfTime;
    private long totalTime;
}
