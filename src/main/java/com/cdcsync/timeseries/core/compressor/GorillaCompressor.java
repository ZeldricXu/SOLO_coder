package com.cdcsync.timeseries.core.compressor;

import com.alibaba.fastjson2.JSON;
import com.cdcsync.timeseries.domain.TimeSeriesData;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component("GORILLA")
public class GorillaCompressor implements TimeSeriesCompressor {

    @Override
    public byte[] compress(List<TimeSeriesData> data) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            long prevTs = 0;
            double prevValue = 0;
            boolean first = true;

            for (TimeSeriesData point : data) {
                if (first) {
                    writeLong(baos, point.getMetricTs());
                    writeDouble(baos, point.getValue());
                    prevTs = point.getMetricTs();
                    prevValue = point.getValue();
                    first = false;
                } else {
                    long tsDelta = point.getMetricTs() - prevTs;
                    double valueDelta = point.getValue() - prevValue;
                    writeLong(baos, tsDelta);
                    writeDouble(baos, valueDelta);
                    prevTs = point.getMetricTs();
                    prevValue = point.getValue();
                }
            }
            return baos.toByteArray();
        } catch (IOException e) {
            return JSON.toJSONBytes(data);
        }
    }

    @Override
    public List<TimeSeriesData> decompress(byte[] compressedData) {
        List<TimeSeriesData> result = new ArrayList<>();
        try {
            int offset = 0;
            long prevTs = 0;
            double prevValue = 0;
            boolean first = true;

            while (offset < compressedData.length) {
                if (first) {
                    long ts = readLong(compressedData, offset);
                    offset += 8;
                    double value = readDouble(compressedData, offset);
                    offset += 8;
                    TimeSeriesData data = new TimeSeriesData();
                    data.setMetricTs(ts);
                    data.setValue(value);
                    result.add(data);
                    prevTs = ts;
                    prevValue = value;
                    first = false;
                } else {
                    long tsDelta = readLong(compressedData, offset);
                    offset += 8;
                    double valueDelta = readDouble(compressedData, offset);
                    offset += 8;
                    long ts = prevTs + tsDelta;
                    double value = prevValue + valueDelta;
                    TimeSeriesData data = new TimeSeriesData();
                    data.setMetricTs(ts);
                    data.setValue(value);
                    result.add(data);
                    prevTs = ts;
                    prevValue = value;
                }
            }
            return result;
        } catch (Exception e) {
            return JSON.parseArray(compressedData, TimeSeriesData.class);
        }
    }

    private void writeLong(ByteArrayOutputStream baos, long value) {
        for (int i = 7; i >= 0; i--) {
            baos.write((int) ((value >> (i * 8)) & 0xFF));
        }
    }

    private void writeDouble(ByteArrayOutputStream baos, double value) {
        long bits = Double.doubleToLongBits(value);
        writeLong(baos, bits);
    }

    private long readLong(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xFF);
        }
        return value;
    }

    private double readDouble(byte[] bytes, int offset) {
        long bits = readLong(bytes, offset);
        return Double.longBitsToDouble(bits);
    }
}
