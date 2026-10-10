import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Activity, Cable, ChevronRight, GitBranch, KeyRound, LayoutDashboard, LoaderCircle, LogOut, RefreshCw, Server } from 'lucide-react';
import { ApiClient, ApiError } from './api';
import { Badge, ErrorNotice, errorMessage, IconButton } from './components';
import { Overview } from './features/Overview';
import { Providers } from './features/Providers';
import { Schemes } from './features/Schemes';
import { Requests } from './features/Requests';
import { Connection } from './features/Connection';
import type { Provider, ProviderRuntimeState, Scheme, Statistics } from './types';
import { desktopRuntimeStatus, discoverWebRuntime, initialRuntime, isDesktopApp, type ClientRuntime } from './runtime';
import { Launcher } from './features/Launcher';

const navigation = [
  { id: 'overview', name: '运行概览', icon: LayoutDashboard },
  { id: 'providers', name: '来源管理', icon: Server },
  { id: 'schemes', name: '配置方案', icon: GitBranch },
  { id: 'requests', name: '请求记录', icon: Activity },
  { id: 'connection', name: 'Codex 接入', icon: Cable },
];
const TOKEN_KEY = 'squire.adminToken';

export default function App() {
  const [runtime, setRuntime] = useState<ClientRuntime | null>(initialRuntime);
  const [runtimeDiscoveryComplete, setRuntimeDiscoveryComplete] = useState(() => isDesktopApp() || initialRuntime() !== null);
  const [token, setToken] = useState(() => sessionStorage.getItem(TOKEN_KEY) ?? '');
  const [page, setPage] = useState(() => location.hash.slice(1) || 'overview');
  const [data, setData] = useState<{ providers: Provider[]; schemes: Scheme[]; statistics: Statistics; runtime: ProviderRuntimeState[] } | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  const [runtimeFault, setRuntimeFault] = useState('');
  const mounted = useRef(true);
  const refreshSequence = useRef(0);
  const refreshController = useRef<AbortController | null>(null);
  const api = useMemo(() => new ApiClient(token, runtime?.backendUrl), [runtime?.backendUrl, token]);
  useEffect(() => {
    if (isDesktopApp() || runtime) {
      setRuntimeDiscoveryComplete(true);
      return;
    }
    let alive = true;
    void discoverWebRuntime().then(value => {
      if (!alive) return;
      if (value) setRuntime(value);
      setRuntimeDiscoveryComplete(true);
    });
    return () => { alive = false; };
  }, [runtime]);
  const refresh = useCallback(async () => {
    if (!token) return;
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
      if (mounted.current && sequence === refreshSequence.current) {
        setData({ providers, schemes, statistics, runtime }); setError(''); setRevision(value => value + 1);
      }
    } catch (failure) {
      if (controller.signal.aborted) return;
      if (failure instanceof ApiError && failure.status === 401) { sessionStorage.removeItem(TOKEN_KEY); setToken(''); setData(null); }
      if (mounted.current && sequence === refreshSequence.current) setError(errorMessage(failure));
    } finally {
      if (refreshController.current === controller) refreshController.current = null;
      if (mounted.current && sequence === refreshSequence.current) setBusy(false);
    }
  }, [api, token]);
  useEffect(() => {
    mounted.current = true;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      if (document.visibilityState === 'visible' && !refreshController.current) await refresh();
      if (!stopped) timer = setTimeout(() => { void poll(); }, 5000);
    }
    void poll();
    return () => {
      stopped = true; mounted.current = false; clearTimeout(timer);
      refreshController.current?.abort(); refreshController.current = null;
    };
  }, [refresh]);
  useEffect(() => {
    if (!isDesktopApp() || !runtime) return;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    async function pollRuntime() {
      try {
        const status = await desktopRuntimeStatus();
        if (!status && !stopped) {
          setRuntimeFault('桌面后端已停止，请重新启动运行时。');
          sessionStorage.removeItem(TOKEN_KEY);
          setToken('');
          setData(null);
          setRuntime(null);
        }
      } catch (failure) {
        if (!stopped) {
          setRuntimeFault(failure instanceof Error ? failure.message : '无法读取桌面运行时状态。');
          setError(failure instanceof Error ? failure.message : '无法读取桌面运行时状态。');
          setData(null);
        }
      } finally {
        if (!stopped) timer = setTimeout(() => { void pollRuntime(); }, 2000);
      }
    }
    void pollRuntime();
    return () => { stopped = true; clearTimeout(timer); };
  }, [runtime]);
  useEffect(() => {
    const handler = () => setPage(location.hash.slice(1) || 'overview');
    window.addEventListener('hashchange', handler);
    return () => window.removeEventListener('hashchange', handler);
  }, []);
  function navigate(value: string) { location.hash = value; setPage(value); }
  function logout() { sessionStorage.removeItem(TOKEN_KEY); setToken(''); setData(null); setError(''); }
  if (!runtimeDiscoveryComplete) return <div className="loading"><LoaderCircle className="spin" size={24} />正在读取运行时配置</div>;
  if (isDesktopApp() && !runtime) return <Launcher initialError={runtimeFault} onStarted={value => { sessionStorage.removeItem(TOKEN_KEY); setToken(''); setRuntimeFault(''); setRuntime(value); }} />;
  if (!token) return <Login baseUrl={runtime?.backendUrl} tokenPath={runtime?.adminTokenPath} onConnect={value => { sessionStorage.setItem(TOKEN_KEY, value); setToken(value); }} />;
  const activePage = navigation.find(item => item.id === page) ?? navigation[0];
  return <div className="app-shell"><aside className="sidebar"><a className="brand" href="#overview" onClick={() => navigate('overview')}><img src="/branding/codex-squire-icon-white.svg" alt="Codex Squire" /><span>Codex Squire</span></a>
    <div className="nav-caption">工作空间</div><nav aria-label="主导航">{navigation.map(item => <button key={item.id} aria-current={activePage.id === item.id ? 'page' : undefined} onClick={() => navigate(item.id)}><item.icon size={18} /><span>{item.name}</span>{activePage.id === item.id && <ChevronRight size={14} className="nav-chevron" />}</button>)}</nav>
    <div className="sidebar-bottom"><span className="status-dot on" />本地工作空间<span>v0.1.0</span></div></aside>
    <div className="main-shell"><header className="topbar"><span className="breadcrumb">工作空间 <ChevronRight size={13} />{activePage.name}</span><div className="topbar-actions"><Badge tone={error ? 'amber' : 'green'}>{error ? '连接异常' : data ? '服务在线' : '连接中'}</Badge><IconButton icon={busy ? LoaderCircle : RefreshCw} label="刷新" disabled={busy} onClick={() => void refresh()} /><IconButton icon={LogOut} label="退出管理" onClick={logout} /></div></header>
      <main>{error && <ErrorNotice message={error} />}{!data ? <div className="loading"><LoaderCircle className="spin" size={24} />正在连接本地后端</div> : <>
        {activePage.id === 'overview' && <Overview {...data} navigate={navigate} />}
        {activePage.id === 'providers' && <Providers api={api} providers={data.providers} runtime={data.runtime} statistics={data.statistics} refresh={refresh} />}
        {activePage.id === 'schemes' && <Schemes api={api} providers={data.providers} schemes={data.schemes} refresh={refresh} />}
        {activePage.id === 'requests' && <Requests api={api} revision={revision} />}
        {activePage.id === 'connection' && <Connection api={api} active={data.schemes.some(scheme => scheme.active)} refresh={refresh} />}
      </>}</main><footer className="app-footer"><span>Codex Squire</span><span>本地网关 · Responses API</span></footer></div>
  </div>;
}

function Login({ onConnect, tokenPath, baseUrl }: { onConnect: (token: string) => void; tokenPath?: string; baseUrl?: string }) {
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  return <div className="login-page"><div className="login-brand"><img src="/branding/codex-squire-icon-white.svg" alt="Codex Squire" /></div>
    <form className="login-form" onSubmit={event => {
      event.preventDefault(); setBusy(true); setError('');
      void new ApiClient(token.trim(), baseUrl).providers().then(() => onConnect(token.trim())).catch(failure => setError(errorMessage(failure))).finally(() => setBusy(false));
    }}><KeyRound size={25} /><h1>连接本地工作空间</h1><label>管理令牌<input autoFocus required type="password" autoComplete="current-password" value={token} onChange={event => setToken(event.target.value)} /></label>
      <div className="token-path"><span>令牌文件</span><code>{tokenPath || '启动主机数据目录中的 admin.token'}</code></div>{error && <ErrorNotice message={error} />}<button className="button primary" disabled={busy}>{busy ? <LoaderCircle className="spin" size={16} /> : <Cable size={16} />}连接</button>
    </form><span className="login-version">LOCAL WORKSPACE / 0.1.0</span></div>;
}
