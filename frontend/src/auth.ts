import type { ApiClient } from './api';
import type { ClientRuntime } from './runtime';

export type ClientHost = 'browser' | 'tauri';
export type AuthSource = 'manual-token' | 'native-local';

export interface SessionContext {
  clientHost: ClientHost;
  runtime: ClientRuntime | null;
}

export type WorkspaceSession =
  | { status: 'discovering' }
  | { status: 'stopped'; message: string }
  | ({ status: 'authenticating'; sessionEpoch: number } & SessionContext)
  | ({ status: 'login-required'; message: string } & SessionContext)
  | ({ status: 'ready'; authSource: AuthSource; api: ApiClient; sessionEpoch: number; signal: AbortSignal } & SessionContext)
  | ({ status: 'fault'; code: string; message: string } & SessionContext);

export function usesNativeAuth(context: SessionContext): boolean {
  return context.clientHost === 'tauri' && context.runtime?.mode === 'local';
}

function storageKey(baseUrl?: string): string {
  const scope = baseUrl ? new URL(baseUrl).href.replace(/\/$/, '') : `${location.origin}/@proxy`;
  return `squire.adminSession:${encodeURIComponent(scope)}`;
}

export function discardLegacyToken(): void {
  try { sessionStorage.removeItem('squire.adminToken'); } catch { /* Storage may be disabled. */ }
}

export function readManualToken(baseUrl?: string): string {
  try { return sessionStorage.getItem(storageKey(baseUrl)) ?? ''; } catch { return ''; }
}

export function storeManualToken(token: string, baseUrl?: string): void {
  try { sessionStorage.setItem(storageKey(baseUrl), token); } catch { /* The session still works in memory. */ }
}

export function clearManualToken(baseUrl?: string): void {
  try { sessionStorage.removeItem(storageKey(baseUrl)); } catch { /* Storage may be disabled. */ }
}
