import { useEffect, useState } from 'react';
import { Cable, Check, Eye, EyeOff, Play, Terminal } from 'lucide-react';
import type { ApiClient } from '../api';
import { Badge, CopyButton, ErrorNotice, errorMessage, IconButton } from '../components';
import type { Connection as ConnectionInfo } from '../types';

export function Connection({ api, active, refresh }: { api: ApiClient; active: boolean; refresh: () => Promise<void> }) {
  const [info, setInfo] = useState<ConnectionInfo | null>(null);
  const [error, setError] = useState('');
  const [visible, setVisible] = useState(false);
  const [model, setModel] = useState('');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState('');
  useEffect(() => {
    let alive = true;
    void api.connection().then(value => { if (alive) setInfo(value); }).catch(failure => { if (alive) setError(errorMessage(failure)); });
    return () => { alive = false; };
  }, [api]);
  const config = info ? `model_provider = "squire"\n\n[model_providers.squire]\nname = "Codex Squire"\nbase_url = "${info.baseUrl}"\nenv_key = "SQUIRE_GATEWAY_KEY"\nwire_api = "responses"\nrequires_openai_auth = false\nsupports_websockets = false\n` : '';
  async function probe() {
    if (!info) return;
    setBusy(true); setError(''); setResult('');
    try {
      const text = await api.gatewayResponse(info.gatewayToken,
        { model, input: 'Reply with OK.', stream: false, store: false, max_output_tokens: 32 });
      setResult(text.slice(0, 5000));
    } catch (failure) { setError(errorMessage(failure)); } finally { setBusy(false); await refresh(); }
  }
  return <>
    <div className="page-title"><div><div className="eyebrow">LOCAL GATEWAY</div><h1>Codex 接入</h1></div><Badge tone={active ? 'green' : 'amber'}>{active ? '方案已就绪' : '无生效方案'}</Badge></div>
    {error && <ErrorNotice message={error} />}
    {info && <>
      <section className="connection-section"><div className="section-title"><h2><Cable size={19} />本地接入端</h2><Badge>Responses</Badge></div>
        <dl className="connection-values"><dt>Base URL</dt><dd><code>{info.baseUrl}</code><CopyButton value={info.baseUrl} /></dd><dt>API Key</dt><dd><code className="secret">{visible ? info.gatewayToken : '••••••••••••••••••••••••'}</code><div className="row-actions"><IconButton icon={visible ? EyeOff : Eye} label={visible ? '隐藏密钥' : '显示密钥'} onClick={() => setVisible(!visible)} /><CopyButton value={info.gatewayToken} label="复制网关密钥" /></div></dd></dl></section>
      <section className="connection-section"><div className="section-title"><h2><Terminal size={19} />Codex 配置</h2><span className="muted mono">~/.codex/config.toml</span></div><div className="code-block"><pre>{config}</pre><CopyButton value={config} label="复制 Codex 配置" /></div>
        <div className="section-title small"><h3>PowerShell 环境变量</h3><CopyButton value={`$env:SQUIRE_GATEWAY_KEY = '${info.gatewayToken}'`} label="复制环境变量" /></div><div className="code-block"><pre>{`$env:SQUIRE_GATEWAY_KEY = '${visible ? info.gatewayToken : '<网关 API Key>'}'`}</pre></div></section>
      <section className="connection-section"><div className="section-title"><h2><Play size={18} />网关连通测试</h2><Badge tone="amber">消耗上游额度</Badge></div>
        <form className="probe-form" onSubmit={event => { event.preventDefault(); void probe(); }}><label>模型 ID<input required aria-label="模型 ID" maxLength={200} value={model} onChange={event => setModel(event.target.value)} placeholder="上游支持的模型 ID" /></label><button className="button primary" disabled={busy || !active}>{busy ? '请求中' : '发送测试'}<Play size={16} /></button></form>
        {result && <div className="probe-result"><Badge tone="green"><Check size={13} />HTTP 200</Badge><pre>{result}</pre></div>}
      </section><div className="storage-line">数据目录 <code>{info.dataDirectory}</code><span>限额时区 {info.quotaTimeZone}</span></div>
    </>}
  </>;
}
