import { useEffect, useState } from 'react';
import { Activity, Pencil, Plus, Search, Server, Settings2, Trash2, RotateCcw, X } from 'lucide-react';
import { Badge, ConfirmDelete, Empty, ErrorNotice, errorMessage, IconButton, Modal, number, SaveForm } from '../components';
import type { ApiClient } from '../api';
import type { Provider, ProviderCapability, ProviderInput, ProviderProbeResult, ProviderRuntimeState, QuotaRuleInput, Statistics } from '../types';

interface Props { api: ApiClient; providers: Provider[]; runtime: ProviderRuntimeState[]; statistics: Statistics; refresh: () => Promise<void> }

const healthLabels: Record<string, string> = { READY: '健康', RATE_LIMITED: '冷却中', UNHEALTHY: '异常', UNKNOWN: '未探测' };
const metrics = [{ value: 'REQUEST_COUNT', label: '请求数' }, { value: 'INPUT_TOKENS', label: '输入 Token' },
  { value: 'OUTPUT_TOKENS', label: '输出 Token' }, { value: 'TOTAL_TOKENS', label: '总 Token' }, { value: 'CONCURRENT_REQUESTS', label: '并发数' }] as const;
const windows = [{ value: 'MINUTE', label: '分钟' }, { value: 'HOUR', label: '小时' }, { value: 'DAY', label: '日' },
  { value: 'WEEK', label: '周' }, { value: 'MONTH', label: '月' }] as const;
const actions = [{ value: 'SKIP_PROVIDER', label: '跳过来源' }, { value: 'REJECT_REQUEST', label: '拒绝请求' },
  { value: 'ENTER_COOLDOWN', label: '进入冷却' }] as const;

export function Providers({ api, providers, runtime, statistics, refresh }: Props) {
  const [query, setQuery] = useState('');
  const [editing, setEditing] = useState<Provider | 'new' | null>(null);
  const [deleting, setDeleting] = useState<Provider | null>(null);
  const [quotaProvider, setQuotaProvider] = useState<Provider | null>(null);
  const [probeProvider, setProbeProvider] = useState<Provider | null>(null);
  const visible = providers.filter(provider => `${provider.name} ${provider.baseUrl}`.toLowerCase().includes(query.toLowerCase()));
  return <>
    <div className="page-title"><div><div className="eyebrow">PROVIDERS</div><h1>来源管理 <span>{providers.length}</span></h1></div>
      <button className="button primary" onClick={() => setEditing('new')}><Plus size={17} />添加来源</button></div>
    <div className="toolbar"><div className="search"><Search size={17} /><input aria-label="搜索来源" placeholder="搜索名称或地址" value={query} onChange={event => setQuery(event.target.value)} /></div><span className="muted">Base URL + API Key</span></div>
    {visible.length === 0 ? <Empty icon={Server} title={providers.length ? '没有匹配的来源' : '暂无 Provider'} action={!providers.length && <button className="button" onClick={() => setEditing('new')}><Plus size={16} />添加来源</button>} /> :
      <div className="table-wrap"><table><thead><tr><th>来源</th><th>状态</th><th>今日请求 / 限额</th><th>今日占额 / Token 限额</th><th>并发</th><th className="right">操作</th></tr></thead>
        <tbody>{visible.map(provider => {
          const usage = statistics.providers.find(row => row.providerId === provider.id);
          const state = runtime.find(row => row.providerId === provider.id);
          const health = state?.health ?? 'UNKNOWN';
          const charged = usage?.chargedTokens ?? 0;
          return <tr key={provider.id}><td><strong>{provider.name}</strong><small className="mono url">{provider.baseUrl}</small></td>
            <td><div><Badge tone={provider.enabled ? 'green' : ''}>{provider.enabled ? '已启用' : '已停用'}</Badge>
              <Badge tone={health === 'READY' ? 'green' : health === 'UNKNOWN' ? '' : 'amber'}>{healthLabels[health] ?? health}</Badge>
              {state?.cooldownUntil && <IconButton icon={RotateCcw} label={`清除 ${provider.name} 冷却`} onClick={() => void api.clearCooldown(provider.id).then(refresh)} />}</div></td>
            <td className="numeric">{number(usage?.requests ?? 0)} <span className="muted">/ {provider.dailyRequestLimit ? number(provider.dailyRequestLimit) : '不限'}</span></td>
            <td><div className="numeric">{number(charged)} <span className="muted">/ {provider.dailyTokenLimit ? number(provider.dailyTokenLimit) : '不限'}</span></div>
              {provider.dailyTokenLimit > 0 && <progress aria-label={`${provider.name} Token 占额`} max={provider.dailyTokenLimit} value={charged} />}</td>
            <td className="numeric">{usage?.inFlight ?? 0}<span className="muted"> / {provider.maxConcurrency}</span></td>
            <td><div className="row-actions"><IconButton icon={Activity} label={`探测 ${provider.name}`} onClick={() => setProbeProvider(provider)} /><IconButton icon={Settings2} label={`配置 ${provider.name} 额度`} onClick={() => setQuotaProvider(provider)} /><IconButton icon={Pencil} label={`编辑 ${provider.name}`} onClick={() => setEditing(provider)} /><IconButton icon={Trash2} label={`删除 ${provider.name}`} danger onClick={() => setDeleting(provider)} /></div></td></tr>;
        })}</tbody></table></div>}
    {editing && <ProviderEditor provider={editing === 'new' ? undefined : editing} api={api} refresh={refresh} close={() => setEditing(null)} />}
    {deleting && <ConfirmDelete name={deleting.name} close={() => setDeleting(null)} remove={async () => { await api.deleteProvider(deleting.id); await refresh(); }} />}
    {quotaProvider && <QuotaEditor provider={quotaProvider} api={api} close={() => setQuotaProvider(null)} />}
    {probeProvider && <ProbeDialog provider={probeProvider} api={api} close={() => setProbeProvider(null)} />}
  </>;
}

