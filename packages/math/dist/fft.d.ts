export interface Complex {
    re: number;
    im: number;
}
export interface FFTResult {
    frequencies: number[];
    magnitudes: number[];
    phases: number[];
    power: number[];
    complex: Complex[];
}
export declare function complex(re?: number, im?: number): Complex;
export declare function complexAdd(a: Complex, b: Complex): Complex;
export declare function complexSub(a: Complex, b: Complex): Complex;
export declare function complexMul(a: Complex, b: Complex): Complex;
export declare function complexScale(a: Complex, s: number): Complex;
export declare function complexMagnitude(c: Complex): number;
export declare function complexPhase(c: Complex): number;
export declare function complexPower(c: Complex): number;
export declare function conjugate(c: Complex): Complex;
export declare function fft(input: Complex[], inverse?: boolean): Complex[];
export declare function fftReal(input: number[], inverse?: boolean): Complex[];
export declare function computeFFT(timeSeries: number[], samplingRate: number): FFTResult;
export declare function inverseFFT(input: Complex[]): Complex[];
export declare function ifftReal(input: Complex[]): number[];
export declare function powerSpectralDensity(timeSeries: number[], samplingRate: number, windowSize?: number, overlap?: number): {
    frequencies: number[];
    psd: number[];
};
export declare function hannWindow(n: number): number[];
export declare function blackmanWindow(n: number): number[];
export declare function convolve(signal: number[], kernel: number[]): number[];
export declare function crossCorrelation(a: number[], b: number[]): number[];
export declare function autoCorrelation(signal: number[]): number[];
export declare const FFT: {
    complex: typeof complex;
    complexAdd: typeof complexAdd;
    complexSub: typeof complexSub;
    complexMul: typeof complexMul;
    complexScale: typeof complexScale;
    complexMagnitude: typeof complexMagnitude;
    complexPhase: typeof complexPhase;
    complexPower: typeof complexPower;
    conjugate: typeof conjugate;
    fft: typeof fft;
    fftReal: typeof fftReal;
    computeFFT: typeof computeFFT;
    inverseFFT: typeof inverseFFT;
    ifftReal: typeof ifftReal;
    powerSpectralDensity: typeof powerSpectralDensity;
    hannWindow: typeof hannWindow;
    blackmanWindow: typeof blackmanWindow;
    convolve: typeof convolve;
    crossCorrelation: typeof crossCorrelation;
    autoCorrelation: typeof autoCorrelation;
};
//# sourceMappingURL=fft.d.ts.map