import { Route, ProxyRequest, ProxyResponse } from '../types';

export interface IProtocolAdapter {
  protocol: 'http' | 'grpc' | 'websocket' | 'graphql';
  adaptRequest(request: ProxyRequest, route: Route): unknown;
  adaptResponse(response: unknown, route: Route): ProxyResponse;
}

export interface IHttpClient {
  execute(request: ProxyRequest, route: Route): Promise<ProxyResponse>;
}

export interface IRequestHandler {
  handle(request: ProxyRequest, route: Route): Promise<ProxyResponse>;
  canHandle(route: Route): boolean;
}

export interface IGatewayMiddleware {
  name: string;
  preRequest?(request: ProxyRequest, route: Route): Promise<ProxyRequest>;
  postRequest?(response: ProxyResponse, route: Route): Promise<ProxyResponse>;
  onError?(error: Error, request: ProxyRequest, route: Route): Promise<ProxyResponse>;
}

export interface GatewayHandlerOptions {
  timeoutMs?: number;
  retries?: number;
}
