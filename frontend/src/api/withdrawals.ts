import { api } from './client';
import type { PageResponse, WithdrawalDetailResponse, WithdrawalFilters, WithdrawalResponse } from './types';

export interface CreateWithdrawalRequest {
  accountId: string;
  destinationCbu: string;
  amount: string;
}

// The backend binds dateFrom/dateTo as Instant (ISO.DATE_TIME) — a bare `<input type="date">`
// value like "2026-08-10" won't parse, so it needs widening to a full day. That day has to be
// the operator's LOCAL calendar day (e.g. Argentina, UTC-3), not UTC's — anchoring to
// "2026-08-10T00:00:00Z" would cut off the last 3 hours of the 10th and the first 3 of the 11th
// from the operator's point of view. `new Date(y, m, d, h, ...)` builds the Date in the
// browser's local timezone; `.toISOString()` then converts that instant to UTC correctly.
function localDayBoundaryToIsoInstant(dateStr: string, endOfDay: boolean): string {
  const [year, month, day] = dateStr.split('-').map(Number);
  return endOfDay
    ? new Date(year, month - 1, day, 23, 59, 59, 999).toISOString()
    : new Date(year, month - 1, day, 0, 0, 0, 0).toISOString();
}

function buildQuery(filters: WithdrawalFilters): string {
  const params = new URLSearchParams();
  if (filters.status) params.set('status', filters.status);
  if (filters.dateFrom) params.set('dateFrom', localDayBoundaryToIsoInstant(filters.dateFrom, false));
  if (filters.dateTo) params.set('dateTo', localDayBoundaryToIsoInstant(filters.dateTo, true));
  if (filters.search) params.set('search', filters.search);
  params.set('page', String(filters.page ?? 0));
  params.set('size', String(filters.size ?? 20));
  params.set('sort', 'createdAt,desc');
  return params.toString();
}

export const withdrawalsApi = {
  search: (filters: WithdrawalFilters) =>
    api.get<PageResponse<WithdrawalResponse>>(`/withdrawals?${buildQuery(filters)}`),

  getById: (id: string) => api.get<WithdrawalDetailResponse>(`/withdrawals/${id}`),

  create: (request: CreateWithdrawalRequest) => api.post<WithdrawalResponse>('/withdrawals', request),

  authorize: (id: string, operatorId: string) =>
    api.post<WithdrawalResponse>(`/withdrawals/${id}/authorize`, undefined, operatorId),

  reject: (id: string, operatorId: string) =>
    api.post<WithdrawalResponse>(`/withdrawals/${id}/reject`, undefined, operatorId),

  retry: (id: string, operatorId: string) =>
    api.post<WithdrawalResponse>(`/withdrawals/${id}/retry`, undefined, operatorId),

  resolveManualReview: (id: string, operatorId: string) =>
    api.post<WithdrawalResponse>(`/withdrawals/${id}/resolve-manual-review`, undefined, operatorId),
};
