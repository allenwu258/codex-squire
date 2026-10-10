import { test, expect, type Page } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import type { DesktopRuntime, NativeAdminSession } from '../src/runtime';

interface NativeFixture {
  runtime: DesktopRuntime | null;
  starts: number;
  credentials: number;
  failure: { code: string; message: string } | null;
  tokenPlan: string[];
  delay: number;
}
declare global { interface Window { authFixture: NativeFixture } }

const backendUrl = 'http://127.0.0.1:8798';
const adminToken = () => fs.readFileSync(path.join(process.env.SQUIRE_E2E_DATA!, 'admin.token'), 'utf8').trim();

async function native(page: Page, mode: 'local' | 'service' = 'local', running = true) {
  await page.addInitScript(({ token, mode, running, backendUrl }) => {
    const runtime: DesktopRuntime = { runtimeId: 'native-instance', mode, backendUrl,
      gatewayUrl: `${backendUrl}/v1`, webUrl: 'http://tauri.localhost', frontendBroadcast: false, adminTokenPath: 'native/admin.token' };
    window.authFixture = { runtime: running ? runtime : null, starts: 0, credentials: 0, failure: null, tokenPlan: [], delay: 0 };
    sessionStorage.setItem('squire.adminToken', 'legacy-wrong-token');
    window.__TAURI_INTERNALS__ = { invoke: async (command, args) => {
      const state = window.authFixture;
      if (command === 'runtime_status') return state.runtime;
      if (command === 'runtime_port_settings') return { backendPort: 18787, frontendPort: 18788 };
      if (command === 'start_runtime') { state.starts++; state.runtime = runtime; return runtime; }
      if (command === 'get_local_admin_session') {
        state.credentials++;
        if (state.delay) await new Promise(resolve => setTimeout(resolve, state.delay));
        if (state.failure) throw state.failure;
        return { runtimeId: String(args?.expectedRuntimeId), backendUrl,
          adminToken: state.tokenPlan.shift() ?? token } satisfies NativeAdminSession;
      }
      throw new Error(`Unexpected IPC: ${command}`);
    } };
  }, { token: adminToken(), mode, running, backendUrl });
}

async function login(page: Page) {
  await page.getByLabel('管理令牌', { exact: true }).fill(adminToken());
  await page.getByRole('button', { name: '连接', exact: true }).click();
  await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible();
}

const denied = { status: 401, contentType: 'application/json', body: JSON.stringify({ error: { code: 'UNAUTHORIZED', message: 'Access denied' } }) };

test('local desktop starts and reloads with native memory authentication only', async ({ page }) => {
  await native(page, 'local', false);
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '启动 Codex Squire' })).toBeVisible();
  await page.getByRole('button', { name: '启动运行时', exact: true }).click();
  await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible();
  await expect(page.getByLabel('管理令牌', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '退出管理' })).toHaveCount(0);
  expect(await page.evaluate(() => ({ starts: window.authFixture.starts, credentials: window.authFixture.credentials,
    storage: { ...sessionStorage, ...localStorage }, runtime: window.__SQUIRE_RUNTIME__ }))).toEqual({ starts: 1, credentials: 1, storage: {}, runtime: undefined });
  // Supply the same already running instance after reload, just as Rust runtime_status does.
  await page.addInitScript(() => {
    window.authFixture.runtime = { runtimeId: 'native-instance', mode: 'local', backendUrl: 'http://127.0.0.1:8798',
      gatewayUrl: 'http://127.0.0.1:8798/v1', webUrl: 'http://tauri.localhost', frontendBroadcast: false, adminTokenPath: '' };
  });
  await page.reload();
  await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible();
  expect(await page.evaluate(() => window.authFixture.starts)).toBe(0);
  expect(await page.evaluate(() => ({ ...sessionStorage }))).toEqual({});
  await page.screenshot({ path: 'test-results/native-local-desktop.png', fullPage: true });
});

test('service desktop requires manual login and logout leaves the runtime running', async ({ page }) => {
  await native(page, 'service');
  await page.goto('/');
  await expect(page.getByLabel('管理令牌', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => window.authFixture.credentials)).toBe(0);
  await login(page);
  await page.reload();
  await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible();
  expect(await page.evaluate(() => window.authFixture.credentials)).toBe(0);
  await page.getByRole('button', { name: '退出管理', exact: true }).click();
  await expect(page.getByLabel('管理令牌', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => window.authFixture.runtime?.runtimeId)).toBe('native-instance');
  expect(await page.evaluate(() => ({ ...sessionStorage }))).toEqual({});
});

