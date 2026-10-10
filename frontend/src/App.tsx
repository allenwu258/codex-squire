import { useCallback, useEffect, useRef, useState } from 'react';
import { Activity, Cable, ChevronRight, GitBranch, KeyRound, LayoutDashboard, LoaderCircle, LogOut, RefreshCw, Server, ShieldAlert } from 'lucide-react';
import { Badge, ErrorNotice, errorMessage, IconButton } from './components';
import { Overview } from './features/Overview';
import { Providers } from './features/Providers';
import { Schemes } from './features/Schemes';
import { Requests } from './features/Requests';
import { Connection } from './features/Connection';
import { Launcher } from './features/Launcher';
import type { Provider, ProviderRuntimeState, Scheme, Statistics } from './types';
import type { WorkspaceSession } from './auth';
import { usesNativeAuth } from './auth';
import { useWorkspaceSession } from './useWorkspaceSession';

const navigation = [
  { id: 'overview', name: '运行概览', icon: LayoutDashboard },
  { id: 'providers', name: '来源管理', icon: Server },
  { id: 'schemes', name: '配置方案', icon: GitBranch },
  { id: 'requests', name: '请求记录', icon: Activity },
  { id: 'connection', name: 'Codex 接入', icon: Cable },
];

export default function App() {
  const { session, login, logout, started, retry } = useWorkspaceSession();
  switch (session.status) {
    case 'discovering':
      return <SessionLoading message="正在读取运行时配置" />;
    case 'stopped':
      return <Launcher initialError={session.message} onStarted={started} />;
    case 'authenticating':
      return <SessionLoading message={usesNativeAuth(session) ? '正在连接本地工作空间' : '正在校验管理会话'} />;
    case 'fault':
      return <div className="session-fault"><ShieldAlert size={28} /><h1>{usesNativeAuth(session) ? '本地认证失败' : '运行时连接失败'}</h1>
        <ErrorNotice message={session.message} /><button className="button primary" onClick={retry}><RefreshCw size={16} />重试连接</button></div>;
    case 'login-required':
      return <Login key={session.runtime?.backendUrl ?? 'proxy'} tokenPath={session.runtime?.adminTokenPath}
        initialError={session.message} onConnect={login} />;
    case 'ready':
      return <Workspace key={session.sessionEpoch} session={session} logout={logout} />;
  }
}

function SessionLoading({ message }: { message: string }) {
  return <div className="loading" role="status"><LoaderCircle className="spin" size={24} />{message}</div>;
}

