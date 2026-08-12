import type { WithdrawalResponse } from '../api/types';
import { StatusBadge } from './StatusBadge';
import { useWithdrawalActions } from '../hooks/useWithdrawalActions';
import { actorLabel, RISK_LABELS } from '../labels';

interface Props {
  withdrawals: WithdrawalResponse[];
  operatorId: string;
  onSelect: (id: string) => void;
}

function formatAmount(amount: number): string {
  return amount.toLocaleString('es-AR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
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
            {/* Explicit widths + table-layout: fixed (styles.css) so a status badge or actor
                name changing length doesn't reflow every column — most visible with few rows
                on screen, where one row's content is most of the sizing signal. Estado is sized
                for its longest real label, "Procesando transferencia". */}
            <th style={{ width: 90 }}>ID</th>
            <th style={{ width: 170 }}>CBU destino</th>
            <th style={{ width: 110 }}>Monto</th>
            <th style={{ width: 190 }}>Estado</th>
            <th style={{ width: 80 }}>Riesgo</th>
            <th style={{ width: 120 }}>Creado</th>
            <th style={{ width: 150 }}>Actualizado por</th>
            <th style={{ width: 160 }}>Acciones</th>
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
              <td>{w.riskLevel ? RISK_LABELS[w.riskLevel] : '—'}</td>
              <td>{formatDate(w.createdAt)}</td>
              <td>{w.updatedBy ? actorLabel(w.updatedBy) : '—'}</td>
              <td>
                {/* The flex layout lives on this inner div, not on the <td> itself: a <td>
                    with display:flex stops behaving like a table cell for row-height purposes
                    (it no longer stretches to match its row's height, it sizes to its own
                    content instead), which is exactly what made this cell sit shorter than its
                    row and look "misaligned" even though every individual measurement inside
                    it was internally consistent. Keeping the <td> a plain table cell lets it
                    stretch normally; the div only handles the horizontal gap between buttons. */}
                <div className="actions-cell">
                  {w.status === 'PENDING_AUTHORIZATION' && (
                    <>
                      <button
                        className="btn btn-small btn-primary"
                        disabled={noOperator || authorize.isPending}
                        title={noOperator ? 'Ingresá tu ID de operador primero' : undefined}
                        onClick={(e) => run(authorize, w.id, e)}
                      >
                        Autorizar
                      </button>
                      <button
                        className="btn btn-small btn-danger"
                        disabled={noOperator || reject.isPending}
                        title={noOperator ? 'Ingresá tu ID de operador primero' : undefined}
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
                      title={noOperator ? 'Ingresá tu ID de operador primero' : undefined}
                      onClick={(e) => run(retry, w.id, e)}
                    >
                      Reintentar
                    </button>
                  )}
                  {w.status === 'MANUAL_REVIEW' && (
                    <button
                      className="btn btn-small btn-danger"
                      disabled={noOperator || resolveManualReview.isPending}
                      title={noOperator ? 'Ingresá tu ID de operador primero' : 'Resolver revisión manual'}
                      onClick={(e) => run(resolveManualReview, w.id, e)}
                    >
                      Resolver
                    </button>
                  )}
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