test('loopback browser ignores legacy token and public automatic-login claims', async ({ page }) => {
  let managementRequests = 0;
  page.on('request', request => { if (request.url().includes('/api/')) managementRequests++; });
  await page.addInitScript(() => {
    sessionStorage.setItem('squire.adminToken', 'legacy-token');
    window.__SQUIRE_RUNTIME__ = { mode: 'local', backendUrl: 'http://127.0.0.1:8798', gatewayUrl: 'http://127.0.0.1:8798/v1',
      webUrl: location.origin, frontendBroadcast: false, adminTokenPath: 'private/path' };
    Object.assign(window.__SQUIRE_RUNTIME__, { adminToken: 'public-token', autoLogin: true });
  });
  await page.goto('/');
  await expect(page.getByLabel('管理令牌', { exact: true })).toBeVisible();
  expect(managementRequests).toBe(0);
  await expect(page.getByText('private/path')).toHaveCount(0);
  await login(page);
  await page.reload();
  await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible();
});

test('browser manual session is scoped to the discovered backend', async ({ page }) => {
  await page.goto('/');
  await login(page);
  await page.addInitScript(() => {
    window.__SQUIRE_RUNTIME__ = { mode: 'service', backendUrl: 'http://127.0.0.1:8798', gatewayUrl: 'http://127.0.0.1:8798/v1',
      webUrl: location.origin, frontendBroadcast: false, adminTokenPath: '' };
  });
  await page.reload();
  await expect(page.getByLabel('管理令牌', { exact: true })).toBeVisible();
});

test('invalid public runtime descriptor falls back to manual proxy login', async ({ page }) => {
  await page.route('**/__squire/runtime.json', route => route.fulfill({ json: { mode: 'local', backendUrl: 'javascript:alert(1)',
    gatewayUrl: backendUrl, webUrl: backendUrl, frontendBroadcast: false, adminToken: 'secret' } }));
  await page.goto('/');
  await expect(page.getByLabel('管理令牌', { exact: true })).toBeVisible();
  await login(page);
});

test('native read fault has retry and never falls back to token login', async ({ page }) => {
  await native(page);
  await page.addInitScript(() => { window.authFixture.failure = { code: 'ADMIN_TOKEN_READ_FAILED', message: '读取本地管理令牌失败' }; });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '本地认证失败' })).toBeVisible();
  await expect(page.getByLabel('管理令牌', { exact: true })).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/native-auth-fault-mobile.png', fullPage: true });
  await page.evaluate(() => { window.authFixture.failure = null; });
  await page.getByRole('button', { name: '重试连接' }).click();
  await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible();
});

test('native rejected token attempts one recovery then stops until explicit retry', async ({ page }) => {
  await native(page);
  await page.addInitScript(() => { window.authFixture.tokenPlan = ['wrong-one', 'wrong-two']; });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '本地认证失败' })).toBeVisible();
  expect(await page.evaluate(() => window.authFixture.credentials)).toBe(2);
  await expect(page.getByLabel('管理令牌', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '重试连接' }).click();
  await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible();
});

test('manual management 401 from feature requests immediately clears the session', async ({ page }) => {
  await page.goto('/');
  await login(page);
  await page.route('**/api/connection', route => route.fulfill(denied));
  await page.getByRole('button', { name: 'Codex 接入', exact: true }).click();
  await expect(page.getByLabel('管理令牌', { exact: true })).toBeVisible();
  await expect(page.getByRole('alert')).toContainText('管理会话已失效');
  expect(await page.evaluate(() => ({ ...sessionStorage }))).toEqual({});
});

test('local feature 401 recovers once without replaying the mutation', async ({ page }) => {
  await native(page);
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible();
  let writes = 0;
  await page.route('**/api/providers', route => {
    if (route.request().method() !== 'POST') return route.continue();
    writes++;
    return route.fulfill(denied);
  });
  await page.getByRole('button', { name: '来源管理', exact: true }).click();
  await page.getByRole('button', { name: '添加来源', exact: true }).first().click();
  await page.getByLabel('来源名称').fill('Unauthorized write');
  await page.getByLabel('Base URL', { exact: true }).fill('http://127.0.0.1:9999/v1');
  await page.getByLabel('API Key', { exact: true }).fill('unused');
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '添加来源', exact: true }).first()).toBeVisible();
  expect(writes).toBe(1);
  expect(await page.evaluate(() => window.authFixture.credentials)).toBe(2);
  await page.route('**/api/connection', route => route.fulfill(denied));
  await page.getByRole('button', { name: 'Codex 接入', exact: true }).click();
  await expect(page.getByRole('heading', { name: '本地认证失败' })).toBeVisible();
  expect(await page.evaluate(() => window.authFixture.credentials)).toBe(2);
});

test('backend exit cancels pending native authentication and returns to launcher', async ({ page }) => {
  await native(page);
  await page.addInitScript(() => { window.authFixture.delay = 3500; });
  await page.goto('/');
  await expect(page.getByRole('status')).toContainText('正在连接本地工作空间');
  await page.evaluate(() => { window.authFixture.runtime = null; });
  await expect(page.getByRole('heading', { name: '启动 Codex Squire' })).toBeVisible();
  await page.waitForTimeout(1800);
  await expect(page.getByRole('heading', { name: '启动 Codex Squire' })).toBeVisible();
  await expect(page.getByRole('button', { name: '运行概览', exact: true })).toHaveCount(0);
});

