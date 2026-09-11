import { useEffect, useState } from 'react';
import { ChevronLeft, ChevronRight, Download, ListFilter, Search, X } from 'lucide-react';
import type { ApiClient } from '../api';
import { Badge, CopyButton, Empty, ErrorNotice, errorMessage, IconButton, Modal, number } from '../components';
import type { Attempt } from '../types';

const labels: Record<string, string> = { SUCCEEDED: '已完成', IN_FLIGHT: '进行中', INCOMPLETE: '未完成', FAILED: '失败', UPSTREAM_ERROR: '上游错误', TRANSPORT_ERROR: '连接错误', CANCELLED: '已取消', INTERRUPTED: '异常中断', RATE_LIMITED: '上游限流', REDIRECT_REJECTED: '重定向拒绝' };
const usageLabels: Record<string, string> = { REPORTED: '上游已报告', RESERVED: '预留额度', NOT_APPLICABLE: '不计 Token' };

export function Requests({ api, revision }: { api: ApiClient; revision: number }) {
  const [rows, setRows] = useState<Attempt[]>([]);
  const [offset, setOffset] = useState(0);
  const [query, setQuery] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [detail, setDetail] = useState<Attempt | null>(null);
  useEffect(() => {
    let alive = true;
    setLoading(true);
    void api.attempts(offset).then(values => { if (alive) { setRows(values); setError(''); } }).catch(failure => { if (alive) setError(errorMessage(failure)); }).finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [api, offset, revision]);
  const visible = rows.filter(row => `${row.providerName} ${row.model} ${row.requestId}`.toLowerCase().includes(query.toLowerCase()));
  function download() {
    const url = URL.createObjectURL(new Blob([JSON.stringify(visible, null, 2)], { type: 'application/json' }));
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `squire-attempts-${offset}.json`; anchor.click(); URL.revokeObjectURL(url);
  }
  return <>
    <div className="page-title"><div><div className="eyebrow">ACTIVITY</div><h1>请求记录</h1></div><button className="button" disabled={!visible.length} onClick={download}><Download size={16} />导出本页</button></div>
    <div className="toolbar"><div className="search"><Search size={17} /><input aria-label="搜索本页请求" placeholder="搜索本页来源、模型或请求 ID" value={query} onChange={event => setQuery(event.target.value)} /></div><span className="muted">按上游尝试记录</span></div>
    {error && <ErrorNotice message={error} />}
    {visible.length === 0 ? <Empty icon={ListFilter} title={loading ? '正在读取记录' : '暂无请求记录'} /> : <div className="table-wrap"><table><thead><tr><th>时间 / 来源</th><th>模型</th><th>状态</th><th>输入 / 输出</th><th>用量口径</th><th className="right">耗时</th></tr></thead>
      <tbody>{visible.map(row => <tr key={row.id}><td><button className="table-link" onClick={() => setDetail(row)}>{new Date(row.startedAt).toLocaleString('zh-CN', { hour12: false })}</button><small>{row.providerName}</small></td><td><code>{row.model}</code></td>
        <td><Badge tone={row.status === 'SUCCEEDED' ? 'green' : row.status === 'IN_FLIGHT' ? 'blue' : 'amber'}>{labels[row.status] ?? row.status}</Badge></td>
        <td className="numeric">{row.usageState === 'REPORTED' ? `${number(row.inputTokens)} / ${number(row.outputTokens)}` : '—'}</td><td>{usageLabels[row.usageState] ?? row.usageState}</td><td className="right numeric">{row.endedAt ? `${(row.latencyMs / 1000).toFixed(2)} s` : '…'}</td></tr>)}</tbody></table></div>}
    <div className="pagination"><span>第 {offset / 30 + 1} 页 · {rows.length} 条</span><div className="row-actions"><IconButton icon={ChevronLeft} label="上一页" disabled={offset === 0 || loading} onClick={() => setOffset(offset - 30)} /><IconButton icon={ChevronRight} label="下一页" disabled={rows.length < 30 || loading} onClick={() => setOffset(offset + 30)} /></div></div>
    {detail && <Modal title="请求详情" close={() => setDetail(null)}><div className="form-body"><dl className="details">
      <dt>网关请求 ID</dt><dd className="mono">{detail.requestId}<CopyButton value={detail.requestId} /></dd><dt>尝试 ID</dt><dd className="mono">{detail.id}</dd><dt>Provider</dt><dd>{detail.providerName}</dd><dt>HTTP 状态</dt><dd>{detail.httpStatus ?? '未收到响应头'}</dd>
      <dt>状态</dt><dd>{labels[detail.status] ?? detail.status}</dd><dt>统计口径</dt><dd>{usageLabels[detail.usageState]}</dd><dt>缓存输入 Token</dt><dd>{number(detail.cachedTokens)}</dd><dt>本次额度占用</dt><dd>{number(detail.chargedTokens)}</dd>
    </dl></div><footer><button className="button" onClick={() => setDetail(null)}><X size={16} />关闭</button></footer></Modal>}
  </>;
}
