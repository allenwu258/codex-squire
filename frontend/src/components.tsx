import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { AlertCircle, Check, Copy, LoaderCircle, Trash2, X, type LucideIcon } from 'lucide-react';

export const number = (value: number) => new Intl.NumberFormat('zh-CN').format(value);
export const shortNumber = (value: number) => new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 }).format(value);
const errorLabels: Record<string, string> = {
  DATABASE_BUSY: '本地存储正忙，请稍后重试',
  STORAGE_ERROR: '本地存储操作失败',
  CREDENTIAL_DECRYPTION_FAILED: 'Provider 密钥无法解密，请恢复主密钥或重新配置',
  UPSTREAM_REDIRECT: '上游重定向已拒绝',
  UPSTREAM_TIMEOUT: '上游响应超时',
  UPSTREAM_CONNECTION_ERROR: '无法连接上游 Provider',
  NO_AVAILABLE_PROVIDER: '当前没有可接收请求的 Provider',
  CONFLICT: '配置已被其他操作修改，请刷新后重试',
  CONFIG_VERSION_REQUIRED: '配置已更新，请重新加载后再保存',
  CONFIG_VERSION_CONFLICT: '配置已被其他标签页修改，请重新加载后再保存',
  PROBE_BUSY: '当前探测任务已满，请稍后重试',
  UNSUPPORTED_QUOTA_ACTION: '模型降级规则需要先配置模型映射',
  QUOTA_EXCEEDED: '额度规则已拒绝本次请求',
};
export const errorMessage = (error: unknown) => {
  if (error && typeof error === 'object' && 'code' in error) {
    const code = (error as { code?: string }).code;
    if (code && errorLabels[code]) return errorLabels[code];
  }
  return error instanceof Error ? error.message : '操作失败';
};

export function IconButton({ icon: Icon, label, onClick, disabled = false, danger = false }: {
  icon: LucideIcon; label: string; onClick: () => void; disabled?: boolean; danger?: boolean;
}) {
  return <button type="button" className={`icon-button ${danger ? 'danger' : ''}`} title={label} aria-label={label} disabled={disabled} onClick={onClick}><Icon size={17} /></button>;
}

export function Badge({ children, tone = '' }: { children: ReactNode; tone?: string }) {
  return <span className={`badge ${tone}`}>{children}</span>;
}

export function ErrorNotice({ message }: { message: string }) {
  return <div className="error-notice" role="alert"><AlertCircle size={17} /><span>{message}</span></div>;
}

export function Empty({ icon: Icon, title, action }: { icon: LucideIcon; title: string; action?: ReactNode }) {
  return <div className="empty"><Icon size={30} strokeWidth={1.5} /><p>{title}</p>{action}</div>;
}

export function Modal({ title, children, close }: { title: string; children: ReactNode; close: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const element = dialog.current;
    element?.showModal();
    return () => element?.close();
  }, []);
  return <dialog ref={dialog} onCancel={event => { event.preventDefault(); close(); }} className="modal" aria-label={title}>
    <header><h2>{title}</h2><IconButton icon={X} label="关闭" onClick={close} /></header>{children}
  </dialog>;
}

export function SaveForm({ children, submit, close, destructive = false, disabled = false }: {
  children: ReactNode; submit: () => Promise<void>; close: () => void; destructive?: boolean; disabled?: boolean;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (disabled) return;
    setBusy(true); setError('');
    try { await submit(); close(); } catch (failure) { setError(errorMessage(failure)); } finally { setBusy(false); }
  }
  return <form onSubmit={handleSubmit}>
    <fieldset disabled={busy} className="form-body">{children}{error && <ErrorNotice message={error} />}</fieldset>
    <footer><button type="button" className="button" disabled={busy} onClick={close}>取消</button>
      <button className={`button primary ${destructive ? 'destructive' : ''}`} disabled={busy || disabled}>{busy ? <LoaderCircle className="spin" size={16} /> : destructive ? <Trash2 size={16} /> : <Check size={16} />}{destructive ? '确认删除' : '保存'}</button></footer>
  </form>;
}

export function CopyButton({ value, label = '复制' }: { value: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  const [failed, setFailed] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout>>();
  useEffect(() => () => clearTimeout(timer.current), []);
  return <><IconButton icon={copied ? Check : Copy} label={copied ? '已复制' : label} onClick={() => {
    void navigator.clipboard.writeText(value).then(() => {
      setCopied(true); setFailed(false); clearTimeout(timer.current);
      timer.current = setTimeout(() => setCopied(false), 1800);
    }).catch(() => setFailed(true));
  }} />{failed && <span role="alert">复制失败</span>}</>;
}

export function ConfirmDelete({ name, remove, close }: { name: string; remove: () => Promise<void>; close: () => void }) {
  return <Modal title="确认删除" close={close}><SaveForm submit={remove} close={close} destructive><p>删除「{name}」？此操作无法撤销。</p></SaveForm></Modal>;
}
