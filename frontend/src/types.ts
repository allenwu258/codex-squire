export interface Provider {
  id: string;
  name: string;
  baseUrl: string;
  enabled: boolean;
  dailyRequestLimit: number;
  dailyTokenLimit: number;
  maxConcurrency: number;
  tokenReservation: number;
  createdAt: string;
}

export type ProviderInput = Omit<Provider, 'id' | 'createdAt'> & { apiKey: string };
export interface Member { providerId: string; weight: number }
export interface Scheme {
  id: string;
  name: string;
  strategy: 'ORDERED' | 'BALANCED';
  active: boolean;
  members: Member[];
}
export type SchemeInput = Omit<Scheme, 'id' | 'active'>;

export interface ProviderUsage {
  providerId: string;
  requests: number;
  inputTokens: number;
  outputTokens: number;
  cachedTokens: number;
  chargedTokens: number;
  inFlight: number;
  uncertainRequests: number;
}
export interface Statistics {
  day: string;
  requests: number;
  successes: number;
  failures: number;
  inFlight: number;
  inputTokens: number;
  outputTokens: number;
  cachedTokens: number;
  chargedTokens: number;
  uncertainRequests: number;
  providers: ProviderUsage[];
}
export interface Attempt {
  id: string;
  requestId: string;
  providerId: string;
  providerName: string;
  model: string;
  startedAt: string;
  endedAt: string | null;
  status: string;
  httpStatus: number | null;
  inputTokens: number;
  outputTokens: number;
  cachedTokens: number;
  chargedTokens: number;
  usageState: string;
  latencyMs: number;
}
export interface Connection {
  baseUrl: string;
  gatewayToken: string;
  dataDirectory: string;
  quotaTimeZone: string;
}
