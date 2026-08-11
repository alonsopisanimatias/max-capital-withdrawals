import type { WithdrawalStatus } from '../api/types';
import { STATUS_LABELS, STATUS_TOOLTIPS } from '../labels';

const STATUS_CLASSNAMES: Record<WithdrawalStatus, string> = {
  EVALUATING_RISK: 'badge-neutral',
  PENDING_AUTHORIZATION: 'badge-warning',
  AUTHORIZED: 'badge-info',
  REJECTED: 'badge-negative',
  PROCESSING_TRANSFER: 'badge-info',
  EXECUTED: 'badge-positive',
  FINAL_ERROR: 'badge-negative',
  RETRYABLE_ERROR: 'badge-warning',
  MANUAL_REVIEW: 'badge-negative',
};

export function StatusBadge({ status }: { status: WithdrawalStatus }) {
  return (
    <span className={`badge ${STATUS_CLASSNAMES[status]}`} title={STATUS_TOOLTIPS[status]}>
      {STATUS_LABELS[status]}
    </span>
  );
}
