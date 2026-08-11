import { useState } from 'react';
import type { WithdrawalFilters } from './api/types';
import { useWithdrawals } from './hooks/useWithdrawals';
import { useOperatorId } from './hooks/useOperatorId';
import { WithdrawalFiltersBar } from './components/WithdrawalFilters';
import { WithdrawalsTable } from './components/WithdrawalsTable';
import { Pagination } from './components/Pagination';
import { WithdrawalDetail } from './components/WithdrawalDetail';
import { CreateWithdrawalModal } from './components/CreateWithdrawalModal';
import { Toasts } from './components/Toasts';

export function App() {
  const [filters, setFilters] = useState<WithdrawalFilters>({ page: 0, size: 20 });
  const [operatorId, setOperatorId] = useOperatorId();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);

  const { data, isLoading, isError, isFetching } = useWithdrawals(filters);

  return (
    <>
      <div className="topbar">
        <h1>Withdrawals Backoffice</h1>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <label className="operator-field">
            Operator ID
            <input
              type="text"
              value={operatorId}
              onChange={(e) => setOperatorId(e.target.value)}
              placeholder="tu-usuario"
            />
          </label>
          <button className="btn btn-primary" onClick={() => setShowCreate(true)}>
            + Nuevo retiro
          </button>
        </div>
      </div>

      <WithdrawalFiltersBar filters={filters} onChange={setFilters} />

      {isLoading && <div className="panel loading-state">Cargando retiros...</div>}
      {isError && <div className="panel error-state">No se pudieron cargar los retiros. Verificá que el backend esté corriendo.</div>}
      {!isLoading && !isError && data && data.content.length === 0 && (
        <div className="panel empty-state">No hay retiros que coincidan con los filtros actuales.</div>
      )}
      {!isLoading && !isError && data && data.content.length > 0 && (
        <>
          <WithdrawalsTable withdrawals={data.content} operatorId={operatorId} onSelect={setSelectedId} />
          <div className="panel" style={{ marginTop: 8 }}>
            <Pagination
              page={data.number}
              totalPages={data.totalPages}
              totalElements={data.totalElements}
              onPageChange={(page) => setFilters((f) => ({ ...f, page }))}
            />
          </div>
        </>
      )}
      {isFetching && !isLoading && <p style={{ color: 'var(--text-dim)', fontSize: 12 }}>Actualizando...</p>}

      {selectedId && <WithdrawalDetail id={selectedId} operatorId={operatorId} onClose={() => setSelectedId(null)} />}
      {showCreate && <CreateWithdrawalModal onClose={() => setShowCreate(false)} />}

      <Toasts />
    </>
  );
}
