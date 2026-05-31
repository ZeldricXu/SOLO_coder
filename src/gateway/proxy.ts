import { Route, ProxyRequest, ProxyResponse } from './types';
import { logger } from '../utils/common';
import axios, { AxiosRequestConfig } from 'axios';
import { httpAdapter } from './adapters/HttpAdapter';
import { httpClient } from './handlers/HttpClient';

export class ProtocolConverter {
  convertRequest(request: ProxyRequest, route: Route): AxiosRequestConfig {
    return httpAdapter.adaptRequest(request, route);
  }

  convertResponse(axiosResponse: any, route: Route): ProxyResponse {
    return httpAdapter.adaptResponse(axiosResponse, route);
  }

  convertToGRPC(request: ProxyRequest): unknown {
    return {
      service: request.headers['x-grpc-service'],
      method: request.headers['x-grpc-method'],
      payload: request.body,
    };
  }

  convertToWebSocket(request: ProxyRequest): unknown {
    return {
      path: request.path,
      headers: request.headers,
      query: request.query,
    };
  }
}

export const protocolConverter = new ProtocolConverter();

export class RequestExecutor {
  private converter: ProtocolConverter;

  constructor(converter?: ProtocolConverter) {
    this.converter = converter || protocolConverter;
  }

  async execute(request: ProxyRequest, route: Route): Promise<ProxyResponse> {
    return httpClient.execute(request, route);
  }
}

export const requestExecutor = new RequestExecutor();
