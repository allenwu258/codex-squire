import { test, expect } from '@playwright/test';

test('launcher loads saved ports and preserves edited values when startup fails', async ({ page }) => {
  await page.addInitScript(() => {
    const state = window as Window & { portRequests: unknown[] };
    state.portRequests = [];
    window.__TAURI_INTERNALS__ = { invoke: async (command, args) => {
      if (command === 'runtime_port_settings') return { backendPort: 19887, frontendPort: 19888 };
      if (command === 'start_runtime') {
        state.portRequests.push(args?.request);
        throw 'Backend port 19889 is in use';
      }
      return null;
    } };
  });
  await page.goto('/');
  const backend = page.getByLabel('\u540e\u7aef\u7aef\u53e3', { exact: true });
  await expect(backend).toHaveValue('19887');
  await backend.fill('19889');
  await page.getByRole('button', { name: /\u670d\u52a1\u6a21\u5f0f/ }).click();
  const frontend = page.getByLabel('\u7ba1\u7406\u7f51\u9875\u7aef\u53e3', { exact: true });
  await expect(frontend).toHaveValue('19888');
  await frontend.fill('19890');
  await page.getByRole('button', { name: '\u542f\u52a8\u8fd0\u884c\u65f6', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Backend port 19889 is in use');
  await expect(backend).toHaveValue('19889');
  await expect(frontend).toHaveValue('19890');
  expect(await page.evaluate(() => (window as Window & { portRequests: unknown[] }).portRequests)).toEqual([
    { mode: 'service', frontendBroadcast: false, advertisedHost: '127.0.0.1', backendPort: 19889, frontendPort: 19890 },
  ]);
  await page.screenshot({ path: 'test-results/launcher-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.screenshot({ path: 'test-results/launcher-mobile.png', fullPage: true });
});

test('launcher rejects invalid numeric ports before invoking the desktop runtime', async ({ page }) => {
  await page.addInitScript(() => {
    const state = window as Window & { starts: number };
    state.starts = 0;
    window.__TAURI_INTERNALS__ = { invoke: async command => {
      if (command === 'runtime_port_settings') return { backendPort: 18787, frontendPort: 18788 };
      if (command === 'start_runtime') state.starts++;
      return null;
    } };
  });
  await page.goto('/');
  const backend = page.getByLabel('\u540e\u7aef\u7aef\u53e3', { exact: true });
  await expect(backend).toHaveValue('18787');
  for (const invalid of ['80', '65536', '18787.5', '']) {
    await backend.fill(invalid);
    await page.getByRole('button', { name: '\u542f\u52a8\u8fd0\u884c\u65f6', exact: true }).click();
    expect(await backend.evaluate(input => (input as HTMLInputElement).validity.valid)).toBe(false);
    expect(await page.evaluate(() => (window as Window & { starts: number }).starts)).toBe(0);
  }
});

test('launcher blocks startup after a settings read error and allows retry', async ({ page }) => {
  await page.addInitScript(() => {
    const state = window as Window & { settingsReadable: boolean };
    state.settingsReadable = false;
    window.__TAURI_INTERNALS__ = { invoke: async command => {
      if (command === 'runtime_port_settings') {
        if (!state.settingsReadable) throw 'Port settings could not be read';
        return { backendPort: 18787, frontendPort: 18788 };
      }
      return null;
    } };
  });
  await page.goto('/');
  await expect(page.getByRole('alert')).toContainText('Port settings could not be read');
  const start = page.getByRole('button', { name: '\u542f\u52a8\u8fd0\u884c\u65f6', exact: true });
  await expect(start).toBeDisabled();
  await page.evaluate(() => { (window as Window & { settingsReadable: boolean }).settingsReadable = true; });
  await page.getByRole('button', { name: '\u91cd\u65b0\u8bfb\u53d6\u7aef\u53e3', exact: true }).click();
  await expect(page.getByLabel('\u540e\u7aef\u7aef\u53e3', { exact: true })).toHaveValue('18787');
  await expect(start).toBeEnabled();
  await expect(page.getByRole('alert')).toHaveCount(0);
});
