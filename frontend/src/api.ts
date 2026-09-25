import type { Attempt, Connection, Provider, ProviderCapability, ProviderInput, ProviderProbeResult, ProviderRuntimeState, QuotaRule, QuotaRuleInput, Scheme, SchemeInput, Statistics } from './types';

export class ApiError extends Error {
  constructor(public readonly status: number, message: string, public readonly code?: string) { super(message); }
}

export class ApiClient {
  constructor(private readonly token: string) {}

  private async request<T>(path: string, method = 'GET', body?: unknown, signal?: AbortSignal, version?: number): Promise<T> {
    const response = await fetch(`/api${path}`, {
      method,
      headers: { Authorization: `Bearer ${this.token}`, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(version === undefined ? {} : { 'If-Match': `"${version}"` }) },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(15000)]) : AbortSignal.timeout(15000),
    });
    if (!response.ok) {
      const error = await response.json().catch(() => null);
      throw new ApiError(response.status, error?.error?.message ?? `请求失败 (${response.status})`, error?.error?.code);
    }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }

  providers = (signal?: AbortSignal) => this.request<Provider[]>('/providers', 'GET', undefined, signal);
  schemes = (signal?: AbortSignal) => this.request<Scheme[]>('/schemes', 'GET', undefined, signal);
  statistics = (signal?: AbortSignal) => this.request<Statistics>('/statistics', 'GET', undefined, signal);
  providerRuntime = (signal?: AbortSignal) => this.request<ProviderRuntimeState[]>('/provider-runtime', 'GET', undefined, signal);
  attempts = (offset = 0, signal?: AbortSignal) => this.request<Attempt[]>(`/attempts?limit=30&offset=${offset}`, 'GET', undefined, signal);
  connection = (signal?: AbortSignal) => this.request<Connection>('/connection', 'GET', undefined, signal);
  saveProvider = (input: ProviderInput, id?: string, version?: number) => this.request<Provider>(
    `/providers${id ? `/${id}` : ''}`, id ? 'PUT' : 'POST', input, undefined, version);
  deleteProvider = (id: string) => this.request<void>(`/providers/${id}`, 'DELETE');
  clearCooldown = (id: string) => this.request<void>(`/providers/${id}/runtime/clear-cooldown`, 'POST');
  quotaRules = (id: string, signal?: AbortSignal) => this.request<QuotaRule[]>(`/providers/${id}/quota-rules`, 'GET', undefined, signal);
  saveQuotaRules = (id: string, rules: QuotaRuleInput[]) => this.request<QuotaRule[]>(`/providers/${id}/quota-rules`, 'PUT', rules);
  capabilities = (id: string, signal?: AbortSignal) => this.request<ProviderCapability[]>(`/providers/${id}/capabilities`, 'GET', undefined, signal);
  probeProvider = (id: string, model: string) => this.request<ProviderProbeResult>(`/providers/${id}/probe?model=${encodeURIComponent(model)}`, 'POST');
  saveScheme = (input: SchemeInput, id?: string, version?: number) => this.request<Scheme>(
    `/schemes${id ? `/${id}` : ''}`, id ? 'PUT' : 'POST', input, undefined, version);
  deleteScheme = (id: string) => this.request<void>(`/schemes/${id}`, 'DELETE');
  activateScheme = (id: string) => this.request<Scheme>(`/schemes/${id}/activate`, 'POST');

  async gatewayResponse(token: string, body: unknown, signal?: AbortSignal): Promise<string> {
    const response = await fetch('/v1/responses', { method: 'POST', headers: {
      Authorization: `Bearer ${token}`, 'Content-Type': 'application/json',
    }, body: JSON.stringify(body), signal: signal ?? AbortSignal.timeout(120000) });
    const text = await response.text();
    if (!response.ok) {
      throw new ApiError(response.status, `HTTP ${response.status}: ${text.slice(0, 600)}`);
    }
    return text;
  }
}
