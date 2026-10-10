import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import net from 'node:net';
import { execFile, spawn } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { chromium, expect as baseExpect } from '@playwright/test';

const expect = baseExpect.configure({ timeout: 15000 });

if (process.platform !== 'win32') throw new Error('This smoke test requires Windows WebView2.');
if (!process.env.JAVA_HOME) throw new Error('Initialize the shared Java environment before running this test.');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const development = process.argv.includes('--development');
const executable = path.join(root, 'src-tauri/target', development ? 'debug' : 'release', 'codex-squire-desktop.exe');
const testRoot = path.join(root, '.runtime', `desktop-auth-${Date.now()}`);
const data = path.join(testRoot, 'data');
await fs.mkdir(data, { recursive: true });
const jars = (await fs.readdir(path.join(root, 'backend/build/libs'))).filter(name => /^codex-squire-.*\.jar$/.test(name) && !name.endsWith('-plain.jar'));
assert.equal(jars.length, 1, 'Build one executable backend JAR before running desktop verification.');

async function freePort() {
  const server = net.createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}

async function waitUntil(check, description, timeout = 30000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await check()) return;
    await new Promise(resolve => setTimeout(resolve, 150));
  }
  throw new Error(`Timed out: ${description}`);
}

const backendPort = await freePort();
const frontendPort = await freePort();
const debugPort = await freePort();
await fs.writeFile(path.join(data, 'desktop-ports.json'), JSON.stringify({ backendPort, frontendPort }));
const backendUrl = `http://127.0.0.1:${backendPort}`;
const child = spawn(executable, [], { cwd: root, windowsHide: true, stdio: 'ignore', env: {
  ...process.env, SQUIRE_DATA_DIR: data,
  SQUIRE_BACKEND_JAR: path.join(root, 'backend/build/libs', jars[0]),
  SQUIRE_FRONTEND_DIST: path.join(root, 'frontend/dist'),
  WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: `--remote-debugging-port=${debugPort}`,
  WEBVIEW2_USER_DATA_FOLDER: path.join(testRoot, 'webview'),
} });
const exited = new Promise(resolve => child.once('exit', resolve));
child.once('error', error => { console.error(error.message); });
let browser;
let page;
let webBrowser;
const errors = [];
async function closeNativeWindow() {
  if (child.exitCode !== null) return;
  await promisify(execFile)('powershell.exe', ['-NoProfile', '-Command',
    `(Get-Process -Id ${child.pid}).CloseMainWindow() | Out-Null`], { windowsHide: true });
}
try {
  await waitUntil(async () => {
    if (child.exitCode !== null) throw new Error('Desktop application exited before WebView2 initialized.');
    return fetch(`http://127.0.0.1:${debugPort}/json/version`).then(response => response.ok).catch(() => false);
  }, 'WebView2 debugging endpoint');
  browser = await chromium.connectOverCDP(`http://127.0.0.1:${debugPort}`);
  await waitUntil(() => browser.contexts().some(context => context.pages().length), 'native window');
  page = browser.contexts().flatMap(context => context.pages())[0];
  page.on('pageerror', error => errors.push(error.message));
  const invoke = (command, args) => page.evaluate(({ command, args }) => window.__TAURI_INTERNALS__.invoke(command, args), { command, args });
  const rejectedInvoke = (command, args) => page.evaluate(async ({ command, args }) => {
    try { await window.__TAURI_INTERNALS__.invoke(command, args); return null; }
    catch (failure) { return failure; }
  }, { command, args });
  await expect(page.getByRole('heading', { name: '启动 Codex Squire' })).toBeVisible();
  const expectedOrigin = development ? 'http://127.0.0.1:5173' : 'http://tauri.localhost';
  assert.equal(new URL(page.url()).origin, expectedOrigin);
  const rejection = await rejectedInvoke('get_local_admin_session', { expectedRuntimeId: 'no-runtime' });
  assert.equal(rejection?.code, 'RUNTIME_NOT_RUNNING', JSON.stringify(rejection));
  await page.getByRole('button', { name: '启动运行时', exact: true }).click();
  await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible({ timeout: 30000 });
  await expect(page.getByLabel('管理令牌', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '退出管理', exact: true })).toHaveCount(0);
  const runtime = await invoke('runtime_status');
  assert.equal(runtime.backendUrl, backendUrl);
  assert.equal((await fetch(`${backendUrl}/api/health`).then(response => response.json())).runtimeId, runtime.runtimeId);
  assert.equal((await fetch(`${backendUrl}/api/providers`)).status, 401);
  assert.deepEqual(await page.evaluate(() => ({ ...sessionStorage, ...localStorage })), {});
  assert.equal(await page.evaluate(() => window.__SQUIRE_RUNTIME__), undefined);
  assert.equal((await rejectedInvoke('get_local_admin_session', { expectedRuntimeId: 'stale' })).code, 'RUNTIME_CHANGED');
  await page.reload();
  await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible();
  assert.equal((await invoke('runtime_status')).runtimeId, runtime.runtimeId);
  if (development) {
    webBrowser = await chromium.launch({ channel: 'chrome' });
    const web = await webBrowser.newPage();
    await web.goto(expectedOrigin);
    await expect(web.getByLabel('管理令牌', { exact: true })).toBeVisible();
    assert.equal(await web.evaluate(() => typeof window.__TAURI_INTERNALS__), 'undefined');
    await webBrowser.close(); webBrowser = null;
    const socket = page.waitForEvent('websocket', { timeout: 10000 });
    await page.reload();
    assert.match((await socket).url(), /^ws:\/\/127\.0\.0\.1:5173/);
    await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible();
  }

  // Real WebView HTTP traffic must support cross-origin versioned edits, not just reads.
  await page.getByRole('button', { name: '来源管理', exact: true }).click();
  await page.getByRole('button', { name: '添加来源', exact: true }).first().click();
  await page.getByLabel('来源名称').fill('Desktop smoke provider');
  await page.getByLabel('Base URL', { exact: true }).fill('http://127.0.0.1:9/v1');
  await page.getByLabel('API Key', { exact: true }).fill('mock-unused-key');
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.getByRole('button', { name: '编辑 Desktop smoke provider', exact: true }).click();
  await page.getByLabel('来源名称').fill('Desktop smoke provider edited');
  const savedProvider = page.waitForResponse(response => response.request().method() === 'PUT' && response.url().includes('/api/providers/'));
  await page.getByRole('button', { name: '保存', exact: true }).click();
  const providerResponse = await savedProvider;
  assert.equal(providerResponse.status(), 200);
  assert.equal(providerResponse.request().headers()['if-match'], '"1"');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.getByRole('button', { name: '配置方案', exact: true }).click();
  await page.getByRole('button', { name: '新建方案', exact: true }).first().click();
  await page.getByLabel('方案名称').fill('Desktop smoke scheme');
  await page.getByLabel('待添加来源').selectOption({ label: 'Desktop smoke provider edited' });
  await page.getByRole('button', { name: '添加', exact: true }).click();
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.getByRole('button', { name: '编辑 Desktop smoke scheme', exact: true }).click();
  await page.getByLabel('方案名称').fill('Desktop smoke scheme edited');
  const savedScheme = page.waitForResponse(response => response.request().method() === 'PUT' && response.url().includes('/api/schemes/'));
  await page.getByRole('button', { name: '保存', exact: true }).click();
  const schemeResponse = await savedScheme;
  assert.equal(schemeResponse.status(), 200);
  assert.equal(schemeResponse.request().headers()['if-match'], '"1"');
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.getByRole('button', { name: '运行概览', exact: true }).click();
  await page.screenshot({ path: path.join(testRoot, 'local.png'), fullPage: true });

  // A privileged main window may navigate only within its application origin.
  await page.evaluate(() => { location.href = 'http://127.0.0.1:1/untrusted'; });
  await new Promise(resolve => setTimeout(resolve, 500));
  assert.equal(new URL(page.url()).origin, expectedOrigin);
  await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible();

  const tokenPath = path.join(data, 'admin.token');
  const originalToken = await fs.readFile(tokenPath, 'utf8');
  await fs.rename(tokenPath, `${tokenPath}.smoke-backup`);
  try {
    await page.reload();
    await expect(page.getByRole('heading', { name: '本地认证失败' })).toBeVisible();
    await expect(page.getByLabel('管理令牌', { exact: true })).toHaveCount(0);
    await page.screenshot({ path: path.join(testRoot, 'read-fault.png'), fullPage: true });
  } finally { await fs.rename(`${tokenPath}.smoke-backup`, tokenPath); }
  await page.getByRole('button', { name: '重试连接', exact: true }).click();
  await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible();
  await fs.writeFile(tokenPath, 'smoke-invalid-credential');
  try {
    await page.reload();
    await expect(page.getByRole('heading', { name: '本地认证失败' })).toBeVisible();
    await expect(page.getByLabel('管理令牌', { exact: true })).toHaveCount(0);
  } finally { await fs.writeFile(tokenPath, originalToken); }
  await page.getByRole('button', { name: '重试连接', exact: true }).click();
  await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible();

  await invoke('stop_runtime');
  await expect(page.getByRole('heading', { name: '启动 Codex Squire' })).toBeVisible();
  await page.getByRole('button', { name: /服务模式/ }).click();
  await page.getByLabel('公布的主机名或局域网 IP').fill('squire.example.test');
  await page.getByLabel('允许局域网浏览器访问管理界面').check();
  await page.getByRole('button', { name: '启动运行时', exact: true }).click();
  await expect(page.getByLabel('管理令牌', { exact: true })).toBeVisible({ timeout: 30000 });
  const service = await invoke('runtime_status');
  assert.equal(service.backendUrl, backendUrl);
  assert.equal(service.gatewayUrl, `http://squire.example.test:${backendPort}/v1`);
  assert.notEqual(service.runtimeId, runtime.runtimeId);
  assert.equal((await rejectedInvoke('get_local_admin_session', { expectedRuntimeId: service.runtimeId })).code, 'LOCAL_AUTH_UNAVAILABLE');
  const publicRuntime = await fetch(`http://127.0.0.1:${frontendPort}/__squire/runtime.json`).then(response => response.json());
  assert.equal(publicRuntime.backendUrl, `http://squire.example.test:${backendPort}`);
  assert.equal('adminTokenPath' in publicRuntime, false);
  assert.equal('adminToken' in publicRuntime, false);
  const token = (await fs.readFile(tokenPath, 'utf8')).trim();
  await page.getByLabel('管理令牌', { exact: true }).fill(token);
  await page.getByRole('button', { name: '连接', exact: true }).click();
  await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '退出管理', exact: true }).click();
  await expect(page.getByLabel('管理令牌', { exact: true })).toBeVisible();
  assert.equal((await invoke('runtime_status')).runtimeId, service.runtimeId);

  // Ordinary Chrome on loopback still has no native identity. Resolve the advertised name only in this test browser.
  webBrowser = await chromium.launch({ channel: 'chrome', args: ['--host-resolver-rules=MAP squire.example.test 127.0.0.1'] });
  const web = await webBrowser.newPage();
  await web.goto(`http://127.0.0.1:${frontendPort}`);
  await expect(web.getByLabel('管理令牌', { exact: true })).toBeVisible();
  assert.equal(await web.evaluate(() => typeof window.__TAURI_INTERNALS__), 'undefined');
  await web.getByLabel('管理令牌', { exact: true }).fill(token);
  await web.getByRole('button', { name: '连接', exact: true }).click();
  await expect(web.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible();
  await web.screenshot({ path: path.join(testRoot, 'service-browser.png'), fullPage: true });
  await webBrowser.close(); webBrowser = null;
  assert.deepEqual(errors, []);
  const forbiddenClose = await rejectedInvoke('plugin:window|close', { label: 'main' });
  assert.match(String(forbiddenClose), /not allowed/);
  await closeNativeWindow();
  await waitUntil(() => child.exitCode !== null, 'native application close');
  await waitUntil(() => fetch(`${backendUrl}/api/health`).then(() => false).catch(() => true), 'backend child cleanup');
  await waitUntil(() => fetch(`http://127.0.0.1:${frontendPort}`).then(() => false).catch(() => true), 'static server cleanup');
  console.log(`Desktop authentication smoke passed (${development ? 'development' : 'release'}). Evidence: ${testRoot}`);
} catch (failure) {
  if (page && !page.isClosed()) {
    await page.screenshot({ path: path.join(testRoot, 'failure.png'), fullPage: true }).catch(() => {});
    const diagnostics = { url: page.url(), headings: await page.getByRole('heading').allTextContents().catch(() => []),
      alerts: await page.getByRole('alert').allTextContents().catch(() => []), errors };
    await fs.writeFile(path.join(testRoot, 'failure.json'), JSON.stringify(diagnostics, null, 2));
    console.error(JSON.stringify(diagnostics));
  }
  throw failure;
} finally {
  if (child.exitCode === null && page && !page.isClosed()) {
    await page.evaluate(() => window.__TAURI_INTERNALS__.invoke('stop_runtime')).catch(() => {});
    await closeNativeWindow().catch(() => {});
  }
  await webBrowser?.close().catch(() => {});
  await browser?.close().catch(() => {});
  if (child.exitCode === null) child.kill();
  await exited;
}
