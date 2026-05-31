package com.cdcsync.timeseries.core.compressor;

import com.cdcsync.timeseries.domain.TimeSeriesData;

import java.util.List;

public interface TimeSeriesCompressor {

    byte[] compress(List<TimeSeriesData> data);

    List<TimeSeriesData> decompress(byte[] compressedData);
}