function ProviderEditor({ provider, api, refresh, close }: { provider?: Provider; api: ApiClient; refresh: () => Promise<void>; close: () => void }) {
  const [input, setInput] = useState<ProviderInput>({ name: provider?.name ?? '', baseUrl: provider?.baseUrl ?? '', apiKey: '', enabled: provider?.enabled ?? true,
    dailyRequestLimit: provider?.dailyRequestLimit ?? 0, dailyTokenLimit: provider?.dailyTokenLimit ?? 0,
    maxConcurrency: provider?.maxConcurrency ?? 4, tokenReservation: provider?.tokenReservation ?? 8192 });
  const update = <K extends keyof ProviderInput>(key: K, value: ProviderInput[K]) => setInput(current => ({ ...current, [key]: value }));
  return <Modal title={provider ? '编辑来源' : '添加来源'} close={close}><SaveForm close={close} submit={async () => { await api.saveProvider(input, provider?.id, provider?.version); await refresh(); }}>
    <label>来源名称<input required maxLength={80} autoFocus value={input.name} onChange={event => update('name', event.target.value)} placeholder="例如：主力 API" /></label>
    <label>Base URL<input required type="url" maxLength={2048} value={input.baseUrl} onChange={event => update('baseUrl', event.target.value)} placeholder="https://api.example.com/v1" /></label>
    <label>API Key{provider && <span className="muted">（留空保留原值）</span>}<input required={!provider} type="password" autoComplete="new-password" maxLength={8192} value={input.apiKey} onChange={event => update('apiKey', event.target.value)} /></label>
    <div className="form-grid"><label>每日请求上限（0 不限）<input required type="number" min={0} max={1000000000} value={input.dailyRequestLimit} onChange={event => update('dailyRequestLimit', Number(event.target.value))} /></label>
      <label>每日 Token 上限（0 不限）<input required type="number" min={0} max={1000000000000} value={input.dailyTokenLimit} onChange={event => update('dailyTokenLimit', Number(event.target.value))} /></label>
      <label>最大并发<input required type="number" min={1} max={128} value={input.maxConcurrency} onChange={event => update('maxConcurrency', Number(event.target.value))} /></label>
      <label>每次请求预留 Token<input required type="number" min={1} max={10000000} value={input.tokenReservation} onChange={event => update('tokenReservation', Number(event.target.value))} /></label></div>
    <label className="checkbox"><input type="checkbox" checked={input.enabled} onChange={event => update('enabled', event.target.checked)} />启用来源</label>
  </SaveForm></Modal>;
}

