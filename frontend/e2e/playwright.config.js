import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '.',
  testMatch: '*.e2e.js',
  timeout: 60000,
  use: {
    baseURL: 'http://localhost:3000',
    headless: true,
    viewport: { width: 1400, height: 900 },
    ignoreHTTPSErrors: true,
  },
  webServer: {
    command: 'cd .. && npx vite --port 3000',
    port: 3000,
    reuseExistingServer: true,
    timeout: 30000,
  },
});
