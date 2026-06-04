export function complex(re = 0, im = 0) {
    return { re, im };
}
export function complexAdd(a, b) {
    return { re: a.re + b.re, im: a.im + b.im };
}
export function complexSub(a, b) {
    return { re: a.re - b.re, im: a.im - b.im };
}
export function complexMul(a, b) {
    return {
        re: a.re * b.re - a.im * b.im,
        im: a.re * b.im + a.im * b.re,
    };
}
export function complexScale(a, s) {
    return { re: a.re * s, im: a.im * s };
}
export function complexMagnitude(c) {
    return Math.sqrt(c.re * c.re + c.im * c.im);
}
export function complexPhase(c) {
    return Math.atan2(c.im, c.re);
}
export function complexPower(c) {
    return c.re * c.re + c.im * c.im;
}
export function conjugate(c) {
    return { re: c.re, im: -c.im };
}
function nextPowerOf2(n) {
    let count = 0;
    if (n && !(n & (n - 1))) {
        return n;
    }
    while (n !== 0) {
        n >>= 1;
        count += 1;
    }
    return 1 << count;
}
export function fft(input, inverse = false) {
    const n = input.length;
    if (n === 0)
        return [];
    const m = nextPowerOf2(n);
    const padded = new Array(m).fill(null).map((_, i) => i < n ? { ...input[i] } : complex(0, 0));
    const result = recursiveFFT(padded, inverse);
    if (inverse) {
        for (let i = 0; i < m; i++) {
            result[i] = complexScale(result[i], 1 / m);
        }
    }
    return result;
}
function recursiveFFT(input, inverse) {
    const n = input.length;
    if (n === 1) {
        return [{ ...input[0] }];
    }
    const half = n / 2;
    const even = new Array(half);
    const odd = new Array(half);
    for (let i = 0; i < half; i++) {
        even[i] = { ...input[2 * i] };
        odd[i] = { ...input[2 * i + 1] };
    }
    const evenResult = recursiveFFT(even, inverse);
    const oddResult = recursiveFFT(odd, inverse);
    const result = new Array(n);
    const sign = inverse ? 1 : -1;
    for (let i = 0; i < half; i++) {
        const angle = sign * 2 * Math.PI * i / n;
        const twiddle = complex(Math.cos(angle), Math.sin(angle));
        const t = complexMul(twiddle, oddResult[i]);
        result[i] = complexAdd(evenResult[i], t);
        result[i + half] = complexSub(evenResult[i], t);
    }
    return result;
}
export function fftReal(input, inverse = false) {
    const complexInput = input.map(re => complex(re, 0));
    return fft(complexInput, inverse);
}
export function computeFFT(timeSeries, samplingRate) {
    const n = timeSeries.length;
    const complexInput = timeSeries.map(re => complex(re, 0));
    const fftResult = fft(complexInput);
    const m = fftResult.length;
    const frequencies = new Array(m / 2);
    const magnitudes = new Array(m / 2);
    const phases = new Array(m / 2);
    const power = new Array(m / 2);
    const freqStep = samplingRate / m;
    for (let i = 0; i < m / 2; i++) {
        frequencies[i] = i * freqStep;
        magnitudes[i] = complexMagnitude(fftResult[i]) * 2 / n;
        phases[i] = complexPhase(fftResult[i]);
        power[i] = complexPower(fftResult[i]) * 2 / (n * n);
    }
    return { frequencies, magnitudes, phases, power, complex: fftResult };
}
export function inverseFFT(input) {
    return fft(input, true);
}
export function ifftReal(input) {
    const result = inverseFFT(input);
    return result.map(c => c.re);
}
export function powerSpectralDensity(timeSeries, samplingRate, windowSize = 1024, overlap = 0.5) {
    const step = Math.floor(windowSize * (1 - overlap));
    const numSegments = Math.floor((timeSeries.length - windowSize) / step) + 1;
    const window = hannWindow(windowSize);
    const windowPower = window.reduce((sum, w) => sum + w * w, 0);
    const m = nextPowerOf2(windowSize);
    const frequencies = new Array(m / 2);
    const freqStep = samplingRate / m;
    for (let i = 0; i < m / 2; i++) {
        frequencies[i] = i * freqStep;
    }
    const psd = new Array(m / 2).fill(0);
    for (let seg = 0; seg < numSegments; seg++) {
        const start = seg * step;
        const segment = timeSeries.slice(start, start + windowSize);
        const windowed = segment.map((v, i) => v * window[i]);
        const padded = new Array(m).fill(0);
        for (let i = 0; i < windowSize; i++)
            padded[i] = windowed[i];
        const spectrum = fftReal(padded);
        for (let i = 0; i < m / 2; i++) {
            const mag = complexMagnitude(spectrum[i]);
            psd[i] += (mag * mag * 2) / (samplingRate * windowPower);
        }
    }
    for (let i = 0; i < m / 2; i++) {
        psd[i] /= numSegments;
    }
    return { frequencies, psd };
}
export function hannWindow(n) {
    const w = new Array(n);
    for (let i = 0; i < n; i++) {
        w[i] = 0.5 * (1 - Math.cos(2 * Math.PI * i / (n - 1)));
    }
    return w;
}
export function blackmanWindow(n) {
    const w = new Array(n);
    const a0 = 0.42, a1 = 0.5, a2 = 0.08;
    for (let i = 0; i < n; i++) {
        const t = 2 * Math.PI * i / (n - 1);
        w[i] = a0 - a1 * Math.cos(t) + a2 * Math.cos(2 * t);
    }
    return w;
}
export function convolve(signal, kernel) {
    const n = signal.length;
    const m = kernel.length;
    const result = new Array(n + m - 1).fill(0);
    for (let i = 0; i < n; i++) {
        for (let j = 0; j < m; j++) {
            result[i + j] += signal[i] * kernel[j];
        }
    }
    return result;
}
export function crossCorrelation(a, b) {
    const n = a.length;
    const m = b.length;
    const result = new Array(n + m - 1).fill(0);
    for (let lag = 0; lag < n + m - 1; lag++) {
        const start = Math.max(0, lag - m + 1);
        const end = Math.min(n, lag + 1);
        for (let i = start; i < end; i++) {
            result[lag] += a[i] * b[lag - i];
        }
    }
    return result;
}
export function autoCorrelation(signal) {
    return crossCorrelation(signal, signal);
}
export const FFT = {
    complex, complexAdd, complexSub, complexMul, complexScale,
    complexMagnitude, complexPhase, complexPower, conjugate,
    fft, fftReal, computeFFT, inverseFFT, ifftReal,
    powerSpectralDensity, hannWindow, blackmanWindow,
    convolve, crossCorrelation, autoCorrelation,
};
//# sourceMappingURL=fft.js.map