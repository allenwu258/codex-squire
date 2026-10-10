import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiClient, ApiError } from './api';
import { clearManualToken, discardLegacyToken, readManualToken, storeManualToken, usesNativeAuth,
  type AuthSource, type SessionContext, type WorkspaceSession } from './auth';
import { desktopRuntimeStatus, discoverWebRuntime, getLocalAdminSession, initialRuntime, isDesktopApp,
  runtimeErrorMessage, type DesktopRuntime } from './runtime';

interface SessionOperation { epoch: number; controller: AbortController }

function failureCode(failure: unknown): string {
  if (failure instanceof ApiError && failure.status === 401) return 'LOCAL_AUTH_REJECTED';
  if (failure && typeof failure === 'object' && 'code' in failure && typeof failure.code === 'string') return failure.code;
  return 'SESSION_INITIALIZATION_FAILED';
}

export function useWorkspaceSession() {
  const [session, setSession] = useState<WorkspaceSession>({ status: 'discovering' });
  const current = useRef<WorkspaceSession>(session);
  const epoch = useRef(0);
  const operation = useRef<SessionOperation | null>(null);
  const recoveryUsed = useRef(false);
  const unauthorized = useRef<(sessionEpoch: number) => void>(() => {});
  const host = useRef(isDesktopApp() ? 'tauri' as const : 'browser' as const).current;

  const publish = useCallback((value: WorkspaceSession) => {
    current.current = value;
    setSession(value);
  }, []);

  const begin = useCallback((): SessionOperation => {
    operation.current?.controller.abort();
    const next = { epoch: ++epoch.current, controller: new AbortController() };
    operation.current = next;
    return next;
  }, []);

  const active = useCallback((candidate: SessionOperation) =>
    candidate.epoch === epoch.current && !candidate.controller.signal.aborted, []);

  const ready = useCallback((context: SessionContext, source: AuthSource, token: string, candidate: SessionOperation) => {
    if (!active(candidate)) return;
    const signal = candidate.controller.signal;
    const api = new ApiClient(token, context.runtime?.backendUrl, {
      signal, onUnauthorized: () => unauthorized.current(candidate.epoch),
    });
    publish({ ...context, status: 'ready', authSource: source, api, sessionEpoch: candidate.epoch, signal });
  }, [active, publish]);

  const authenticate = useCallback(async (target: SessionContext, savedToken = '') => {
    const context: SessionContext = { clientHost: target.clientHost, runtime: target.runtime };
    const candidate = begin();
    publish({ ...context, status: 'authenticating', sessionEpoch: candidate.epoch });
    if (!usesNativeAuth(context) && !savedToken) {
      publish({ ...context, status: 'login-required', message: '' });
      return;
    }
    try {
      if (usesNativeAuth(context)) {
        clearManualToken(context.runtime?.backendUrl);
        // A native credential is always verified by the backend before it becomes a session.
        for (;;) {
          const native = await getLocalAdminSession(context.runtime as DesktopRuntime);
          if (!active(candidate)) return;
          try {
            await new ApiClient(native.adminToken, native.backendUrl).providers(candidate.controller.signal);
            ready(context, 'native-local', native.adminToken, candidate);
            return;
          } catch (failure) {
            if (!active(candidate)) return;
            if (!(failure instanceof ApiError && failure.status === 401) || recoveryUsed.current) throw failure;
            recoveryUsed.current = true;
          }
        }
      }
      await new ApiClient(savedToken, context.runtime?.backendUrl).providers(candidate.controller.signal);
      ready(context, 'manual-token', savedToken, candidate);
    } catch (failure) {
      if (!active(candidate)) return;
      if (usesNativeAuth(context)) {
        const code = failureCode(failure);
        if (code === 'RUNTIME_NOT_RUNNING') publish({ status: 'stopped', message: runtimeErrorMessage(failure) });
        else publish({ ...context, status: 'fault', code,
          message: code === 'LOCAL_AUTH_REJECTED' ? '本地管理令牌未通过后端校验，请检查运行时后重试。' : runtimeErrorMessage(failure) });
      } else {
        if (failure instanceof ApiError && failure.status === 401) {
          clearManualToken(context.runtime?.backendUrl);
          publish({ ...context, status: 'login-required', message: '管理会话已失效，请重新输入令牌。' });
        } else {
          publish({ ...context, status: 'fault', code: failureCode(failure), message: runtimeErrorMessage(failure) });
        }
      }
    }
  }, [active, begin, publish, ready]);

  const discover = useCallback(async () => {
    const candidate = begin();
    publish({ status: 'discovering' });
    discardLegacyToken();
    try {
      const runtime = host === 'tauri' ? await desktopRuntimeStatus() : initialRuntime() ?? await discoverWebRuntime();
      if (!active(candidate)) return;
      if (host === 'tauri' && !runtime) {
        publish({ status: 'stopped', message: '' });
        return;
      }
      await authenticate({ clientHost: host, runtime }, host === 'browser' || runtime?.mode === 'service'
        ? readManualToken(runtime?.backendUrl) : '');
    } catch (failure) {
      if (active(candidate)) publish({ clientHost: host, runtime: null, status: 'fault',
        code: failureCode(failure), message: runtimeErrorMessage(failure) });
    }
  }, [active, authenticate, begin, host, publish]);

  unauthorized.current = sessionEpoch => {
    const value = current.current;
    if (value.status !== 'ready' || value.sessionEpoch !== sessionEpoch || value.signal.aborted) return;
    if (value.authSource === 'native-local') {
      if (!recoveryUsed.current) {
        recoveryUsed.current = true;
        void authenticate(value);
      } else {
        begin();
        publish({ clientHost: value.clientHost, runtime: value.runtime, status: 'fault', code: 'LOCAL_AUTH_REJECTED',
          message: '本地管理会话已失效，请检查运行时后重试。' });
      }
    } else {
      begin();
      clearManualToken(value.runtime?.backendUrl);
      publish({ clientHost: value.clientHost, runtime: value.runtime, status: 'login-required',
        message: '管理会话已失效，请重新输入令牌。' });
    }
  };

  useEffect(() => {
    void discover();
    return () => { ++epoch.current; operation.current?.controller.abort(); };
  }, [discover]);

  // Polling also runs during authentication faults so a stopped child cannot leave a stale fault screen.
  const runtimeId = 'runtime' in session ? session.runtime?.runtimeId : undefined;
  useEffect(() => {
    if (host !== 'tauri' || !runtimeId) return;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      const expectedEpoch = epoch.current;
      try {
        const runtime = await desktopRuntimeStatus();
        if (stopped || expectedEpoch !== epoch.current) return;
        if (!runtime) {
          const value = current.current;
          if ('runtime' in value) clearManualToken(value.runtime?.backendUrl);
          begin();
          publish({ status: 'stopped', message: '桌面后端已停止，请重新启动运行时。' });
        } else if (runtime.runtimeId !== runtimeId) {
          recoveryUsed.current = false;
          await authenticate({ clientHost: 'tauri', runtime }, runtime.mode === 'service' ? readManualToken(runtime.backendUrl) : '');
        }
      } catch (failure) {
        if (!stopped && expectedEpoch === epoch.current) {
          const value = current.current;
          begin();
          publish({ clientHost: host, runtime: 'runtime' in value ? value.runtime : null, status: 'fault',
            code: failureCode(failure), message: runtimeErrorMessage(failure) });
        }
      } finally {
        if (!stopped) timer = setTimeout(() => { void poll(); }, 2000);
      }
    }
    timer = setTimeout(() => { void poll(); }, 2000);
    return () => { stopped = true; clearTimeout(timer); };
  }, [authenticate, begin, host, publish, runtimeId]);

  const login = useCallback(async (token: string) => {
    const value = current.current;
    if (value.status !== 'login-required') return;
    const candidate = begin();
    try {
      await new ApiClient(token, value.runtime?.backendUrl).providers(candidate.controller.signal);
      if (!active(candidate)) return;
      storeManualToken(token, value.runtime?.backendUrl);
      ready(value, 'manual-token', token, candidate);
    } catch (failure) {
      if (active(candidate)) throw failure;
    }
  }, [active, begin, ready]);

  const logout = useCallback(() => {
    const value = current.current;
    if (value.status !== 'ready' || value.authSource !== 'manual-token') return;
    begin();
    clearManualToken(value.runtime?.backendUrl);
    publish({ clientHost: value.clientHost, runtime: value.runtime, status: 'login-required', message: '' });
  }, [begin, publish]);

  const started = useCallback((runtime: DesktopRuntime) => {
    recoveryUsed.current = false;
    clearManualToken(runtime.backendUrl);
    void authenticate({ clientHost: 'tauri', runtime });
  }, [authenticate]);

  const retry = useCallback(() => {
    recoveryUsed.current = false;
    void discover();
  }, [discover]);

  return { session, login, logout, started, retry };
}
