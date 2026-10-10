export type RuntimeMode = 'local' | 'service';

export interface ClientRuntime {
  mode: RuntimeMode;
  backendUrl: string;
  gatewayUrl: string;
  webUrl: string;
  frontendBroadcast: boolean;
  adminTokenPath: string;
}

export interface StartRuntimeRequest {
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
  return typeof window === 'undefined' ? null : window.__SQUIRE_RUNTIME__ ?? null;
}

export async function discoverWebRuntime(): Promise<ClientRuntime | null> {
  if (isDesktopApp()) return initialRuntime();
  try {
    const response = await fetch('/__squire/runtime.json', { cache: 'no-store' });
    if (!response.ok) return null;
    const runtime = await response.json() as Partial<ClientRuntime>;
    if (!runtime.backendUrl || !runtime.gatewayUrl || !runtime.mode || !runtime.webUrl) return null;
    return { ...runtime, adminTokenPath: runtime.adminTokenPath ?? '' } as ClientRuntime;
  } catch {
    return null;
  }
}

export async function startDesktopRuntime(request: StartRuntimeRequest): Promise<ClientRuntime> {
  if (!isDesktopApp()) {
    throw new Error('Tauri runtime is unavailable');
  }
  const result = await invokeTauri<ClientRuntime>('start_runtime', { request });
  const runtime = result as ClientRuntime;
  window.__SQUIRE_RUNTIME__ = runtime;
  return runtime;
}

export async function stopDesktopRuntime(): Promise<void> {
  if (!isDesktopApp()) return;
  await invokeTauri('stop_runtime');
}

export async function desktopRuntimeStatus(): Promise<ClientRuntime | null> {
  if (!isDesktopApp()) return null;
  return invokeTauri<ClientRuntime | null>('runtime_status');
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
