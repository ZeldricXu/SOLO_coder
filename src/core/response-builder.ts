import { IAPIResponseBuilder } from '@ports/index';
import { APIResponse } from '@apptypes/index';

export class APIResponseBuilder implements IAPIResponseBuilder {
  success<T>(data: T, pagination?: APIResponse['pagination']): APIResponse<T> {
    return {
      code: 200,
      data,
      ...(pagination ? { pagination } : {}),
    };
  }

  error(code: number, message: string): APIResponse {
    return {
      code,
      message,
    };
  }

  conflict(resourceId: string): APIResponse {
    return {
      code: 409,
      message: 'Resource conflict',
      data: {
        resource_id: resourceId,
      },
    };
  }

  created<T>(data: T): APIResponse<T> {
    return {
      code: 201,
      data,
    };
  }

  noContent(): APIResponse {
    return {
      code: 204,
    };
  }

  notFound(message: string = 'Resource not found'): APIResponse {
    return {
      code: 404,
      message,
    };
  }

  badRequest(message: string): APIResponse {
    return {
      code: 400,
      message,
    };
  }

  unauthorized(message: string = 'Unauthorized'): APIResponse {
    return {
      code: 401,
      message,
    };
  }

  forbidden(message: string = 'Forbidden'): APIResponse {
    return {
      code: 403,
      message,
    };
  }

  tooManyRequests(message: string = 'Rate limit exceeded'): APIResponse {
    return {
      code: 429,
      message,
    };
  }

  internalError(message: string = 'Internal server error'): APIResponse {
    return {
      code: 500,
      message,
    };
  }

  timeout(message: string = 'Request timeout'): APIResponse {
    return {
      code: 504,
      message,
    };
  }
}

export const responseBuilder = new APIResponseBuilder();
