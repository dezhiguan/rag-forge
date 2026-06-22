import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.PLAYWRIGHT_BASE_URL || 'https://ragforge.net'
const forceBrowser = process.env.PW_E2E_BROWSER?.toLowerCase() || 'chromium'

export default defineConfig({
  testDir: './tests/e2e',
  timeout: 30_000,
  expect: {
    timeout: 10_000,
  },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [
    ['line'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
  ],
  outputDir: 'test-results',
  use: {
    baseURL,
    // Set PW_E2E_ARTIFACTS=on to always capture trace/video/screenshots (used by the T8 acceptance suite).
    trace: process.env.PW_E2E_ARTIFACTS === 'on' ? 'on' : process.env.CI ? 'retain-on-failure' : 'on-first-retry',
    screenshot: process.env.PW_E2E_ARTIFACTS === 'on' ? 'on' : 'only-on-failure',
    video: process.env.PW_E2E_ARTIFACTS === 'on' ? 'on' : 'retain-on-failure',
  },
  projects: [
    ...(forceBrowser === 'chromium'
      ? [
        {
          name: 'chromium',
          use: {
            ...devices['Desktop Chrome'],
            // Use locally installed Google Chrome (no `npx playwright install` needed).
            channel: process.env.PW_E2E_CHANNEL || 'chrome',
            launchOptions: {
              args: ['--no-sandbox', '--disable-setuid-sandbox'],
            },
          },
        },
      ]
      : []),
    ...(forceBrowser === 'firefox'
      ? [
        {
          name: 'firefox',
          use: {
            ...devices['Desktop Firefox'],
            launchOptions: {
              args: ['-wait-for-browser'],
            },
          },
        },
      ]
      : []),
    ...(forceBrowser === 'webkit'
      ? [
        {
          name: 'webkit',
          use: {
            ...devices['Desktop Safari'],
          },
        },
      ]
      : []),
  ],
})
