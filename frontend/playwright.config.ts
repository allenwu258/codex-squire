import { defineConfig } from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs';

const root = path.resolve('..');
const javaHome = process.env.JAVA_HOME;
if (!javaHome) throw new Error('Set JAVA_HOME from the shared environment configuration before running E2E tests; see docs/engineering.md.');
const libraries = path.join(root, 'backend', 'build', 'libs');
const jars = fs.readdirSync(libraries).filter(name => name.startsWith('codex-squire-') && name.endsWith('.jar') && !name.endsWith('-plain.jar'));
if (jars.length !== 1) throw new Error('Run Gradle clean bootJar before E2E tests; exactly one executable JAR is required.');
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
      command: `"${path.join(javaHome, 'bin', 'java.exe')}" -Dfile.encoding=UTF-8 -jar "${path.join(libraries, jars[0])}"`,
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
