package com.tsdbproxy.timeseries.compression;

import cn.hutool.core.util.ByteUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

@Slf4j
@Component
public class GorillaCompressor {

    public byte[] compress(List<TimeValuePair> data) {
        if (data == null || data.isEmpty()) {
            return new byte[0];
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try {
            TimeValuePair first = data.get(0);
            baos.write(ByteUtil.longToBytes(first.timestamp));
            baos.write(ByteUtil.doubleToBytes(first.value));

            long prevTimestamp = first.timestamp;
            double prevValue = first.value;
            long prevTimestampDelta = 0;

            for (int i = 1; i < data.size(); i++) {
                TimeValuePair current = data.get(i);
                long timestampDelta = current.timestamp - prevTimestamp;
                double valueDelta = current.value - prevValue;

                long deltaOfDelta = timestampDelta - prevTimestampDelta;

                if (deltaOfDelta == 0) {
                    baos.write(0);
                } else {
                    baos.write(1);
                    baos.write(ByteUtil.longToBytes(timestampDelta));
                }

                if (valueDelta == 0) {
                    baos.write(0);
                } else {
                    baos.write(1);
                    baos.write(ByteUtil.doubleToBytes(valueDelta));
                }

                prevTimestamp = current.timestamp;
                prevValue = current.value;
                prevTimestampDelta = timestampDelta;
            }
        } catch (Exception e) {
            log.error("Gorilla压缩失败", e);
        }

        return baos.toByteArray();
    }

    public List<TimeValuePair> decompress(byte[] compressed) {
        List<TimeValuePair> result = new ArrayList<>();

        if (compressed == null || compressed.length < 16) {
            return result;
        }

        ByteBuffer buffer = ByteBuffer.wrap(compressed);

        long firstTimestamp = buffer.getLong();
        double firstValue = buffer.getDouble();
        result.add(new TimeValuePair(firstTimestamp, firstValue));

        long prevTimestamp = firstTimestamp;
        double prevValue = firstValue;
        long prevTimestampDelta = 0;

        while (buffer.hasRemaining()) {
            int timestampFlag = buffer.get();
            long timestampDelta;

            if (timestampFlag == 0) {
                timestampDelta = prevTimestampDelta;
            } else {
                timestampDelta = buffer.getLong();
            }

            int valueFlag = buffer.get();
            double valueDelta;

            if (valueFlag == 0) {
                valueDelta = 0;
            } else {
                valueDelta = buffer.getDouble();
            }

            long currentTimestamp = prevTimestamp + timestampDelta;
            double currentValue = prevValue + valueDelta;

            result.add(new TimeValuePair(currentTimestamp, currentValue));

            prevTimestamp = currentTimestamp;
            prevValue = currentValue;
            prevTimestampDelta = timestampDelta;
        }

        return result;
    }

    @Data
    public static class TimeValuePair {
        private final long timestamp;
        private final double value;

        public TimeValuePair(long timestamp, double value) {
            this.timestamp = timestamp;
            this.value = value;
        }
    }
}
