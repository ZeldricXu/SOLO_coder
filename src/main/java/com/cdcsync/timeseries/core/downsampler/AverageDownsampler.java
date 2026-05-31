package com.cdcsync.timeseries.core.downsampler;

import com.cdcsync.timeseries.domain.TimeSeriesData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component("AVERAGE")
public class AverageDownsampler implements Downsampler {

    @Override
    public List<TimeSeriesData> downsample(List<TimeSeriesData> data, long windowSizeMs) {
        Map<Long, WindowStats> windows = new HashMap<>();

        for (TimeSeriesData point : data) {
            long windowStart = (point.getMetricTs() / windowSizeMs) * windowSizeMs;
            windows.compute(windowStart, (k, v) -> {
                if (v == null) {
                    v = new WindowStats();
                }
                v.sum += point.getValue();
                v.count++;
                v.tagsJson = point.getTagsJson();
                return v;
            });
        }

        List<TimeSeriesData> result = new ArrayList<>();
        for (Map.Entry<Long, WindowStats> entry : windows.entrySet()) {
            TimeSeriesData point = new TimeSeriesData();
            point.setMetricTs(entry.getKey());
            point.setValue(entry.getValue().sum / entry.getValue().count);
            point.setTagsJson(entry.getValue().tagsJson);
            result.add(point);
        }

        result.sort((a, b) -> Long.compare(a.getMetricTs(), b.getMetricTs()));
        return result;
    }

    private static class WindowStats {
        double sum;
        int count;
        String tagsJson;
    }
}
