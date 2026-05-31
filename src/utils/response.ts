import { Response } from 'express';
import { ApiResponse, ApiError } from '../types';
import { AppError } from './errors';

export class ResponseUtils {
  static success<T>(res: Response, data?: T, message?: string): Response<ApiResponse<T>> {
    return res.status(200).json({
      code: 200,
      data,
      message,
    });
  }

  static created<T>(res: Response, data?: T, message?: string): Response<ApiResponse<T>> {
    return res.status(201).json({
      code: 201,
      data,
      message,
    });
  }

  static noContent(res: Response): Response<ApiResponse<void>> {
    return res.status(204).send();
  }

  static error(res: Response, error: AppError | Error): Response<ApiResponse<void>> {
    if (error instanceof AppError) {
      return res.status(error.statusCode).json({
        code: error.statusCode,
        message: error.message,
        errors: error.details ? this.formatErrors(error.details) : undefined,
      });
    }

    return res.status(500).json({
      code: 500,
      message: 'Internal Server Error',
    });
  }

  static badRequest(res: Response, message: string, details?: any): Response<ApiResponse<void>> {
    return res.status(400).json({
      code: 400,
      message,
      errors: details ? this.formatErrors(details) : undefined,
    });
  }

  static unauthorized(res: Response, message: string = 'Unauthorized'): Response<ApiResponse<void>> {
    return res.status(401).json({
      code: 401,
      message,
    });
  }

  static forbidden(res: Response, message: string = 'Forbidden'): Response<ApiResponse<void>> {
    return res.status(403).json({
      code: 403,
      message,
    });
  }

  static notFound(res: Response, message: string = 'Resource not found'): Response<ApiResponse<void>> {
    return res.status(404).json({
      code: 404,
      message,
    });
  }

  static conflict(res: Response, message: string, details?: any): Response<ApiResponse<void>> {
    return res.status(409).json({
      code: 409,
      message,
      errors: details ? this.formatErrors(details) : undefined,
    });
  }

  static paginated<T>(
    res: Response,
    items: T[],
    total: number,
    page: number,
    pageSize: number
  ): Response<ApiResponse<{ items: T[]; total: number; page: number; pageSize: number; totalPages: number }>> {
    const totalPages = Math.ceil(total / pageSize);
    return res.status(200).json({
      code: 200,
      data: {
        items,
        total,
        page,
        pageSize,
        totalPages,
      },
    });
  }

  private static formatErrors(details: any): ApiError[] {
    if (Array.isArray(details)) {
      return details.map((detail: any) => ({
        field: detail.path?.join('.') || detail.field,
        message: detail.message,
        code: detail.code,
      }));
    }

    if (typeof details === 'object' && details !== null) {
      return [{
        field: details.field,
        message: details.message,
        code: details.code,
      }];
    }

    return [{ message: String(details) }];
  }
}

export default ResponseUtils;
