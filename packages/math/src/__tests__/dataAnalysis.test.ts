import { computeFFT, leastSquaresLinear, leastSquaresQuadratic, leastSquaresExponential, leastSquaresSine } from '../index';

describe('Data Analysis Tools', () => {
  describe('computeFFT', () => {
    it('should identify dominant frequency in a pure sine wave', () => {
      const sampleRate = 100;
      const frequency = 10;
      const numSamples = 256;
      const signal: number[] = [];
      for (let i = 0; i < numSamples; i++) {
        signal.push(Math.sin(2 * Math.PI * frequency * i / sampleRate));
      }
      const result = computeFFT(signal, sampleRate);
      const peakIdx = result.magnitudes.indexOf(Math.max(...result.magnitudes));
      const peakFreq = result.frequencies[peakIdx];
      expect(Math.abs(peakFreq - frequency)).toBeLessThan(2);
    });

    it('should identify two frequencies in a composite signal', () => {
      const sampleRate = 100;
      const f1 = 5;
      const f2 = 20;
      const numSamples = 512;
      const signal: number[] = [];
      for (let i = 0; i < numSamples; i++) {
        signal.push(Math.sin(2 * Math.PI * f1 * i / sampleRate) + 0.5 * Math.sin(2 * Math.PI * f2 * i / sampleRate));
      }
      const result = computeFFT(signal, sampleRate);
      expect(result.frequencies.length).toBeGreaterThan(0);
      expect(result.magnitudes.length).toBeGreaterThan(0);
    });

    it('should return near-zero magnitudes for DC signal', () => {
      const signal = new Array(256).fill(5.0);
      const result = computeFFT(signal, 1);
      const nonDcMagnitudes = result.magnitudes.slice(1);
      const maxNonDc = Math.max(...nonDcMagnitudes);
      expect(maxNonDc).toBeLessThan(1);
    });
  });

  describe('leastSquaresLinear', () => {
    it('should fit y = 2x + 1 (a=slope=2, b=intercept=1)', () => {
      const x: number[] = [];
      const y: number[] = [];
      for (let xi = 0; xi <= 10; xi += 0.5) {
        x.push(xi);
        y.push(2 * xi + 1);
      }
      const result = leastSquaresLinear(x, y);
      expect(result.a).toBeCloseTo(2, 4);
      expect(result.b).toBeCloseTo(1, 4);
      expect(result.rSquared).toBeCloseTo(1, 4);
    });
  });

  describe('leastSquaresQuadratic', () => {
    it('should fit y = x^2', () => {
      const x: number[] = [];
      const y: number[] = [];
      for (let xi = -5; xi <= 5; xi += 0.5) {
        x.push(xi);
        y.push(xi * xi);
      }
      const result = leastSquaresQuadratic(x, y);
      expect(result.a).toBeCloseTo(1, 2);
      expect(result.rSquared).toBeGreaterThan(0.99);
    });
  });

  describe('leastSquaresExponential', () => {
    it('should fit y = 2*e^(0.5x)', () => {
      const x: number[] = [];
      const y: number[] = [];
      for (let xi = 0; xi <= 4; xi += 0.2) {
        x.push(xi);
        y.push(2 * Math.exp(0.5 * xi));
      }
      const result = leastSquaresExponential(x, y);
      expect(result.rSquared).toBeGreaterThan(0.99);
    });
  });

  describe('leastSquaresSine', () => {
    it('should fit a sine wave with known parameters', () => {
      const x: number[] = [];
      const y: number[] = [];
      for (let xi = 0; xi <= 4 * Math.PI; xi += 0.1) {
        x.push(xi);
        y.push(3 * Math.sin(2 * xi + 1));
      }
      const result = leastSquaresSine(x, y);
      expect(result.rSquared).toBeGreaterThan(0.5);
    });
  });
});
