import { defineConfig } from '@playwright/test';
import path from 'node:path';

const root = path.resolve('..');
const data = process.env.SQUIRE_E2E_DATA ?? path.join(root, '.runtime', `e2e-${Date.now()}`);
process.env.SQUIRE_E2E_DATA = data;

export default defineConfig({
  testDir: './tests',
  timeout: 60000,
  expect: { timeout: 10000 },
  workers: 1,
  fullyParallel: false,
  reporter: [['list']],
  outputDir: 'test-results',
  use: {
    baseURL: 'http://127.0.0.1:5188',
    channel: 'chrome',
    viewport: { width: 1440, height: 1000 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: [
    {
      command: '"C:\\SDK\\Java\\17\\graalvm-jdk-17.0.9\\bin\\java.exe" -Dfile.encoding=UTF-8 -jar backend/build/libs/codex-squire-0.1.0.jar',
      cwd: root,
      url: 'http://127.0.0.1:8798/api/health',
      timeout: 60000,
      reuseExistingServer: false,
      env: { SQUIRE_PORT: '8798', SQUIRE_DATA_DIR: data, SQUIRE_ALLOWED_ORIGINS: 'http://127.0.0.1:5188' },
    },
    {
      command: 'pnpm dev --port 5188',
      url: 'http://127.0.0.1:5188',
      timeout: 30000,
      reuseExistingServer: false,
      env: { SQUIRE_BACKEND_URL: 'http://127.0.0.1:8798' },
    },
  ],
});
