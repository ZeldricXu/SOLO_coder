import { lttbDownsample, downsampleForZoom, dynamicDownsample } from '../src/downsampling';

interface DataPoint {
  t: number;
  v: number;
}

describe('LTTB (Largest Triangle Three Buckets) Downsampling', () => {
  describe('lttbDownsample', () => {
    it('should preserve first and last data points', () => {
      const data: DataPoint[] = [];
      for (let i = 0; i < 100; i++) {
        data.push({ t: i, v: Math.sin(i * 0.1) });
      }

      const downsampled = lttbDownsample(data, 10);

      expect(downsampled.length).toBe(10);
      expect(downsampled[0].t).toBe(data[0].t);
      expect(downsampled[0].v).toBe(data[0].v);
      expect(downsampled[downsampled.length - 1].t).toBe(data[data.length - 1].t);
      expect(downsampled[downsampled.length - 1].v).toBe(data[data.length - 1].v);
    });

    it('should return original data when threshold >= data length', () => {
      const data: DataPoint[] = [];
      for (let i = 0; i < 10; i++) {
        data.push({ t: i, v: Math.random() });
      }

      const downsampled = lttbDownsample(data, 20);

      expect(downsampled).toEqual(data);
    });

    it('should handle edge case of threshold = 2', () => {
      const data: DataPoint[] = [];
      for (let i = 0; i < 100; i++) {
        data.push({ t: i, v: i * i });
      }

      const downsampled = lttbDownsample(data, 2);

      expect(downsampled.length).toBe(2);
      expect(downsampled[0]).toEqual(data[0]);
      expect(downsampled[1]).toEqual(data[data.length - 1]);
    });

    it('should preserve peaks and valleys', () => {
      const data: DataPoint[] = [];
      for (let i = 0; i < 100; i++) {
        const t = i * 0.1;
        data.push({ t, v: Math.sin(t) });
      }

      const downsampled = lttbDownsample(data, 20);

      const values = downsampled.map(d => d.v);
      const maxVal = Math.max(...values);
      const minVal = Math.min(...values);

      expect(maxVal).toBeGreaterThan(0.9);
      expect(minVal).toBeLessThan(-0.9);
    });

    it('should correctly downsample linear data', () => {
      const data: DataPoint[] = [];
      for (let i = 0; i < 1000; i++) {
        data.push({ t: i, v: 2 * i + 5 });
      }

      const downsampled = lttbDownsample(data, 50);

      for (const pt of downsampled) {
        const expectedV = 2 * pt.t + 5;
        expect(pt.v).toBeCloseTo(expectedV);
      }
    });
  });

  describe('dynamicDownsample', () => {
    it('should downsample based on viewport width', () => {
      const data: DataPoint[] = [];
      for (let i = 0; i < 10000; i++) {
        data.push({ t: i, v: Math.sin(i * 0.01) });
      }

      const viewportWidth = 800;
      const maxPointsPerPixel = 2;
      const downsampled = dynamicDownsample(data, viewportWidth, maxPointsPerPixel);

      expect(downsampled.length).toBeLessThanOrEqual(viewportWidth * maxPointsPerPixel + 1);
      expect(downsampled.length).toBeGreaterThan(0);
    });
  });

  describe('downsampleForZoom', () => {
    it('should only include data points in the time range', () => {
      const data: DataPoint[] = [];
      for (let i = 0; i < 1000; i++) {
        data.push({ t: i, v: Math.sin(i * 0.1) });
      }

      const startTime = 200;
      const endTime = 500;
      const viewportWidth = 500;
      const maxPointsPerPixel = 2;

      const downsampled = downsampleForZoom(data, startTime, endTime, viewportWidth, maxPointsPerPixel);

      for (const pt of downsampled) {
        expect(pt.t).toBeGreaterThanOrEqual(startTime);
        expect(pt.t).toBeLessThanOrEqual(endTime);
      }
    });

    it('should show full precision when zoomed in tightly', () => {
      const data: DataPoint[] = [];
      for (let i = 0; i < 10000; i++) {
        data.push({ t: i * 0.01, v: Math.sin(i * 0.01) });
      }

      const startTime = 5;
      const endTime = 5.1;
      const viewportWidth = 1000;
      const maxPointsPerPixel = 2;

      const downsampled = downsampleForZoom(data, startTime, endTime, viewportWidth, maxPointsPerPixel);

      const expectedPoints = Math.floor((endTime - startTime) / 0.01) + 1;
      expect(downsampled.length).toBeGreaterThanOrEqual(expectedPoints * 0.8);
    });

    it('should heavily downsample when viewing full range', () => {
      const data: DataPoint[] = [];
      for (let i = 0; i < 100000; i++) {
        data.push({ t: i * 0.001, v: Math.sin(i * 0.001) });
      }

      const startTime = 0;
      const endTime = 100;
      const viewportWidth = 800;
      const maxPointsPerPixel = 2;

      const downsampled = downsampleForZoom(data, startTime, endTime, viewportWidth, maxPointsPerPixel);

      expect(downsampled.length).toBeLessThanOrEqual(viewportWidth * maxPointsPerPixel + 1);
      expect(downsampled.length).toBeLessThan(data.length / 10);
    });

    it('should preserve visual features at different zoom levels', () => {
      const data: DataPoint[] = [];
      for (let i = 0; i < 10000; i++) {
        const t = i * 0.01;
        let v = Math.sin(t);
        if (t >= 50 && t <= 52) {
          v += 5 * Math.sin(t * 50);
        }
        data.push({ t, v });
      }

      const startTime = 50;
      const endTime = 52;
      const viewportWidth = 1000;
      const downsampled = downsampleForZoom(data, startTime, endTime, viewportWidth);

      const values = downsampled.map(d => d.v);
      const hasHighFreq = values.some((v, i) => 
        i > 0 && Math.abs(v - values[i - 1]) > 2
      );
      expect(hasHighFreq).toBe(true);
    });
  });

  describe('Performance', () => {
    it('should handle 1 million data points efficiently', () => {
      const N = 1000000;
      const data: DataPoint[] = new Array(N);
      for (let i = 0; i < N; i++) {
        data[i] = { t: i * 0.001, v: Math.sin(i * 0.001) + Math.random() * 0.1 };
      }

      const startTime = Date.now();
      const downsampled = lttbDownsample(data, 1000);
      const duration = Date.now() - startTime;

      expect(downsampled.length).toBe(1000);
      expect(duration).toBeLessThan(2000);
    });
  });

  describe('Integration with typical sensor data', () => {
    it('should handle 1000Hz sensor data for 10 seconds', () => {
      const sampleRate = 1000;
      const duration = 10;
      const N = sampleRate * duration;
      const data: DataPoint[] = new Array(N);

      for (let i = 0; i < N; i++) {
        const t = i / sampleRate;
        const v = Math.sin(2 * Math.PI * 10 * t) + 0.5 * Math.sin(2 * Math.PI * 50 * t);
        data[i] = { t, v };
      }

      const viewportWidth = 1000;
      const downsampled = downsampleForZoom(data, 0, duration, viewportWidth, 2);

      expect(downsampled.length).toBeLessThanOrEqual(viewportWidth * 2 + 1);
      expect(downsampled[0].t).toBe(0);
      expect(downsampled[downsampled.length - 1].t).toBeCloseTo(duration);
    });
  });
});
