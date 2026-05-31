package com.cdcsync.timeseries.core.downsampler;

import com.cdcsync.timeseries.domain.TimeSeriesData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component("MAX")
public class MaxDownsampler implements Downsampler {

    @Override
    public List<TimeSeriesData> downsample(List<TimeSeriesData> data, long windowSizeMs) {
        Map<Long, WindowStats> windows = new HashMap<>();

        for (TimeSeriesData point : data) {
            long windowStart = (point.getMetricTs() / windowSizeMs) * windowSizeMs;
            windows.compute(windowStart, (k, v) -> {
                if (v == null) {
                    v = new WindowStats();
                    v.max = point.getValue();
                    v.tagsJson = point.getTagsJson();
                } else {
                    v.max = Math.max(v.max, point.getValue());
                }
                return v;
            });
        }

        List<TimeSeriesData> result = new ArrayList<>();
        for (Map.Entry<Long, WindowStats> entry : windows.entrySet()) {
            TimeSeriesData point = new TimeSeriesData();
            point.setMetricTs(entry.getKey());
            point.setValue(entry.getValue().max);
            point.setTagsJson(entry.getValue().tagsJson);
            result.add(point);
        }

        result.sort((a, b) -> Long.compare(a.getMetricTs(), b.getMetricTs()));
        return result;
    }

    private static class WindowStats {
        double max;
        String tagsJson;
    }
}
