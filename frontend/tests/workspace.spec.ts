import { test, expect, type Page } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import { execFile } from 'node:child_process';

let upstream: http.Server;
let upstreamPort: number;

test.beforeAll(async () => {
  upstream = http.createServer((request, response) => {
    if (request.headers.authorization !== 'Bearer mock-api-key') { response.writeHead(401).end(); return; }
    const chunks: Buffer[] = [];
    request.on('data', chunk => chunks.push(chunk));
    request.on('end', () => {
      const body = JSON.parse(Buffer.concat(chunks).toString());
      const part = { type: 'output_text', text: 'OK', annotations: [] };
      const item = { id: 'msg_test', type: 'message', role: 'assistant', status: 'completed', content: [part] };
      const result = { id: 'resp_ui_test', object: 'response', created_at: Math.floor(Date.now() / 1000), status: 'completed',
        model: body.model, output: [item], usage: { input_tokens: 15, output_tokens: 2, total_tokens: 17, input_tokens_details: { cached_tokens: 3 }, output_tokens_details: { reasoning_tokens: 0 } } };
      if (body.stream) {
        response.writeHead(200, { 'Content-Type': 'text/event-stream' });
        const events = [
          { type: 'response.created', response: { ...result, status: 'in_progress', output: [], usage: null } },
          { type: 'response.output_item.added', output_index: 0, item: { ...item, status: 'in_progress', content: [] } },
          { type: 'response.content_part.added', item_id: item.id, output_index: 0, content_index: 0, part: { ...part, text: '' } },
          { type: 'response.output_text.delta', item_id: item.id, output_index: 0, content_index: 0, delta: 'OK' },
          { type: 'response.output_text.done', item_id: item.id, output_index: 0, content_index: 0, text: 'OK' },
          { type: 'response.content_part.done', item_id: item.id, output_index: 0, content_index: 0, part },
          { type: 'response.output_item.done', output_index: 0, item },
          { type: 'response.completed', response: result },
        ];
        for (const [index, event] of events.entries()) response.write(`event: ${event.type}\ndata: ${JSON.stringify({ ...event, sequence_number: index })}\n\n`);
        response.end();
      } else {
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify(result));
      }
    });
  });
  await new Promise<void>(resolve => upstream.listen(0, '127.0.0.1', resolve));
  upstreamPort = (upstream.address() as { port: number }).port;
});

test.afterAll(async () => { await new Promise<void>(resolve => upstream.close(() => resolve())); });

async function login(page: Page) {
  await page.goto('/');
  await page.getByLabel('管理令牌', { exact: true }).fill(fs.readFileSync(path.join(process.env.SQUIRE_E2E_DATA!, 'admin.token'), 'utf8').trim());
  await page.getByRole('button', { name: '连接', exact: true }).click();
  await expect(page.getByRole('heading', { name: '运行概览', exact: true })).toBeVisible();
}

