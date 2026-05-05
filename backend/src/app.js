const express = require('express');
const cors = require('cors');
require('dotenv').config();

const requestLogger = require('./middleware/requestLogger');
const { errorHandler, notFoundHandler } = require('./middleware/errorHandler');

const analysisRoutes = require('./routes/analysisRoutes');
const reviewRoutes = require('./routes/reviewRoutes');
const reportRoutes = require('./routes/reportRoutes');
const codeRoutes = require('./routes/codeRoutes');
const configRoutes = require('./routes/configRoutes');

const app = express();

app.use(cors({
  origin: process.env.CORS_ORIGIN || '*',
  credentials: true,
  methods: ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
  allowedHeaders: ['Content-Type', 'Authorization']
}));

app.use(express.json({ limit: '50mb' }));
app.use(express.urlencoded({ extended: true, limit: '50mb' }));

app.use(requestLogger);

app.get('/health', (req, res) => {
  res.json({
    code: 200,
    data: {
      status: 'ok',
      timestamp: new Date().toISOString(),
      service: 'codereview-backend'
    }
  });
});

app.use('/api/v1/analysis', analysisRoutes);
app.use('/api/v1/review', reviewRoutes);
app.use('/api/v1/report', reportRoutes);
app.use('/api/v1/code', codeRoutes);
app.use('/api/v1/config/rules', configRoutes);

app.use(notFoundHandler);
app.use(errorHandler);

module.exports = app;
