export interface CommandBase {
  tenantId: string;
  traceId?: string;
}

export interface QueryBase {
  tenantId: string;
  traceId?: string;
}

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  traceId: string;
  timestamp: string;
}

export interface ApiPaginatedResponse<T> {
  code: number;
  message: string;
  data: {
    items: T[];
    total: number;
    page: number;
    pageSize: number;
    totalPages: number;
  };
  traceId: string;
  timestamp: string;
}

export interface ApiErrorResponse {
  code: number;
  error: string;
  message: string;
  details?: unknown;
  traceId: string;
  timestamp: string;
}

export const createSuccessResponse = <T>(data: T, traceId: string): ApiResponse<T> => ({
  code: 200,
  message: 'Success',
  data,
  traceId,
  timestamp: new Date().toISOString()
});

export const createCreatedResponse = <T>(data: T, traceId: string): ApiResponse<T> => ({
  code: 201,
  message: 'Created',
  data,
  traceId,
  timestamp: new Date().toISOString()
});

export const createPaginatedResponse = <T>(
  data: { items: T[]; total: number; page: number; pageSize: number; totalPages: number },
  traceId: string
): ApiPaginatedResponse<T> => ({
  code: 200,
  message: 'Success',
  data,
  traceId,
  timestamp: new Date().toISOString()
});

export const createErrorResponse = (
  code: number,
  message: string,
  error: string,
  traceId: string,
  details?: unknown
): ApiErrorResponse => ({
  code,
  error,
  message,
  details,
  traceId,
  timestamp: new Date().toISOString()
});
