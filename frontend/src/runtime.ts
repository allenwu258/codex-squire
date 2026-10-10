export type RuntimeMode = 'local' | 'service';

export interface ClientRuntime {
  runtimeId?: string;
  mode: RuntimeMode;
  backendUrl: string;
  gatewayUrl: string;
  webUrl: string;
  frontendBroadcast: boolean;
  adminTokenPath: string;
}

export interface DesktopRuntime extends ClientRuntime {
  runtimeId: string;
}

export interface NativeAdminSession {
  runtimeId: string;
  backendUrl: string;
  adminToken: string;
}

export interface RuntimePortSettings {
  backendPort: number;
  frontendPort: number;
}

export const DEFAULT_RUNTIME_PORT_SETTINGS: RuntimePortSettings = {
  backendPort: 18787,
  frontendPort: 18788,
};

export interface StartRuntimeRequest extends RuntimePortSettings {
  mode: RuntimeMode;
  frontendBroadcast: boolean;
  advertisedHost: string;
}

declare global {
  interface Window {
    __SQUIRE_RUNTIME__?: ClientRuntime;
    __TAURI_INTERNALS__?: { invoke: (command: string, args?: Record<string, unknown>) => Promise<unknown> };
    __TAURI__?: { core?: { invoke: (command: string, args?: Record<string, unknown>) => Promise<unknown> } };
  }
}

export function isDesktopApp(): boolean {
  return typeof window !== 'undefined'
    && (window.location.protocol === 'tauri:' || window.__TAURI_INTERNALS__ !== undefined || window.__TAURI__ !== undefined);
}

export function initialRuntime(): ClientRuntime | null {
  return typeof window === 'undefined' ? null : parseWebRuntime(window.__SQUIRE_RUNTIME__);
}

function httpUrl(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  try {
    const url = new URL(value);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) return null;
    return url.href.replace(/\/$/, '');
  } catch { return null; }
}

export function parseWebRuntime(value: unknown): ClientRuntime | null {
  if (!value || typeof value !== 'object') return null;
  const fields = value as Record<string, unknown>;
  const backendUrl = httpUrl(fields.backendUrl);
  const gatewayUrl = httpUrl(fields.gatewayUrl);
  const webUrl = httpUrl(fields.webUrl);
  if (!backendUrl || !gatewayUrl || !webUrl || (fields.mode !== 'local' && fields.mode !== 'service')
    || typeof fields.frontendBroadcast !== 'boolean') return null;
  // Public configuration can describe an endpoint, never an authentication policy or credential.
  return { mode: fields.mode as RuntimeMode, backendUrl, gatewayUrl, webUrl,
    frontendBroadcast: fields.frontendBroadcast, adminTokenPath: '',
    runtimeId: typeof fields.runtimeId === 'string' ? fields.runtimeId : undefined };
}

function parseDesktopRuntime(value: unknown): DesktopRuntime {
  const runtime = parseWebRuntime(value);
  if (!runtime?.runtimeId || new URL(runtime.backendUrl).hostname !== '127.0.0.1'
    || new URL(runtime.backendUrl).protocol !== 'http:' || new URL(runtime.backendUrl).pathname !== '/') {
    throw new Error('桌面运行时返回了无效的实例或后端地址。');
  }
  const fields = value as Record<string, unknown>;
  return { ...runtime, runtimeId: runtime.runtimeId, adminTokenPath: typeof fields.adminTokenPath === 'string' ? fields.adminTokenPath : '' };
}

export async function discoverWebRuntime(): Promise<ClientRuntime | null> {
  if (isDesktopApp()) return initialRuntime();
  try {
    const response = await fetch('/__squire/runtime.json', { cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(5000) });
    if (!response.ok) return null;
    return parseWebRuntime(await response.json());
  } catch {
    return null;
  }
}

export async function startDesktopRuntime(request: StartRuntimeRequest): Promise<DesktopRuntime> {
  if (!isDesktopApp()) {
    throw new Error('Tauri runtime is unavailable');
  }
  return parseDesktopRuntime(await invokeTauri('start_runtime', { request }));
}

export async function stopDesktopRuntime(): Promise<void> {
  if (!isDesktopApp()) return;
  await invokeTauri('stop_runtime');
}

export async function desktopRuntimeStatus(): Promise<DesktopRuntime | null> {
  if (!isDesktopApp()) return null;
  const value = await invokeTauri('runtime_status');
  return value === null ? null : parseDesktopRuntime(value);
}

export async function getLocalAdminSession(runtime: DesktopRuntime): Promise<NativeAdminSession> {
  const value = await invokeTauri<NativeAdminSession>('get_local_admin_session', { expectedRuntimeId: runtime.runtimeId });
  if (value.runtimeId !== runtime.runtimeId || value.backendUrl !== runtime.backendUrl
    || typeof value.adminToken !== 'string' || !/^[\x21-\x7e]+$/.test(value.adminToken)) {
    throw new Error('本地认证返回了无效的会话，请重试。');
  }
  return value;
}

export async function desktopRuntimePortSettings(): Promise<RuntimePortSettings> {
  return invokeTauri<RuntimePortSettings>('runtime_port_settings');
}

/** Convert the different rejection shapes used by Tauri versions into text.
 * Tauri commands returning `Result<T, String>` may reject with a plain string,
 * while some runtimes wrap the same value in an object. Keeping this here
 * prevents the launcher from hiding the backend path and log diagnostics.
 */
export function runtimeErrorMessage(failure: unknown): string {
  if (failure instanceof Error && failure.message.trim()) return failure.message;
  if (typeof failure === 'string' && failure.trim()) return failure;
  if (failure && typeof failure === 'object') {
    const value = failure as { message?: unknown; error?: unknown; details?: unknown };
    for (const candidate of [value.message, value.error, value.details]) {
      if (typeof candidate === 'string' && candidate.trim()) return candidate;
    }
    try {
      const serialized = JSON.stringify(failure);
      if (serialized && serialized !== '{}') return serialized;
    } catch {
      // Fall through to the stable generic message below.
    }
  }
  return '启动运行时失败';
}

async function invokeTauri<T>(command: string, args?: Record<string, unknown>): Promise<T> {
  const invoke = window.__TAURI_INTERNALS__?.invoke ?? window.__TAURI__?.core?.invoke;
  if (!invoke) throw new Error('Tauri invoke API is unavailable');
  return invoke(command, args) as Promise<T>;
}
