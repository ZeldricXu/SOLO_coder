import {
  ExceptionFilter,
  Catch,
  ArgumentsHost,
  HttpException,
  HttpStatus,
} from '@nestjs/common';
import { Request, Response } from 'express';

@Catch()
export class AllExceptionsFilter implements ExceptionFilter {
  catch(exception: unknown, host: ArgumentsHost) {
    const ctx = host.switchToHttp();
    const response = ctx.getResponse<Response>();
    const request = ctx.getRequest<Request>();

    let statusCode = HttpStatus.INTERNAL_SERVER_ERROR;
    let message = 'Internal server error';
    let error = 'Internal Server Error';

    if (exception instanceof HttpException) {
      statusCode = exception.getStatus();
      const exceptionResponse = exception.getResponse();

      if (typeof exceptionResponse === 'string') {
        message = exceptionResponse;
        error = exception.name;
      } else if (typeof exceptionResponse === 'object') {
        const resp = exceptionResponse as any;
        error = resp.error || exception.name;
        message = Array.isArray(resp.message)
          ? resp.message.join('; ')
          : resp.message || exception.message;
      }
    } else if (exception instanceof Error) {
      message = exception.message;
      error = exception.name;
    }

    response.status(statusCode).json({
      statusCode,
      message,
      error,
      timestamp: new Date().toISOString(),
      path: request.url,
    });
  }
}
