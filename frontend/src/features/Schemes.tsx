import { useState } from 'react';
import { ArrowDown, ArrowUp, Check, GitBranch, Pencil, Play, Plus, Trash2, X } from 'lucide-react';
import type { ApiClient } from '../api';
import { Badge, ConfirmDelete, Empty, ErrorNotice, errorMessage, IconButton, Modal, SaveForm } from '../components';
import type { Provider, Scheme, SchemeInput } from '../types';

export function Schemes({ api, providers, schemes, refresh }: {
  api: ApiClient; providers: Provider[]; schemes: Scheme[]; refresh: () => Promise<void>;
}) {
  const [editing, setEditing] = useState<Scheme | 'new' | null>(null);
  const [deleting, setDeleting] = useState<Scheme | null>(null);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  async function activate(scheme: Scheme) {
    setBusy(scheme.id); setError('');
    try { await api.activateScheme(scheme.id); await refresh(); } catch (failure) { setError(errorMessage(failure)); } finally { setBusy(''); }
  }
  return <>
    <div className="page-title"><div><div className="eyebrow">ROUTING</div><h1>配置方案 <span>{schemes.length}</span></h1></div>
      <button className="button primary" onClick={() => setEditing('new')}><Plus size={17} />新建方案</button></div>
    {error && <ErrorNotice message={error} />}
    {!schemes.length ? <Empty icon={GitBranch} title="暂无路由方案" action={<button className="button" onClick={() => setEditing('new')}><Plus size={16} />新建方案</button>} /> :
      <div className="scheme-list">{schemes.map(scheme => <article className={`scheme ${scheme.active ? 'active' : ''}`} key={scheme.id}>
        <div className="scheme-heading"><div className="scheme-name"><GitBranch size={20} /><h2>{scheme.name}</h2>{scheme.active && <Badge tone="green">当前生效</Badge>}</div>
          <div className="row-actions"><button className={`button ${scheme.active ? 'selected' : ''}`} disabled={scheme.active || !!busy || !scheme.members.length} onClick={() => void activate(scheme)}>{scheme.active ? <Check size={16} /> : <Play size={16} />}{busy === scheme.id ? '切换中' : scheme.active ? '已启用' : '启用方案'}</button>
            <IconButton icon={Pencil} label={`编辑 ${scheme.name}`} onClick={() => setEditing(scheme)} /><IconButton icon={Trash2} label={`删除 ${scheme.name}`} danger disabled={scheme.active} onClick={() => setDeleting(scheme)} /></div></div>
        <div className="scheme-meta"><Badge>{scheme.strategy === 'ORDERED' ? '按序使用' : '加权轮询'}</Badge><span>{scheme.members.length} 个来源</span></div>
        <ol className="route-members">{scheme.members.map((member, index) => {
          const provider = providers.find(item => item.id === member.providerId);
          return <li key={member.providerId}><span className="route-index">{String(index + 1).padStart(2, '0')}</span><strong>{provider?.name ?? '来源已移除'}</strong>{!provider?.enabled && <Badge>停用</Badge>}<span className="muted">{scheme.strategy === 'BALANCED' ? `权重 ${member.weight}` : index === 0 ? '首选' : '备用'}</span></li>;
        })}</ol>
        {!scheme.members.length && <p className="muted">尚未配置来源</p>}
      </article>)}</div>}
    {editing && <SchemeEditor scheme={editing === 'new' ? undefined : editing} api={api} providers={providers} refresh={refresh} close={() => setEditing(null)} />}
    {deleting && <ConfirmDelete name={deleting.name} close={() => setDeleting(null)} remove={async () => { await api.deleteScheme(deleting.id); await refresh(); }} />}
  </>;
}

function SchemeEditor({ scheme, api, providers, refresh, close }: {
  scheme?: Scheme; api: ApiClient; providers: Provider[]; refresh: () => Promise<void>; close: () => void;
}) {
  const [input, setInput] = useState<SchemeInput>({ name: scheme?.name ?? '', strategy: scheme?.strategy ?? 'ORDERED', members: scheme?.members.map(member => ({ ...member })) ?? [] });
  const available = providers.filter(provider => !input.members.some(member => member.providerId === provider.id));
  const [selected, setSelected] = useState('');
  function move(index: number, step: number) {
    setInput(current => {
      const members = [...current.members];
      [members[index], members[index + step]] = [members[index + step], members[index]];
      return { ...current, members };
    });
  }
  return <Modal title={scheme ? '编辑方案' : '新建方案'} close={close}><SaveForm close={close} submit={async () => { await api.saveScheme(input, scheme?.id); await refresh(); }}>
    <label>方案名称<input required autoFocus maxLength={80} value={input.name} onChange={event => setInput({ ...input, name: event.target.value })} placeholder="例如：日常开发" /></label>
    <label>路由策略</label><div className="segmented" role="group" aria-label="路由策略">
      <button type="button" aria-pressed={input.strategy === 'ORDERED'} onClick={() => setInput({ ...input, strategy: 'ORDERED' })}>按序使用</button>
      <button type="button" aria-pressed={input.strategy === 'BALANCED'} onClick={() => setInput({ ...input, strategy: 'BALANCED' })}>负载均衡 · 加权轮询</button></div>
    <label>方案来源</label>
    <div className="member-editor">{input.members.map((member, index) => <div className="member-edit-row" key={member.providerId}>
      <span className="route-index">{index + 1}</span><strong>{providers.find(provider => provider.id === member.providerId)?.name ?? member.providerId}</strong>
      {input.strategy === 'BALANCED' && <label className="weight-input">权重<input required aria-label={`来源 ${index + 1} 权重`} type="number" min={1} max={100} value={member.weight} onChange={event => setInput({ ...input, members: input.members.map((item, i) => i === index ? { ...item, weight: Number(event.target.value) } : item) })} /></label>}
      <div className="row-actions"><IconButton icon={ArrowUp} label={`上移来源 ${index + 1}`} disabled={index === 0} onClick={() => move(index, -1)} /><IconButton icon={ArrowDown} label={`下移来源 ${index + 1}`} disabled={index === input.members.length - 1} onClick={() => move(index, 1)} /><IconButton icon={X} label={`移除来源 ${index + 1}`} onClick={() => setInput({ ...input, members: input.members.filter((_, i) => index !== i) })} /></div>
    </div>)}</div>
    <div className="inline-input"><select aria-label="待添加来源" value={selected} onChange={event => setSelected(event.target.value)}><option value="">选择 Provider</option>{available.map(provider => <option value={provider.id} key={provider.id}>{provider.name}{!provider.enabled && '（停用）'}</option>)}</select>
      <button className="button" type="button" disabled={!selected} onClick={() => { setInput({ ...input, members: [...input.members, { providerId: selected, weight: 1 }] }); setSelected(''); }}><Plus size={16} />添加</button></div>
  </SaveForm></Modal>;
}
