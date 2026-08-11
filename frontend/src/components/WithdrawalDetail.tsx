import { useWithdrawal } from '../hooks/useWithdrawal';
import { useWithdrawalActions } from '../hooks/useWithdrawalActions';
import { StatusBadge } from './StatusBadge';

interface Props {
  id: string;
  operatorId: string;
  onClose: () => void;
}

function formatDate(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('es-AR', { dateStyle: 'short', timeStyle: 'medium' });
}

export function WithdrawalDetail({ id, operatorId, onClose }: Props) {
  const { data: withdrawal, isLoading, isError } = useWithdrawal(id);
  const { authorize, reject, retry, resolveManualReview } = useWithdrawalActions();
  const noOperator = operatorId.trim().length === 0;

  return (
    <div className="overlay" onClick={onClose}>
      <div className="drawer" onClick={(e) => e.stopPropagation()}>
        <div className="drawer-header">
          <h2 style={{ margin: 0, fontSize: 16 }}>Detalle del retiro</h2>
          <button className="btn btn-small" onClick={onClose}>
            Cerrar
          </button>
        </div>

        {isLoading && <div className="loading-state">Cargando...</div>}
        {isError && <div className="error-state">No se pudo cargar el retiro.</div>}

        {withdrawal && (
          <>
            <div className="field-row">
              <span className="label">ID</span>
              <span className="value">{withdrawal.id}</span>
            </div>
            <div className="field-row">
              <span className="label">Cuenta</span>
              <span className="value">{withdrawal.accountId}</span>
            </div>
            <div className="field-row">
              <span className="label">CBU destino</span>
              <span className="value">{withdrawal.destinationCbu}</span>
            </div>
            <div className="field-row">
              <span className="label">Monto</span>
              <span className="value">${Number(withdrawal.amount).toLocaleString('es-AR', { minimumFractionDigits: 2 })}</span>
            </div>
            <div className="field-row">
              <span className="label">Estado</span>
              <span className="value">
                <StatusBadge status={withdrawal.status} />
              </span>
            </div>
            <div className="field-row">
              <span className="label">Riesgo</span>
              <span className="value">{withdrawal.riskLevel ?? '—'}</span>
            </div>
            <div className="field-row">
              <span className="label">Creado</span>
              <span className="value">{formatDate(withdrawal.createdAt)}</span>
            </div>
            <div className="field-row">
              <span className="label">Actualizado</span>
              <span className="value">
                {formatDate(withdrawal.updatedAt)} ({withdrawal.updatedBy ?? '—'})
              </span>
            </div>

            {withdrawal.transfer && (
              <>
                <div className="section-title">Transferencia</div>
                <div className="field-row">
                  <span className="label">Estado</span>
                  <span className="value">{withdrawal.transfer.status}</span>
                </div>
                <div className="field-row">
                  <span className="label">Intentos</span>
                  <span className="value">{withdrawal.transfer.attemptCount}</span>
                </div>
                <div className="field-row">
                  <span className="label">Referencia bancaria</span>
                  <span className="value">{withdrawal.transfer.bankReference ?? '—'}</span>
                </div>
                {withdrawal.transfer.lastError && (
                  <div className="field-row">
                    <span className="label">Último error</span>
                    <span className="value">{withdrawal.transfer.lastError}</span>
                  </div>
                )}
              </>
            )}

            <div className="section-title">Historial</div>
            {withdrawal.history.length === 0 && <div className="empty-state">Sin movimientos.</div>}
            {withdrawal.history.map((h, idx) => (
              <div className="history-item" key={idx}>
                <div>
                  {h.previousStatus ? `${h.previousStatus} → ${h.newStatus}` : `Creado en ${h.newStatus}`}
                  {' · '}
                  <strong>{h.actor}</strong>
                </div>
                <div className="when">{formatDate(h.occurredAt)}</div>
              </div>
            ))}

            <div className="modal-actions" style={{ justifyContent: 'flex-start', flexWrap: 'wrap' }}>
              {withdrawal.status === 'PENDING_AUTHORIZATION' && (
                <>
                  <button
                    className="btn btn-primary"
                    disabled={noOperator || authorize.isPending}
                    onClick={() => authorize.mutate({ id: withdrawal.id, operatorId })}
                  >
                    Autorizar
                  </button>
                  <button
                    className="btn btn-danger"
                    disabled={noOperator || reject.isPending}
                    onClick={() => reject.mutate({ id: withdrawal.id, operatorId })}
                  >
                    Rechazar
                  </button>
                </>
              )}
              {withdrawal.status === 'RETRYABLE_ERROR' && (
                <button
                  className="btn btn-primary"
                  disabled={noOperator || retry.isPending}
                  onClick={() => retry.mutate({ id: withdrawal.id, operatorId })}
                >
                  Reintentar
                </button>
              )}
              {withdrawal.status === 'MANUAL_REVIEW' && (
                <button
                  className="btn btn-danger"
                  disabled={noOperator || resolveManualReview.isPending}
                  onClick={() => resolveManualReview.mutate({ id: withdrawal.id, operatorId })}
                >
                  Resolver revisión manual
                </button>
              )}
            </div>
            {noOperator && (withdrawal.status === 'PENDING_AUTHORIZATION' || withdrawal.status === 'RETRYABLE_ERROR' || withdrawal.status === 'MANUAL_REVIEW') && (
              <p style={{ color: 'var(--text-dim)', fontSize: 12 }}>Ingresá tu operator id arriba para poder actuar sobre este retiro.</p>
            )}
          </>
        )}
      </div>
    </div>
  );
}