function Workspace({ session, logout }: { session: Extract<WorkspaceSession, { status: 'ready' }>; logout: () => void }) {
  const { api, signal } = session;
  const [page, setPage] = useState(() => location.hash.slice(1) || 'overview');
  const [data, setData] = useState<{ providers: Provider[]; schemes: Scheme[]; statistics: Statistics; runtime: ProviderRuntimeState[] } | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  const refreshSequence = useRef(0);
  const refreshController = useRef<AbortController | null>(null);

  const refresh = useCallback(async () => {
    if (signal.aborted) return;
    const sequence = ++refreshSequence.current;
    refreshController.current?.abort();
    const controller = new AbortController();
    refreshController.current = controller;
    setBusy(true);
    try {
      const [providers, schemes, statistics, runtime] = await Promise.all([
        api.providers(controller.signal), api.schemes(controller.signal), api.statistics(controller.signal),
        api.providerRuntime(controller.signal),
      ]);
      if (!signal.aborted && !controller.signal.aborted && sequence === refreshSequence.current) {
        setData({ providers, schemes, statistics, runtime }); setError(''); setRevision(value => value + 1);
      }
    } catch (failure) {
      if (!signal.aborted && !controller.signal.aborted && sequence === refreshSequence.current) setError(errorMessage(failure));
    } finally {
      if (refreshController.current === controller) refreshController.current = null;
      if (!signal.aborted && !controller.signal.aborted && sequence === refreshSequence.current) setBusy(false);
    }
  }, [api, signal]);

  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      if (document.visibilityState === 'visible' && !refreshController.current) await refresh();
      if (!stopped) timer = setTimeout(() => { void poll(); }, 5000);
    }
    void poll();
    return () => { stopped = true; clearTimeout(timer); refreshController.current?.abort(); };
  }, [refresh]);
  useEffect(() => {
    const handler = () => setPage(location.hash.slice(1) || 'overview');
    window.addEventListener('hashchange', handler);
    return () => window.removeEventListener('hashchange', handler);
  }, []);

  function navigate(value: string) { location.hash = value; setPage(value); }
  const activePage = navigation.find(item => item.id === page) ?? navigation[0];
  return <div className="app-shell"><aside className="sidebar"><a className="brand" href="#overview" onClick={() => navigate('overview')}><img src="/branding/codex-squire-icon-white.svg" alt="Codex Squire" /><span>Codex Squire</span></a>
    <div className="nav-caption">工作空间</div><nav aria-label="主导航">{navigation.map(item => <button key={item.id} aria-current={activePage.id === item.id ? 'page' : undefined} onClick={() => navigate(item.id)}><item.icon size={18} /><span>{item.name}</span>{activePage.id === item.id && <ChevronRight size={14} className="nav-chevron" />}</button>)}</nav>
    <div className="sidebar-bottom"><span className="status-dot on" />本地工作空间<span>v0.2.2</span></div></aside>
    <div className="main-shell"><header className="topbar"><span className="breadcrumb">工作空间 <ChevronRight size={13} />{activePage.name}</span><div className="topbar-actions"><Badge tone={error ? 'amber' : 'green'}>{error ? '连接异常' : data ? '服务在线' : '连接中'}</Badge><IconButton icon={busy ? LoaderCircle : RefreshCw} label="刷新" disabled={busy} onClick={() => void refresh()} />{session.authSource === 'manual-token' && <IconButton icon={LogOut} label="退出管理" onClick={logout} />}</div></header>
      <main>{error && <ErrorNotice message={error} />}{!data ? <SessionLoading message="正在连接工作空间" /> : <>
        {activePage.id === 'overview' && <Overview {...data} navigate={navigate} />}
        {activePage.id === 'providers' && <Providers api={api} providers={data.providers} runtime={data.runtime} statistics={data.statistics} refresh={refresh} />}
        {activePage.id === 'schemes' && <Schemes api={api} providers={data.providers} schemes={data.schemes} refresh={refresh} />}
        {activePage.id === 'requests' && <Requests api={api} revision={revision} />}
        {activePage.id === 'connection' && <Connection api={api} active={data.schemes.some(scheme => scheme.active)} refresh={refresh} />}
      </>}</main><footer className="app-footer"><span>Codex Squire</span><span>本地网关 · Responses API</span></footer></div>
  </div>;
}

function Login({ onConnect, tokenPath, initialError }: {
  onConnect: (token: string) => Promise<void>; tokenPath?: string; initialError: string;
}) {
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(initialError);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => { setError(initialError); }, [initialError]);
  async function connect() {
    setBusy(true); setError('');
    try { await onConnect(token.trim()); }
    catch (failure) { if (alive.current) setError(errorMessage(failure)); }
    finally { if (alive.current) setBusy(false); }
  }
  return <div className="login-page"><div className="login-brand"><img src="/branding/codex-squire-icon-white.svg" alt="Codex Squire" /></div>
    <form className="login-form" onSubmit={event => { event.preventDefault(); void connect(); }}><KeyRound size={25} /><h1>连接本地工作空间</h1><label>管理令牌<input autoFocus required disabled={busy} type="password" autoComplete="current-password" value={token} onChange={event => setToken(event.target.value)} /></label>
      <div className="token-path"><span>令牌文件</span><code>{tokenPath || '启动主机数据目录中的 admin.token'}</code></div>{error && <ErrorNotice message={error} />}<button className="button primary" disabled={busy || !token.trim()}>{busy ? <LoaderCircle className="spin" size={16} /> : <Cable size={16} />}连接</button>
    </form><span className="login-version">LOCAL WORKSPACE / 0.2.2</span></div>;
}
