import { useState } from 'react';
import { Pencil, Plus, Search, Server, Trash2 } from 'lucide-react';
import { Badge, ConfirmDelete, Empty, IconButton, Modal, number, SaveForm } from '../components';
import type { ApiClient } from '../api';
import type { Provider, ProviderInput, ProviderRuntimeState, Statistics } from '../types';

interface Props { api: ApiClient; providers: Provider[]; runtime: ProviderRuntimeState[]; statistics: Statistics; refresh: () => Promise<void> }

const healthLabels: Record<string, string> = { READY: '健康', RATE_LIMITED: '冷却中', UNHEALTHY: '异常', UNKNOWN: '未探测' };

export function Providers({ api, providers, runtime, statistics, refresh }: Props) {
  const [query, setQuery] = useState('');
  const [editing, setEditing] = useState<Provider | 'new' | null>(null);
  const [deleting, setDeleting] = useState<Provider | null>(null);
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
              <Badge tone={health === 'READY' ? 'green' : health === 'UNKNOWN' ? '' : 'amber'}>{healthLabels[health] ?? health}</Badge></div></td>
            <td className="numeric">{number(usage?.requests ?? 0)} <span className="muted">/ {provider.dailyRequestLimit ? number(provider.dailyRequestLimit) : '不限'}</span></td>
            <td><div className="numeric">{number(charged)} <span className="muted">/ {provider.dailyTokenLimit ? number(provider.dailyTokenLimit) : '不限'}</span></div>
              {provider.dailyTokenLimit > 0 && <progress aria-label={`${provider.name} Token 占额`} max={provider.dailyTokenLimit} value={charged} />}</td>
            <td className="numeric">{usage?.inFlight ?? 0}<span className="muted"> / {provider.maxConcurrency}</span></td>
            <td><div className="row-actions"><IconButton icon={Pencil} label={`编辑 ${provider.name}`} onClick={() => setEditing(provider)} /><IconButton icon={Trash2} label={`删除 ${provider.name}`} danger onClick={() => setDeleting(provider)} /></div></td></tr>;
        })}</tbody></table></div>}
    {editing && <ProviderEditor provider={editing === 'new' ? undefined : editing} api={api} refresh={refresh} close={() => setEditing(null)} />}
    {deleting && <ConfirmDelete name={deleting.name} close={() => setDeleting(null)} remove={async () => { await api.deleteProvider(deleting.id); await refresh(); }} />}
  </>;
}

function ProviderEditor({ provider, api, refresh, close }: { provider?: Provider; api: ApiClient; refresh: () => Promise<void>; close: () => void }) {
  const [input, setInput] = useState<ProviderInput>({ name: provider?.name ?? '', baseUrl: provider?.baseUrl ?? '', apiKey: '', enabled: provider?.enabled ?? true,
    dailyRequestLimit: provider?.dailyRequestLimit ?? 0, dailyTokenLimit: provider?.dailyTokenLimit ?? 0,
    maxConcurrency: provider?.maxConcurrency ?? 4, tokenReservation: provider?.tokenReservation ?? 8192 });
  const update = <K extends keyof ProviderInput>(key: K, value: ProviderInput[K]) => setInput(current => ({ ...current, [key]: value }));
  return <Modal title={provider ? '编辑来源' : '添加来源'} close={close}><SaveForm close={close} submit={async () => { await api.saveProvider(input, provider?.id); await refresh(); }}>
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
