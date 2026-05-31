import { Route, ProxyRequest, ProxyResponse } from '../types';
import { IProtocolAdapter } from '../interfaces';
import axios, { AxiosRequestConfig } from 'axios';

export class HttpAdapter implements IProtocolAdapter {
  protocol: 'http' | 'grpc' | 'websocket' | 'graphql' = 'http';

  adaptRequest(request: ProxyRequest, route: Route): AxiosRequestConfig {
    const target = route.target;
    const targetPath = target.path || request.path;

    let convertedBody = request.body;
    const convertedHeaders = { ...request.headers };

    for (const transformation of route.transformations) {
      if (transformation.type === 'request') {
        if (transformation.action === 'add-header') {
          const name = transformation.config.name as string;
          const value = transformation.config.value as string;
          convertedHeaders[name] = value;
        } else if (transformation.action === 'remove-header') {
          const name = transformation.config.name as string;
          delete convertedHeaders[name];
        }
      }
    }

    const url = `${target.protocol}://${target.host}:${target.port}${targetPath}`;

    return {
      method: request.method.toLowerCase(),
      url,
      headers: convertedHeaders,
      params: request.query,
      data: convertedBody,
      timeout: route.timeoutMs,
      validateStatus: () => true,
    };
  }

  adaptResponse(axiosResponse: any, route: Route): ProxyResponse {
    let body = axiosResponse.data;
    const headers = { ...axiosResponse.headers };

    for (const transformation of route.transformations) {
      if (transformation.type === 'response') {
        if (transformation.action === 'add-header') {
          const name = transformation.config.name as string;
          const value = transformation.config.value as string;
          headers[name] = value;
        } else if (transformation.action === 'remove-header') {
          const name = transformation.config.name as string;
          delete headers[name];
        }
      }
    }

    return {
      status: axiosResponse.status,
      headers,
      body,
      latencyMs: 0,
    };
  }
}

export const httpAdapter = new HttpAdapter();
