export interface DataPoint {
  time: number;
  value: number;
}

export function lttbDownsample(data: DataPoint[], threshold: number): DataPoint[] {
  if (threshold >= data.length || threshold <= 2) {
    return data.slice();
  }

  const sampled: DataPoint[] = [];
  const bucketSize = (data.length - 2) / (threshold - 2);

  let a = 0;
  sampled.push(data[a]);

  for (let i = 0; i < threshold - 2; i++) {
    const avgRangeStart = Math.floor((i + 1) * bucketSize) + 1;
    const avgRangeEnd = Math.floor((i + 2) * bucketSize) + 1;
    const avgRangeEndClamped = Math.min(avgRangeEnd, data.length);

    let avgX = 0;
    let avgY = 0;
    const avgRangeLength = avgRangeEndClamped - avgRangeStart;

    for (let j = avgRangeStart; j < avgRangeEndClamped; j++) {
      avgX += data[j].time;
      avgY += data[j].value;
    }
    avgX /= avgRangeLength;
    avgY /= avgRangeLength;

    const rangeOffs = Math.floor(i * bucketSize) + 1;
    const rangeTo = Math.floor((i + 1) * bucketSize) + 1;

    const pointA = data[a];
    const pointAx = pointA.time;
    const pointAy = pointA.value;

    let maxArea = -1;
    let nextA = rangeOffs;

    for (let j = rangeOffs; j < rangeTo; j++) {
      const area = Math.abs(
        (pointAx - avgX) * (data[j].value - pointAy) -
        (pointAx - data[j].time) * (avgY - pointAy)
      ) * 0.5;

      if (area > maxArea) {
        maxArea = area;
        nextA = j;
      }
    }

    sampled.push(data[nextA]);
    a = nextA;
  }

  sampled.push(data[data.length - 1]);
  return sampled;
}

export function dynamicDownsample(
  data: DataPoint[],
  viewportWidth: number,
  maxPointsPerPixel: number = 2,
  minThreshold: number = 100,
  maxThreshold: number = 5000
): DataPoint[] {
  const threshold = Math.max(minThreshold, Math.min(maxThreshold, Math.floor(viewportWidth * maxPointsPerPixel)));
  return lttbDownsample(data, threshold);
}

export function downsampleForZoom(
  data: DataPoint[],
  startTime: number,
  endTime: number,
  viewportWidth: number,
  maxPointsPerPixel: number = 2
): DataPoint[] {
  const visibleData = data.filter(d => d.time >= startTime && d.time <= endTime);

  if (visibleData.length === 0) {
    const firstIdx = Math.max(0, bisectLeft(data, startTime) - 1);
    const lastIdx = Math.min(data.length - 1, bisectRight(data, endTime) + 1);
    return data.slice(firstIdx, lastIdx + 1);
  }

  const totalDuration = data[data.length - 1].time - data[0].time;
  const visibleDuration = endTime - startTime;
  const zoomRatio = totalDuration / Math.max(visibleDuration, 1e-9);

  const baseThreshold = viewportWidth * maxPointsPerPixel;
  const threshold = Math.min(data.length, Math.floor(baseThreshold * Math.min(zoomRatio, 10)));

  if (zoomRatio < 1.1) {
    return visibleData;
  }

  return lttbDownsample(visibleData, Math.max(100, threshold));
}

function bisectLeft(data: DataPoint[], target: number): number {
  let low = 0;
  let high = data.length;
  while (low < high) {
    const mid = Math.floor((low + high) / 2);
    if (data[mid].time < target) {
      low = mid + 1;
    } else {
      high = mid;
    }
  }
  return low;
}

function bisectRight(data: DataPoint[], target: number): number {
  let low = 0;
  let high = data.length;
  while (low < high) {
    const mid = Math.floor((low + high) / 2);
    if (data[mid].time <= target) {
      low = mid + 1;
    } else {
      high = mid;
    }
  }
  return low;
}