test('late feature 401 cannot clear a newer manual session', async ({ page }) => {
  await page.goto('/');
  await login(page);
  let requested = false;
  await page.route('**/api/connection', async route => {
    requested = true;
    await new Promise(resolve => setTimeout(resolve, 2000));
    await route.fulfill(denied).catch(() => {});
  });
  await page.getByRole('button', { name: 'Codex 接入', exact: true }).click();
  await expect.poll(() => requested).toBe(true);
  await page.getByRole('button', { name: '退出管理', exact: true }).click();
  await page.evaluate(() => { location.hash = 'overview'; });
  await login(page);
  await page.waitForTimeout(2200);
  await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible();
});

test('gateway 401 leaves the manual management session authenticated', async ({ page }) => {
  await page.goto('/');
  await login(page);
  await page.route('**/api/schemes', route => route.fulfill({ json: [{ id: 'active-test', name: 'Test', strategy: 'ORDERED', active: true, version: 1, members: [] }] }));
  await page.getByRole('button', { name: '刷新', exact: true }).click();
  await page.route('**/v1/responses', route => route.fulfill(denied));
  await page.getByRole('button', { name: 'Codex 接入', exact: true }).click();
  await page.getByLabel('模型 ID', { exact: true }).fill('unused');
  await page.getByRole('button', { name: '发送测试', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('HTTP 401');
  await expect(page.getByRole('button', { name: '退出管理', exact: true })).toBeVisible();
  await expect(page.getByLabel('管理令牌', { exact: true })).toHaveCount(0);
});

test('new native runtime replaces a pending authentication without accepting the old result', async ({ page }) => {
  await native(page);
  await page.addInitScript(() => { window.authFixture.delay = 3500; });
  const authenticatedInstances: string[] = [];
  page.on('request', request => {
    if (request.url().endsWith('/api/providers')) authenticatedInstances.push('request');
  });
  await page.goto('/');
  await expect(page.getByRole('status')).toContainText('正在连接本地工作空间');
  await page.evaluate(() => { window.authFixture.runtime!.runtimeId = 'replacement-instance'; });
  await expect.poll(() => page.evaluate(() => window.authFixture.credentials)).toBe(2);
  // The old IPC resolves before the replacement IPC; it must not start HTTP validation.
  await page.waitForTimeout(1700);
  expect(authenticatedInstances).toEqual([]);
  await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible();
});

test('redirected management request is rejected before credentials reach the other endpoint', async ({ page }) => {
  await page.goto('/');
  await login(page);
  let reachedRedirect = false;
  await page.route('**/unexpected-target', route => { reachedRedirect = true; return route.fulfill({ json: {} }); });
  await page.route('**/api/connection', route => route.fulfill({ status: 302, headers: { Location: '/unexpected-target' } }));
  await page.getByRole('button', { name: 'Codex 接入', exact: true }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  expect(reachedRedirect).toBe(false);
  await expect(page.getByRole('button', { name: '退出管理', exact: true })).toBeVisible();
});

test('temporary saved-session verification failure preserves the token for retry', async ({ page }) => {
  await page.goto('/');
  await login(page);
  const stored = await page.evaluate(() => ({ ...sessionStorage }));
  await page.route('**/api/providers', route => route.fulfill({ status: 503, json: { error: { code: 'DATABASE_BUSY', message: 'Storage busy' } } }));
  await page.reload();
  await expect(page.getByRole('heading', { name: '运行时连接失败' })).toBeVisible();
  expect(await page.evaluate(() => ({ ...sessionStorage }))).toEqual(stored);
  await page.unroute('**/api/providers');
  await page.getByRole('button', { name: '重试连接' }).click();
  await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible();
});

test('cooldown action 401 expires the session without an unhandled rejection', async ({ page, request }) => {
  const created = await request.post('/api/providers', { headers: { Authorization: `Bearer ${adminToken()}` }, data: {
    name: 'Cooldown auth', baseUrl: 'http://127.0.0.1:9/v1', apiKey: 'unused', enabled: true,
    dailyRequestLimit: 0, dailyTokenLimit: 0, maxConcurrency: 1, tokenReservation: 100,
  } });
  expect(created.status()).toBe(201);
  const provider = await created.json();
  const errors: string[] = [];
  page.on('pageerror', failure => errors.push(failure.message));
  await page.route('**/api/provider-runtime', route => route.fulfill({ json: [
    { providerId: provider.id, health: 'RATE_LIMITED', cooldownUntil: '2099-01-01T00:00:00Z' },
  ] }));
  await page.route(`**/api/providers/${provider.id}/runtime/clear-cooldown`, route => route.fulfill(denied));
  await page.goto('/');
  await login(page);
  await page.getByRole('button', { name: '来源管理', exact: true }).click();
  await page.getByRole('button', { name: '清除 Cooldown auth 冷却', exact: true }).click();
  await expect(page.getByLabel('管理令牌', { exact: true })).toBeVisible();
  await expect(page.getByRole('alert')).toContainText('管理会话已失效');
  expect(errors).toEqual([]);
});
