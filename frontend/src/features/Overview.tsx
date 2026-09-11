import { Activity, ArrowDownLeft, ArrowUpRight, Database, GitBranch, Server, Zap } from 'lucide-react';
import { Badge, Empty, number, shortNumber } from '../components';
import type { Provider, Scheme, Statistics } from '../types';

export function Overview({ providers, schemes, statistics, navigate }: {
  providers: Provider[]; schemes: Scheme[]; statistics: Statistics; navigate: (page: string) => void;
}) {
  const active = schemes.find(scheme => scheme.active);
  const total = statistics.inputTokens + statistics.outputTokens;
  const chart = statistics.providers.filter(row => row.inputTokens + row.outputTokens > 0);
  const max = Math.max(1, ...chart.map(row => row.inputTokens + row.outputTokens));
  return <>
    <div className="page-title"><div><div className="eyebrow">WORKSPACE / OVERVIEW</div><h1>运行概览</h1></div><span className="date-label">{statistics.day} <Badge>UTC</Badge></span></div>
    <section className="metrics">
      <div><span><Activity size={17} />上游请求</span><strong>{number(statistics.requests)}</strong><small>{statistics.successes} 成功 · {statistics.failures} 未成功</small></div>
      <div><span><Zap size={17} />已报告 Token</span><strong>{shortNumber(total)}</strong><small>{number(total)} tokens</small></div>
      <div><span><Database size={17} />缓存输入</span><strong>{shortNumber(statistics.cachedTokens)}</strong><small>包含在输入 Token 中</small></div>
      <div><span><Activity size={17} />进行中</span><strong>{statistics.inFlight}<i className="live-dot" /></strong><small>{statistics.uncertainRequests} 次用量待确认</small></div>
    </section>
    <section className="active-route"><div><span className="section-kicker">当前路由</span><h2><GitBranch size={20} />{active?.name ?? '尚未启用方案'}</h2>
      <div className="route-summary">{active && <Badge tone="green">{active.strategy === 'ORDERED' ? '按序使用' : '加权轮询'}</Badge>}<span>{active?.members.length ?? 0} 个来源</span><span>{providers.filter(provider => provider.enabled).length} 个已启用</span></div></div>
      <button className="button" onClick={() => navigate('schemes')}>配置方案<ArrowUpRight size={16} /></button></section>
    <div className="overview-columns"><section><div className="section-title"><h2>Token 用量分布</h2><div className="legend"><span><i className="input-dot" />输入</span><span><i className="output-dot" />输出</span></div></div>
      {chart.length ? <div className="usage-chart">{chart.map(row => <div className="chart-row" key={row.providerId}><div><strong>{providers.find(provider => provider.id === row.providerId)?.name ?? '已删除来源'}</strong><span>{number(row.inputTokens + row.outputTokens)}</span></div>
        <div className="bar-track"><div className="bar-input" style={{ width: `${row.inputTokens / max * 100}%` }} /><div className="bar-output" style={{ width: `${row.outputTokens / max * 100}%` }} /></div></div>)}</div> : <Empty icon={Activity} title="今日暂无已报告用量" />}
      <div className="token-totals"><span><ArrowDownLeft size={17} />输入 <strong>{number(statistics.inputTokens)}</strong></span><span><ArrowUpRight size={17} />输出 <strong>{number(statistics.outputTokens)}</strong></span></div>
    </section><section><div className="section-title"><h2>Provider 状态</h2><button className="text-button" onClick={() => navigate('providers')}>全部来源<ArrowUpRight size={15} /></button></div>
      {!providers.length ? <Empty icon={Server} title="暂无来源" action={<button className="button" onClick={() => navigate('providers')}>添加来源</button>} /> : <ul className="provider-status-list">{providers.map(provider => <li key={provider.id}><span className={`status-dot ${provider.enabled ? 'on' : ''}`} /><strong>{provider.name}</strong><Badge tone={provider.enabled ? 'green' : ''}>{provider.enabled ? '已启用' : '已停用'}</Badge></li>)}</ul>}
    </section></div>
  </>;
}
