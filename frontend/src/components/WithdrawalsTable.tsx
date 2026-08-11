import type { WithdrawalResponse } from '../api/types';
import { StatusBadge } from './StatusBadge';
import { useWithdrawalActions } from '../hooks/useWithdrawalActions';

interface Props {
  withdrawals: WithdrawalResponse[];
  operatorId: string;
  onSelect: (id: string) => void;
}

function formatAmount(amount: string): string {
  const n = Number(amount);
  return n.toLocaleString('es-AR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString('es-AR', { dateStyle: 'short', timeStyle: 'short' });
}

function shortId(id: string): string {
  return id.slice(0, 8);
}

export function WithdrawalsTable({ withdrawals, operatorId, onSelect }: Props) {
  const { authorize, reject, retry, resolveManualReview } = useWithdrawalActions();
  const noOperator = operatorId.trim().length === 0;

  function run(action: typeof authorize, id: string, e: React.MouseEvent) {
    e.stopPropagation();
    if (noOperator) return;
    action.mutate({ id, operatorId });
  }

  return (
    <div className="panel" style={{ overflowX: 'auto' }}>
      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>CBU destino</th>
            <th>Monto</th>
            <th>Estado</th>
            <th>Riesgo</th>
            <th>Creado</th>
            <th>Actualizado por</th>
            <th>Acciones</th>
          </tr>
        </thead>
        <tbody>
          {withdrawals.map((w) => (
            <tr key={w.id} onClick={() => onSelect(w.id)}>
              <td>{shortId(w.id)}</td>
              <td>{w.destinationCbu}</td>
              <td>${formatAmount(w.amount)}</td>
              <td>
                <StatusBadge status={w.status} />
              </td>
              <td>{w.riskLevel ?? '—'}</td>
              <td>{formatDate(w.createdAt)}</td>
              <td>{w.updatedBy ?? '—'}</td>
              <td className="actions-cell">
                {w.status === 'PENDING_AUTHORIZATION' && (
                  <>
                    <button
                      className="btn btn-small btn-primary"
                      disabled={noOperator || authorize.isPending}
                      title={noOperator ? 'Ingresá tu operator id primero' : undefined}
                      onClick={(e) => run(authorize, w.id, e)}
                    >
                      Autorizar
                    </button>
                    <button
                      className="btn btn-small btn-danger"
                      disabled={noOperator || reject.isPending}
                      title={noOperator ? 'Ingresá tu operator id primero' : undefined}
                      onClick={(e) => run(reject, w.id, e)}
                    >
                      Rechazar
                    </button>
                  </>
                )}
                {w.status === 'RETRYABLE_ERROR' && (
                  <button
                    className="btn btn-small btn-primary"
                    disabled={noOperator || retry.isPending}
                    title={noOperator ? 'Ingresá tu operator id primero' : undefined}
                    onClick={(e) => run(retry, w.id, e)}
                  >
                    Reintentar
                  </button>
                )}
                {w.status === 'MANUAL_REVIEW' && (
                  <button
                    className="btn btn-small btn-danger"
                    disabled={noOperator || resolveManualReview.isPending}
                    title={noOperator ? 'Ingresá tu operator id primero' : undefined}
                    onClick={(e) => run(resolveManualReview, w.id, e)}
                  >
                    Resolver
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