function QuotaEditor({ provider, api, close }: { provider: Provider; api: ApiClient; close: () => void }) {
  const [rules, setRules] = useState<QuotaRuleInput[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  useEffect(() => { void api.quotaRules(provider.id).then(values => setRules(values.map(({ id: _id, providerId: _providerId, ...rule }) => rule))).catch(failure => setError(errorMessage(failure))).finally(() => setLoading(false)); }, [api, provider.id]);
  function addRule() { setRules(current => [...current, { metric: 'REQUEST_COUNT', windowType: 'DAY', windowSize: 1, timezone: 'UTC', threshold: 1, action: 'SKIP_PROVIDER', enabled: true, priority: 0 }]); }
  function update(index: number, patch: Partial<QuotaRuleInput>) { setRules(current => current.map((rule, position) => position === index ? { ...rule, ...patch } : rule)); }
  return <Modal title={`${provider.name} 额度规则`} close={close}><SaveForm close={close} submit={async () => { await api.saveQuotaRules(provider.id, rules); }}>
    {error && <ErrorNotice message={error} />}{loading ? <p className="muted">正在读取规则</p> : rules.map((rule, index) => <div className="quota-rule" key={`${index}-${rule.metric}`}>
      <label>指标<select value={rule.metric} onChange={event => update(index, { metric: event.target.value as QuotaRuleInput['metric'] })}>{metrics.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>
      <label>窗口<select value={rule.windowType} onChange={event => update(index, { windowType: event.target.value as QuotaRuleInput['windowType'] })}>{windows.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>
      <label>窗口数量<input type="number" min={1} max={100000} value={rule.windowSize} onChange={event => update(index, { windowSize: Number(event.target.value) })} /></label>
      <label>时区<input value={rule.timezone} maxLength={64} onChange={event => update(index, { timezone: event.target.value })} /></label>
      <label>阈值<input type="number" min={1} max={1000000000000} value={rule.threshold} onChange={event => update(index, { threshold: Number(event.target.value) })} /></label>
      <label>达到阈值<select value={rule.action} onChange={event => update(index, { action: event.target.value as QuotaRuleInput['action'] })}>{actions.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>
      <label>优先级<input type="number" min={0} max={100000} value={rule.priority} onChange={event => update(index, { priority: Number(event.target.value) })} /></label>
      <label className="checkbox"><input type="checkbox" checked={rule.enabled} onChange={event => update(index, { enabled: event.target.checked })} />启用</label>
      <IconButton icon={X} label="删除规则" onClick={() => setRules(current => current.filter((_, position) => position !== index))} />
    </div>)}
    {!loading && <button type="button" className="button" onClick={addRule}><Plus size={16} />添加规则</button>}
  </SaveForm></Modal>;
}

function ProbeDialog({ provider, api, close }: { provider: Provider; api: ApiClient; close: () => void }) {
  const [model, setModel] = useState('');
  const [result, setResult] = useState<ProviderProbeResult | null>(null);
  const [capabilities, setCapabilities] = useState<ProviderCapability[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    void api.capabilities(provider.id, controller.signal).then(setCapabilities).catch(failure => {
      if (!(failure instanceof DOMException && failure.name === 'AbortError')) setError(errorMessage(failure));
    });
    return () => controller.abort();
  }, [api, provider.id]);
  async function submit(event: React.FormEvent) {
    event.preventDefault(); setBusy(true); setError(''); setResult(null);
    try {
      const value = await api.probeProvider(provider.id, model);
      setResult(value);
      setCapabilities(await api.capabilities(provider.id));
    } catch (failure) { setError(errorMessage(failure)); } finally { setBusy(false); }
  }
  return <Modal title={`探测 ${provider.name}`} close={close}><form onSubmit={submit}><fieldset disabled={busy} className="form-body"><label>模型 ID<input required maxLength={200} value={model} onChange={event => setModel(event.target.value)} placeholder="上游支持的模型 ID" /></label>{error && <ErrorNotice message={error} />}{result && <div className="probe-capability"><Badge tone={result.supportsResponses ? 'green' : 'amber'}>Responses {result.supportsResponses ? '支持' : '不支持'}</Badge><Badge tone={result.supportsSse ? 'green' : 'amber'}>SSE {result.supportsSse ? '支持' : '不支持'}</Badge><Badge>{result.usageSupport === 'REPORTED' ? 'Usage 已返回' : 'Usage 未确认'}</Badge>{result.error && <p className="muted">{result.error}</p>}</div>}{capabilities.length > 0 && <div className="capability-table"><h3>已记录能力</h3><div className="table-wrap"><table><thead><tr><th>模型</th><th>Responses</th><th>SSE</th><th>Usage</th><th>最后探测</th></tr></thead><tbody>{capabilities.map(capability => <tr key={capability.model}><td><code>{capability.model}</code></td><td>{capability.supportsResponses ? '支持' : '不支持'}</td><td>{capability.supportsSse ? '支持' : '不支持'}</td><td>{capability.usageSupport === 'REPORTED' ? '已返回' : '未确认'}</td><td>{new Date(capability.lastProbeAt).toLocaleString('zh-CN', { hour12: false })}</td></tr>)}</tbody></table></div></div>}</fieldset><footer><button type="button" className="button" disabled={busy} onClick={close}>关闭</button><button className="button primary" disabled={busy}>{busy ? '探测中' : '开始探测'}<Activity size={16} /></button></footer></form></Modal>;
}