test('management workflow, real proxy usage, editing, deletion and responsive views', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await login(page);
  await page.getByRole('button', { name: '来源管理', exact: true }).click();
  await page.getByRole('button', { name: '添加来源', exact: true }).first().click();
  await page.getByLabel('来源名称').fill('主力 Provider');
  await page.getByLabel('Base URL', { exact: true }).fill(`http://127.0.0.1:${upstreamPort}/v1`);
  await page.getByLabel('API Key', { exact: true }).fill('mock-api-key');
  await page.getByLabel('每日请求上限').fill('100');
  await page.getByLabel('每日 Token 上限').fill('100000');
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByText('主力 Provider', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: '添加来源', exact: true }).click();
  await page.getByLabel('来源名称').fill('备用 Provider');
  await page.getByLabel('Base URL', { exact: true }).fill(`http://127.0.0.1:${upstreamPort}/v1`);
  await page.getByLabel('API Key', { exact: true }).fill('mock-api-key');
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.screenshot({ path: 'test-results/providers-desktop.png', fullPage: true });

  await page.getByRole('button', { name: '配置方案', exact: true }).click();
  await page.getByRole('button', { name: '新建方案', exact: true }).first().click();
  await page.getByLabel('方案名称').fill('日常开发');
  await page.getByRole('button', { name: '负载均衡' }).click();
  await page.getByLabel('待添加来源').selectOption({ label: '主力 Provider' });
  await page.getByRole('button', { name: '添加', exact: true }).click();
  await page.getByLabel('待添加来源').selectOption({ label: '备用 Provider' });
  await page.getByRole('button', { name: '添加', exact: true }).click();
  await page.getByLabel('来源 1 权重', { exact: true }).fill('2');
  await page.getByRole('button', { name: '上移来源 2', exact: true }).click();
  await page.getByRole('button', { name: '下移来源 1', exact: true }).click();
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.getByRole('button', { name: '启用方案', exact: true }).click();
  await expect(page.getByText('当前生效')).toBeVisible();
  await page.screenshot({ path: 'test-results/schemes-desktop.png', fullPage: true });

  await page.getByRole('button', { name: 'Codex 接入', exact: true }).click();
  await expect(page.getByText('http://127.0.0.1:8798/v1', { exact: true })).toBeVisible();
  await page.getByLabel('模型 ID', { exact: true }).fill('mock-model');
  await page.getByRole('button', { name: '发送测试', exact: true }).click();
  await expect(page.getByText('HTTP 200', { exact: true })).toBeVisible();
  await expect(page.locator('.probe-result pre')).toContainText('resp_ui_test');
  await page.getByRole('button', { name: '请求记录', exact: true }).click();
  await expect(page.getByText('mock-model', { exact: true })).toBeVisible();
  await expect(page.getByText('15 / 2', { exact: true })).toBeVisible();
  await page.locator('.table-link').first().click();
  await expect(page.getByRole('dialog')).toContainText('上游已报告');
  await page.getByRole('button', { name: '关闭', exact: true }).last().click();
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: '导出本页' }).click();
  expect((await download).suggestedFilename()).toBe('squire-attempts-0.json');

  await page.getByRole('button', { name: '运行概览', exact: true }).click();
  await expect(page.getByText('17 tokens')).toBeVisible();
  await page.screenshot({ path: 'test-results/overview-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: 'test-results/overview-mobile.png', fullPage: true });
  for (const name of ['来源管理', '配置方案', 'Codex 接入']) {
    await page.getByRole('button', { name, exact: true }).first().click();
    await expect(page.getByRole('heading', { name: new RegExp(name) }).first()).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
  await page.getByRole('button', { name: '来源管理', exact: true }).click();
  await page.getByRole('button', { name: '编辑 主力 Provider', exact: true }).click();
  await page.screenshot({ path: 'test-results/provider-editor-mobile.png', fullPage: true });
  await page.getByLabel('来源名称').fill('主力 Provider 更新');
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByText('主力 Provider 更新', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '删除 主力 Provider 更新', exact: true }).click();
  await page.getByRole('button', { name: '确认删除', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('referenced');
  await page.getByRole('button', { name: '取消', exact: true }).click();
  await page.getByRole('button', { name: '配置方案', exact: true }).click();
  await page.getByRole('button', { name: '编辑 日常开发', exact: true }).click();
  await page.getByRole('button', { name: '移除来源 2', exact: true }).click();
  await page.getByRole('button', { name: '按序使用', exact: true }).click();
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.getByRole('button', { name: '来源管理', exact: true }).click();
  await page.getByRole('button', { name: '删除 备用 Provider', exact: true }).click();
  await page.getByRole('button', { name: '确认删除', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByText('备用 Provider', { exact: true })).toHaveCount(0);
  await page.reload();
  await expect(page.getByText('主力 Provider 更新', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '退出管理', exact: true }).click();
  await expect(page.getByRole('heading', { name: '连接本地工作空间' })).toBeVisible();
  expect(errors).toEqual([]);
});

test('installed Codex CLI completes a turn through the gateway and mock SSE upstream', async ({ request }) => {
  const adminToken = fs.readFileSync(path.join(process.env.SQUIRE_E2E_DATA!, 'admin.token'), 'utf8').trim();
  const headers = { Authorization: `Bearer ${adminToken}` };
  const created = await request.post('/api/providers', { headers, data: {
    name: 'Codex CLI smoke', baseUrl: `http://127.0.0.1:${upstreamPort}/v1`, apiKey: 'mock-api-key',
    enabled: true, dailyRequestLimit: 0, dailyTokenLimit: 0, maxConcurrency: 2, tokenReservation: 8192,
  } });
  expect(created.status()).toBe(201);
  const provider = await created.json();
  const schemeResponse = await request.post('/api/schemes', { headers, data: { name: 'CLI smoke', strategy: 'ORDERED', members: [{ providerId: provider.id, weight: 1 }] } });
  expect(schemeResponse.status()).toBe(201);
  const scheme = await schemeResponse.json();
  expect((await request.post(`/api/schemes/${scheme.id}/activate`, { headers })).status()).toBe(200);
  const connection = await (await request.get('/api/connection', { headers })).json();
  const args = [
    'exec', '--ignore-user-config', '--ignore-rules', '--ephemeral', '--skip-git-repo-check', '--sandbox', 'read-only', '--json',
    '-m', 'gpt-test', '-c', 'model_provider="squire_smoke"',
    '-c', `model_providers.squire_smoke={name="Squire smoke",base_url="${connection.baseUrl}",env_key="SQUIRE_SMOKE_GATEWAY_KEY",wire_api="responses",requires_openai_auth=false,supports_websockets=false}`,
    'Reply with OK. Do not call any tools.',
  ];
  const result = await new Promise<{ stdout: string; stderr: string }>((resolve, reject) => {
    const child = execFile('codex', args, { env: { ...process.env, SQUIRE_SMOKE_GATEWAY_KEY: connection.gatewayToken }, timeout: 45000, maxBuffer: 2 * 1024 * 1024, windowsHide: true },
      (error, stdout, stderr) => error ? reject(error) : resolve({ stdout, stderr }));
    child.stdin?.end();
  });
  const events = result.stdout.split('\n').filter(line => line.startsWith('{')).map(line => JSON.parse(line));
  expect(events.some(event => event.type === 'turn.completed')).toBe(true);
  expect(events.some(event => event.type === 'item.completed' && event.item?.text === 'OK')).toBe(true);
  const attempts = await (await request.get('/api/attempts', { headers })).json();
  expect(attempts.find((attempt: { providerId: string }) => attempt.providerId === provider.id)).toMatchObject({ status: 'SUCCEEDED', inputTokens: 15, outputTokens: 2, usageState: 'REPORTED' });
});
