import { Request, Response, NextFunction } from 'express';
import { ZodError } from 'zod';
import { AppError, errorHandler as appErrorHandler } from '../utils/errors';
import logger from '../utils/logger';

export const errorMiddleware = (
  error: unknown,
  _req: Request,
  res: Response,
  _next: NextFunction,
): void => {
  logger.error({ error }, 'Request error');

  if (error instanceof ZodError) {
    const issues = error.issues.map(issue => ({
      path: issue.path.join('.'),
      message: issue.message,
    }));
    res.status(422).json({
      code: 422,
      error: 'Validation failed',
      details: issues,
    });
    return;
  }

  const { code, message, details } = appErrorHandler(error);
  res.status(code).json({
    code,
    error: message,
    details,
  });
};

export const notFoundMiddleware = (_req: Request, res: Response): void => {
  res.status(404).json({
    code: 404,
    error: 'Not Found',
    message: 'The requested resource does not exist',
  });
};

export const asyncHandler = (fn: (req: Request, res: Response, next: NextFunction) => Promise<unknown>) => {
  return (req: Request, res: Response, next: NextFunction): void => {
    Promise.resolve(fn(req, res, next)).catch(next);
  };
};
