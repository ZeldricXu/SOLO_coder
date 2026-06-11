import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: {
    timeout: 10_000,
  },
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: process.env.CI
    ? [['list'], ['html', { open: 'never' }]]
    : [['list'], ['html']],

  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },

  projects: [
    {
      name: 'chromium-user1',
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'playwright/.state/user1.json',
      },
    },
    {
      name: 'firefox-user2',
      use: {
        ...devices['Desktop Firefox'],
        storageState: 'playwright/.state/user2.json',
      },
    },
    {
      name: 'webkit-user3',
      use: {
        ...devices['Desktop Safari'],
        storageState: 'playwright/.state/user3.json',
      },
    },
  ],

  webServer: {
    command: 'npm run dev',
    url: process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
