import express from 'express';
import cors from 'cors';
import helmet from 'helmet';
import compression from 'compression';
import {
  traceIdMiddleware,
  tenantContextMiddleware,
  requestLogger,
  errorMiddleware,
  notFoundMiddleware,
  responseTimeHeader,
  securityHeaders
} from './common/middleware';
import routes from './routes';

const app = express();

app.use(helmet());
app.use(cors());
app.use(compression());
app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true }));

app.use(responseTimeHeader);
app.use(securityHeaders);
app.use(traceIdMiddleware);
app.use(tenantContextMiddleware);
app.use(requestLogger);

app.use(routes);

app.use(notFoundMiddleware);
app.use(errorMiddleware);

export default app;
