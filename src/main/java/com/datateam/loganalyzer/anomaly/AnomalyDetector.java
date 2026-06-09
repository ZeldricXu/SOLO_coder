package com.datateam.loganalyzer.anomaly;

import com.datateam.loganalyzer.model.AnomalyResult;
import com.datateam.loganalyzer.model.TimeSeriesPoint;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface AnomalyDetector {

    String getName();

    String getAlgorithmClassName();

    void train(List<Double> baselineData, String metric);

    void trainFromTimeSeries(List<TimeSeriesPoint> points, String metric);

    List<AnomalyResult> detect(List<Double> values);

    List<AnomalyResult> detect(List<Double> values, List<Instant> timestamps);

    List<AnomalyResult> detectFromTimeSeries(List<TimeSeriesPoint> points, String metric);

    void configure(Map<String, Object> config);

    Map<String, Object> getConfiguration();

    int getMinDataPoints();

    boolean isReady();

    BaselineModel getBaseline();
}
