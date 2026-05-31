import app from './app';
import { config } from './config';
import { getPrismaClient, disconnectDatabase } from './utils/database';
import { getRedisClient } from './utils/cache';

const prisma = getPrismaClient();
const redis = getRedisClient();

const PORT = config.server.port;

const startServer = async () => {
  try {
    await prisma.$connect();
    console.log('✅ Database connected successfully');

    if (redis) {
      console.log('✅ Redis client initialized');
    }

    app.listen(PORT, () => {
      console.log(`🚀 Server running on port ${PORT}`);
      console.log(`📡 Environment: ${config.server.nodeEnv}`);
      console.log(`🔍 Health check: http://localhost:${PORT}/health`);
      console.log(`🔧 API base: http://localhost:${PORT}/api/v1`);
    });
  } catch (error) {
    console.error('❌ Failed to start server:', error);
    process.exit(1);
  }
};

const gracefulShutdown = async (signal: string) => {
  console.log(`\nReceived ${signal}. Shutting down gracefully...`);

  try {
    if (redis) {
      await redis.quit();
      console.log('✅ Redis connection closed');
    }

    await disconnectDatabase();
    console.log('✅ Database connection closed');

    process.exit(0);
  } catch (error) {
    console.error('❌ Error during shutdown:', error);
    process.exit(1);
  }
};

process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
process.on('SIGINT', () => gracefulShutdown('SIGINT'));

startServer();
