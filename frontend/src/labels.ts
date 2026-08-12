import type { RiskLevel, TransferStatus, WithdrawalStatus } from './api/types';

// Centralized so the same Spanish label is never spelled two different ways in two components —
// StatusBadge (the grid/detail badge) and WithdrawalFilters (the status dropdown) both need it.
export const STATUS_LABELS: Record<WithdrawalStatus, string> = {
  EVALUATING_RISK: 'Evaluando riesgo',
  PENDING_AUTHORIZATION: 'Pendiente autorización',
  AUTHORIZED: 'Autorizado',
  REJECTED: 'Rechazado',
  PROCESSING_TRANSFER: 'Procesando transferencia',
  EXECUTED: 'Ejecutado',
  FINAL_ERROR: 'Error final',
  RETRYABLE_ERROR: 'Reintentable',
  MANUAL_REVIEW: 'Revisión manual',
};

// Only for statuses whose one-word label doesn't carry the "why" on its own — MANUAL_REVIEW in
// particular reads as just another error state unless you know it means "the system genuinely
// doesn't know what happened and only a human, checking with the bank directly, can close it"
// (see DECISIONS.md section 8). Rendered as a title attribute (a tooltip), not inline text — the
// badge itself has to stay compact.
export const STATUS_TOOLTIPS: Partial<Record<WithdrawalStatus, string>> = {
  MANUAL_REVIEW:
    'El sistema no pudo confirmar con el banco qué pasó con la transferencia. Necesita que un operador lo confirme y lo cierre manualmente.',
};

export const RISK_LABELS: Record<RiskLevel, string> = {
  LOW: 'Bajo',
  MEDIUM: 'Medio',
  HIGH: 'Alto',
};

export const TRANSFER_STATUS_LABELS: Record<TransferStatus, string> = {
  PENDING: 'Pendiente',
  SUCCEEDED: 'Exitosa',
  FAILED_INVALID_ACCOUNT: 'Falló — cuenta inválida',
  FAILED_INTERNAL_ERROR: 'Falló — error interno del banco',
  AWAITING_RECONCILIATION: 'Esperando reconciliación',
};

// SYSTEM_* / CLIENT are constants the backend writes as the actor for automated transitions
// (see ACTOR_SYSTEM_TRANSFER etc. in TransferOutcomeService, and ACTOR_CLIENT in
// WithdrawalService) — translate those specifically, but leave anything else (a real operator
// id, typed by a human) exactly as it came from the API.
const SYSTEM_ACTOR_LABELS: Record<string, string> = {
  CLIENT: 'Cliente',
  SYSTEM_RISK: 'Sistema (riesgo)',
  SYSTEM_TRANSFER: 'Sistema (transferencia)',
  SYSTEM_RECONCILIATION: 'Sistema (reconciliación)',
};

export function actorLabel(actor: string): string {
  return SYSTEM_ACTOR_LABELS[actor] ?? actor;
}

// ErrorResponse.error is a stable code (GlobalExceptionHandler) meant for API consumers, not
// end users — its .message is free text in English and sometimes includes a raw internal id
// (e.g. "Insufficient available balance for account <uuid>"). Map the stable code to a Spanish
// message instead of ever showing that raw text in a toast; anything not in this map is a
// genuinely unexpected error, so it falls back to a generic message rather than leaking English.
const API_ERROR_MESSAGES: Record<string, string> = {
  INSUFFICIENT_BALANCE: 'Saldo insuficiente para este retiro.',
  NOT_FOUND: 'No se encontró lo que buscabas.',
  VALIDATION_ERROR: 'Los datos ingresados no son válidos.',
  INVALID_REQUEST: 'La solicitud no es válida.',
  INTERNAL_ERROR: 'Ocurrió un error inesperado. Intentá de nuevo en unos segundos.',
};

export function apiErrorMessage(errorCode: string | undefined): string {
  return API_ERROR_MESSAGES[errorCode ?? ''] ?? 'Ocurrió un error inesperado. Intentá de nuevo en unos segundos.';
}
