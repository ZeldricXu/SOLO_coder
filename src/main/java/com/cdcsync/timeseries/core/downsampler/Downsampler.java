package com.cdcsync.timeseries.core.downsampler;

import com.cdcsync.timeseries.domain.TimeSeriesData;

import java.util.List;

public interface Downsampler {

    List<TimeSeriesData> downsample(List<TimeSeriesData> data, long windowSizeMs);
}
