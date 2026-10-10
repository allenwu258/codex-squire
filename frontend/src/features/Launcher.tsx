import { useState } from 'react';
import { Globe2, LoaderCircle, Monitor, Server, ShieldAlert } from 'lucide-react';
import { ErrorNotice } from '../components';
import { runtimeErrorMessage, startDesktopRuntime, type ClientRuntime, type RuntimeMode } from '../runtime';

export function Launcher({ onStarted, initialError = '' }: { onStarted: (runtime: ClientRuntime) => void; initialError?: string }) {
  const [mode, setMode] = useState<RuntimeMode>('local');
  const [frontendBroadcast, setFrontendBroadcast] = useState(false);
  const [advertisedHost, setAdvertisedHost] = useState('127.0.0.1');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(initialError);

  async function start() {
    setBusy(true);
    setError('');
    try {
      const runtime = await startDesktopRuntime({ mode, frontendBroadcast, advertisedHost: advertisedHost.trim() });
      onStarted(runtime);
    } catch (failure) {
      setError(runtimeErrorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  return <div className="launcher-page">
    <div className="launcher-header">
      <img src="/branding/codex-squire-icon-white.svg" alt="Codex Squire" />
      <div><span className="eyebrow">DESKTOP RUNTIME</span><h1>启动 Codex Squire</h1><p>选择运行范围，启动后端和管理界面。</p></div>
    </div>
    {error && <ErrorNotice message={error} />}
    <div className="launcher-modes" role="radiogroup" aria-label="运行模式">
      <button className={`launcher-mode ${mode === 'local' ? 'selected' : ''}`} aria-pressed={mode === 'local'} onClick={() => setMode('local')}>
        <Monitor size={22} /><span><strong>本地模式</strong><small>仅本机访问，打开桌面管理界面</small></span>
      </button>
      <button className={`launcher-mode ${mode === 'service' ? 'selected' : ''}`} aria-pressed={mode === 'service'} onClick={() => setMode('service')}>
        <Server size={22} /><span><strong>服务模式</strong><small>启动可供本机或局域网使用的服务</small></span>
      </button>
    </div>
    {mode === 'service' && <section className="launcher-service-options">
      <div className="launcher-warning"><ShieldAlert size={18} /><span>服务模式会让后端监听所有网卡。当前版本没有 TLS、RBAC 或多用户隔离，不要直接暴露到公网。</span></div>
      <label>公布的主机名或局域网 IP<input value={advertisedHost} onChange={event => setAdvertisedHost(event.target.value)} placeholder="例如 192.168.1.20" /></label>
      <label className="checkbox"><input type="checkbox" checked={frontendBroadcast} onChange={event => setFrontendBroadcast(event.target.checked)} /><span>允许局域网浏览器访问管理界面</span></label>
      <p className="muted">关闭时前端只绑定 127.0.0.1；网关仍按服务模式监听 0.0.0.0。</p>
    </section>}
    <button className="button primary launcher-start" disabled={busy || (mode === 'service' && !advertisedHost.trim())} onClick={() => void start()}>
      {busy ? <LoaderCircle className="spin" size={17} /> : mode === 'local' ? <Monitor size={17} /> : <Globe2 size={17} />}
      {busy ? '启动中' : '启动运行时'}
    </button>
    <p className="launcher-footnote">Java 后端由启动器托管，退出应用时会一并停止。</p>
  </div>;
}
