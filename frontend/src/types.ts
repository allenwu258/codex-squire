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
  version: number;
}

export type ProviderInput = Omit<Provider, 'id' | 'createdAt' | 'version'> & { apiKey: string };
export interface Member { providerId: string; weight: number }
export interface Scheme {
  id: string;
  name: string;
  strategy: 'ORDERED' | 'BALANCED';
  active: boolean;
  members: Member[];
  version: number;
}
export type SchemeInput = Omit<Scheme, 'id' | 'active' | 'version'>;

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
  attempts: number;
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
  upstreamOutcome: string;
  deliveryOutcome: string;
  latencyMs: number;
  attemptIndex: number;
  firstByteAt: string | null;
  requestBytes: number;
  responseBytes: number;
  errorCode: string | null;
  failureStage: string | null;
  skipReason: string | null;
  upstreamRequestId: string | null;
}
export interface ProviderRuntimeState {
  providerId: string;
  health: string;
  consecutiveFailures: number;
  lastSuccessAt: string | null;
  lastFailureAt: string | null;
  lastErrorCode: string | null;
  lastErrorMessage: string | null;
  cooldownUntil: string | null;
  averageLatencyMs: number;
  lastResponseStatus: number | null;
}
export interface Connection {
  baseUrl: string;
  gatewayToken: string;
  dataDirectory: string;
  quotaTimeZone: string;
}
