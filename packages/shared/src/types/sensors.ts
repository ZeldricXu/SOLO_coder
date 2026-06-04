import { Vec3 } from './vectors';

export type SensorType = 
  | 'displacement' 
  | 'velocity' 
  | 'acceleration' 
  | 'force' 
  | 'electricField'
  | 'magneticField'
  | 'temperature'
  | 'pressure'
  | 'current';

export interface Sensor {
  id: string;
  name: string;
  type: SensorType;
  position: Vec3;
  targetObjectId?: string;
  samplingRate: number;
  enabled: boolean;
  color: string;
}

export interface SensorDataPoint {
  time: number;
  value: number | Vec3;
}

export interface SensorData {
  sensorId: string;
  data: SensorDataPoint[];
}

export interface DataAnalysisResult {
  mean: number;
  max: number;
  min: number;
  rms: number;
  stdDev: number;
  variance: number;
}

export interface FFTSpectrum {
  frequencies: number[];
  magnitudes: number[];
  phases: number[];
}

export type CurveFitType = 'linear' | 'quadratic' | 'exponential' | 'sine';

export interface CurveFitResult {
  type: CurveFitType;
  parameters: number[];
  rSquared: number;
  equation: string;
}
