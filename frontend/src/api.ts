import type { Attempt, Connection, Provider, ProviderInput, Scheme, SchemeInput, Statistics } from './types';

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) { super(message); }
}

export class ApiClient {
  constructor(private readonly token: string) {}

  private async request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
    const response = await fetch(`/api${path}`, {
      method,
      headers: { Authorization: `Bearer ${this.token}`, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(15000),
    });
    if (!response.ok) {
      const error = await response.json().catch(() => null);
      throw new ApiError(response.status, error?.error?.message ?? `请求失败 (${response.status})`);
    }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }

  providers = () => this.request<Provider[]>('/providers');
  schemes = () => this.request<Scheme[]>('/schemes');
  statistics = () => this.request<Statistics>('/statistics');
  attempts = (offset = 0) => this.request<Attempt[]>(`/attempts?limit=30&offset=${offset}`);
  connection = () => this.request<Connection>('/connection');
  saveProvider = (input: ProviderInput, id?: string) => this.request<Provider>(`/providers${id ? `/${id}` : ''}`, id ? 'PUT' : 'POST', input);
  deleteProvider = (id: string) => this.request<void>(`/providers/${id}`, 'DELETE');
  saveScheme = (input: SchemeInput, id?: string) => this.request<Scheme>(`/schemes${id ? `/${id}` : ''}`, id ? 'PUT' : 'POST', input);
  deleteScheme = (id: string) => this.request<void>(`/schemes/${id}`, 'DELETE');
  activateScheme = (id: string) => this.request<Scheme>(`/schemes/${id}/activate`, 'POST');
}
