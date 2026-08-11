import type { WithdrawalStatus } from '../api/types';

const STATUS_META: Record<WithdrawalStatus, { label: string; className: string }> = {
  EVALUATING_RISK: { label: 'Evaluando riesgo', className: 'badge-neutral' },
  PENDING_AUTHORIZATION: { label: 'Pendiente autorización', className: 'badge-warning' },
  AUTHORIZED: { label: 'Autorizado', className: 'badge-info' },
  REJECTED: { label: 'Rechazado', className: 'badge-negative' },
  PROCESSING_TRANSFER: { label: 'Procesando transferencia', className: 'badge-info' },
  EXECUTED: { label: 'Ejecutado', className: 'badge-positive' },
  FINAL_ERROR: { label: 'Error final', className: 'badge-negative' },
  RETRYABLE_ERROR: { label: 'Reintentable', className: 'badge-warning' },
  MANUAL_REVIEW: { label: 'Revisión manual', className: 'badge-negative' },
};

export function StatusBadge({ status }: { status: WithdrawalStatus }) {
  const meta = STATUS_META[status];
  return <span className={`badge ${meta.className}`}>{meta.label}</span>;
}
