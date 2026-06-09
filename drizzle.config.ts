import type { Config } from 'drizzle-kit';
import { config } from './src/config';

export default {
  schema: './drizzle/schema.ts',
  out: './drizzle/migrations',
  driver: 'pg',
  dbCredentials: {
    connectionString: config.databaseUrl,
  },
  verbose: true,
  strict: true,
} satisfies Config;
