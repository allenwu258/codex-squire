import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Activity, Cable, ChevronRight, GitBranch, KeyRound, LayoutDashboard, LoaderCircle, LogOut, RefreshCw, Server } from 'lucide-react';
import { ApiClient, ApiError } from './api';
import { Badge, ErrorNotice, errorMessage, IconButton } from './components';
import { Overview } from './features/Overview';
import { Providers } from './features/Providers';
import { Schemes } from './features/Schemes';
import { Requests } from './features/Requests';
import { Connection } from './features/Connection';
import type { Provider, Scheme, Statistics } from './types';

const navigation = [
  { id: 'overview', name: '运行概览', icon: LayoutDashboard },
  { id: 'providers', name: '来源管理', icon: Server },
  { id: 'schemes', name: '配置方案', icon: GitBranch },
  { id: 'requests', name: '请求记录', icon: Activity },
  { id: 'connection', name: 'Codex 接入', icon: Cable },
];
const TOKEN_KEY = 'squire.adminToken';

export default function App() {
  const [token, setToken] = useState(() => sessionStorage.getItem(TOKEN_KEY) ?? '');
  const [page, setPage] = useState(() => location.hash.slice(1) || 'overview');
  const [data, setData] = useState<{ providers: Provider[]; schemes: Scheme[]; statistics: Statistics } | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  const mounted = useRef(true);
  const api = useMemo(() => new ApiClient(token), [token]);
  const refresh = useCallback(async () => {
    if (!token) return;
    setBusy(true);
    try {
      const [providers, schemes, statistics] = await Promise.all([api.providers(), api.schemes(), api.statistics()]);
      if (mounted.current) { setData({ providers, schemes, statistics }); setError(''); setRevision(value => value + 1); }
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) { sessionStorage.removeItem(TOKEN_KEY); setToken(''); setData(null); }
      if (mounted.current) setError(errorMessage(failure));
    } finally { if (mounted.current) setBusy(false); }
  }, [api, token]);
  useEffect(() => {
    mounted.current = true;
    void refresh();
    const timer = setInterval(() => { if (document.visibilityState === 'visible') void refresh(); }, 5000);
    return () => { mounted.current = false; clearInterval(timer); };
  }, [refresh]);
  useEffect(() => {
    const handler = () => setPage(location.hash.slice(1) || 'overview');
    window.addEventListener('hashchange', handler);
    return () => window.removeEventListener('hashchange', handler);
  }, []);
  function navigate(value: string) { location.hash = value; setPage(value); }
  function logout() { sessionStorage.removeItem(TOKEN_KEY); setToken(''); setData(null); setError(''); }
  if (!token) return <Login onConnect={value => { sessionStorage.setItem(TOKEN_KEY, value); setToken(value); }} />;
  const activePage = navigation.find(item => item.id === page) ?? navigation[0];
  return <div className="app-shell"><aside className="sidebar"><a className="brand" href="#overview" onClick={() => navigate('overview')}><img src="/squire.svg" alt="" /><span>Codex Squire</span></a>
    <div className="nav-caption">工作空间</div><nav aria-label="主导航">{navigation.map(item => <button key={item.id} aria-current={activePage.id === item.id ? 'page' : undefined} onClick={() => navigate(item.id)}><item.icon size={18} /><span>{item.name}</span>{activePage.id === item.id && <ChevronRight size={14} className="nav-chevron" />}</button>)}</nav>
    <div className="sidebar-bottom"><span className="status-dot on" />本地工作空间<span>v0.1.0</span></div></aside>
    <div className="main-shell"><header className="topbar"><span className="breadcrumb">工作空间 <ChevronRight size={13} />{activePage.name}</span><div className="topbar-actions"><Badge tone={error ? 'amber' : 'green'}>{error ? '连接异常' : data ? '服务在线' : '连接中'}</Badge><IconButton icon={busy ? LoaderCircle : RefreshCw} label="刷新" disabled={busy} onClick={() => void refresh()} /><IconButton icon={LogOut} label="退出管理" onClick={logout} /></div></header>
      <main>{error && <ErrorNotice message={error} />}{!data ? <div className="loading"><LoaderCircle className="spin" size={24} />正在连接本地后端</div> : <>
        {activePage.id === 'overview' && <Overview {...data} navigate={navigate} />}
        {activePage.id === 'providers' && <Providers api={api} providers={data.providers} statistics={data.statistics} refresh={refresh} />}
        {activePage.id === 'schemes' && <Schemes api={api} providers={data.providers} schemes={data.schemes} refresh={refresh} />}
        {activePage.id === 'requests' && <Requests api={api} revision={revision} />}
        {activePage.id === 'connection' && <Connection api={api} active={data.schemes.some(scheme => scheme.active)} refresh={refresh} />}
      </>}</main><footer className="app-footer"><span>Codex Squire</span><span>本地网关 · Responses API</span></footer></div>
  </div>;
}

function Login({ onConnect }: { onConnect: (token: string) => void }) {
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  return <div className="login-page"><div className="login-brand"><img src="/squire.svg" alt="Codex Squire" /><span>Codex Squire</span></div>
    <form className="login-form" onSubmit={event => {
      event.preventDefault(); setBusy(true); setError('');
      void new ApiClient(token.trim()).providers().then(() => onConnect(token.trim())).catch(failure => setError(errorMessage(failure))).finally(() => setBusy(false));
    }}><KeyRound size={25} /><h1>连接本地工作空间</h1><label>管理令牌<input autoFocus required type="password" autoComplete="current-password" value={token} onChange={event => setToken(event.target.value)} /></label>
      <div className="token-path"><span>令牌文件</span><code>~/.codex-squire/admin.token</code></div>{error && <ErrorNotice message={error} />}<button className="button primary" disabled={busy}>{busy ? <LoaderCircle className="spin" size={16} /> : <Cable size={16} />}连接</button>
    </form><span className="login-version">LOCAL WORKSPACE / 0.1.0</span></div>;
}
