import { Route, ProxyRequest, ProxyResponse } from '../types';
import { IHttpClient } from '../interfaces';
import { httpAdapter } from '../adapters/HttpAdapter';
import axios from 'axios';
import { logger } from '../../utils/common';

export class HttpClient implements IHttpClient {
  async execute(request: ProxyRequest, route: Route): Promise<ProxyResponse> {
    const startTime = Date.now();

    logger.debug(`Executing HTTP proxy request`, {
      method: request.method,
      path: request.path,
      target: `${route.target.protocol}://${route.target.host}:${route.target.port}`,
    });

    try {
      const axiosConfig = httpAdapter.adaptRequest(request, route);
      const response = await axios(axiosConfig);
      const proxyResponse = httpAdapter.adaptResponse(response, route);
      proxyResponse.latencyMs = Date.now() - startTime;

      logger.debug(`HTTP proxy request completed`, {
        status: proxyResponse.status,
        latencyMs: proxyResponse.latencyMs,
      });

      return proxyResponse;
    } catch (error) {
      logger.error(`HTTP proxy request failed`, {
        error: error instanceof Error ? error.message : 'Unknown error',
      });

      return {
        status: 502,
        headers: { 'content-type': 'application/json' },
        body: {
          error: 'Bad Gateway',
          message: error instanceof Error ? error.message : 'Unknown error',
        },
        latencyMs: Date.now() - startTime,
      };
    }
  }
}

export const httpClient = new HttpClient();
