import type { ConflictResponse, ErrorResponse } from './types';

/** Thrown for any non-2xx response; carries the parsed body so callers can branch on it
 *  (e.g. a 409 ConflictResponse vs a generic ErrorResponse) instead of re-parsing. */
export class ApiError extends Error {
  readonly status: number;
  readonly body: ErrorResponse | ConflictResponse | null;

  constructor(status: number, body: ErrorResponse | ConflictResponse | null) {
    super(body?.message ?? `Request failed with status ${status}`);
    this.status = status;
    this.body = body;
  }

  get isConflict(): boolean {
    return this.status === 409;
  }

  // Not every 409 is a ConflictResponse — INSUFFICIENT_BALANCE (from POST /withdrawals) is a
  // plain ErrorResponse with no currentStatus/updatedBy. Checking the shape instead of blindly
  // casting means a future 409 that isn't a real conflict body fails this check (falls back to
  // the generic error message) instead of silently rendering "undefined" in a toast.
  get conflict(): ConflictResponse | null {
    if (!this.isConflict || this.body === null || !('currentStatus' in this.body)) {
      return null;
    }
    return this.body as ConflictResponse;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...init?.headers,
    },
  });

  if (!response.ok) {
    let body: ErrorResponse | ConflictResponse | null = null;
    try {
      body = await response.json();
    } catch {
      // no JSON body (e.g. a proxy-level 502) — ApiError falls back to a generic message
    }
    throw new ApiError(response.status, body);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return response.json() as Promise<T>;
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown, operatorId?: string) =>
    request<T>(path, {
      method: 'POST',
      body: body !== undefined ? JSON.stringify(body) : undefined,
      headers: operatorId ? { 'X-Operator-Id': operatorId } : undefined,
    }),
};
