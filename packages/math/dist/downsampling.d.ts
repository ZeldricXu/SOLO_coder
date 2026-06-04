export interface DataPoint {
    time: number;
    value: number;
}
export declare function lttbDownsample(data: DataPoint[], threshold: number): DataPoint[];
export declare function dynamicDownsample(data: DataPoint[], viewportWidth: number, maxPointsPerPixel?: number, minThreshold?: number, maxThreshold?: number): DataPoint[];
export declare function downsampleForZoom(data: DataPoint[], startTime: number, endTime: number, viewportWidth: number, maxPointsPerPixel?: number): DataPoint[];
//# sourceMappingURL=downsampling.d.ts.map