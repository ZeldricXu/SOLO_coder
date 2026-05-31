import { RequestContext } from '../types';
import { generateTraceId } from './idGenerator';

export function createContext(namespace: string, userId?: string): RequestContext {
  return {
    traceId: generateTraceId(),
    startTime: Date.now(),
    namespace,
    userId
  };
}

export function getElapsedTime(ctx: RequestContext): number {
  return Date.now() - ctx.startTime;
}
