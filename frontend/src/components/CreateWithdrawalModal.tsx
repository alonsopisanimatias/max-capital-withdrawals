import { useState } from 'react';
import { useAccounts } from '../hooks/useAccounts';
import { useCreateWithdrawal } from '../hooks/useWithdrawalActions';

interface Props {
  onClose: () => void;
}

const CBU_PATTERN = /^[0-9]{22}$/;

export function CreateWithdrawalModal({ onClose }: Props) {
  const { data: accounts, isLoading: accountsLoading } = useAccounts();
  const createWithdrawal = useCreateWithdrawal();

  const [accountId, setAccountId] = useState('');
  const [destinationCbu, setDestinationCbu] = useState('');
  const [amount, setAmount] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});

  function validate(): boolean {
    const next: Record<string, string> = {};
    if (!accountId) next.accountId = 'Elegí una cuenta.';
    if (!CBU_PATTERN.test(destinationCbu)) next.destinationCbu = 'El CBU debe tener exactamente 22 dígitos.';
    const parsed = Number(amount);
    if (!amount || Number.isNaN(parsed) || parsed <= 0) next.amount = 'Ingresá un monto mayor a cero.';
    else if (!/^\d+(\.\d{1,2})?$/.test(amount)) next.amount = 'Máximo 2 decimales.';
    setErrors(next);
    return Object.keys(next).length === 0;
  }

  function submit() {
    if (!validate()) return;
    createWithdrawal.mutate(
      { accountId, destinationCbu, amount },
      {
        onSuccess: () => onClose(),
      },
    );
  }

  return (
    <div className="overlay center" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h2 style={{ margin: 0, fontSize: 16 }}>Nuevo retiro</h2>
          <button className="btn btn-small" onClick={onClose}>
            ✕
          </button>
        </div>

        <div className="form-field">
          <label>Cuenta</label>
          <select value={accountId} onChange={(e) => setAccountId(e.target.value)} disabled={accountsLoading}>
            <option value="">{accountsLoading ? 'Cargando cuentas...' : 'Elegí una cuenta'}</option>
            {accounts?.map((account) => (
              <option key={account.id} value={account.id}>
                {account.accountNumber} — {account.holderName} (disp. $
                {(Number(account.balance) - Number(account.reservedBalance)).toLocaleString('es-AR', { minimumFractionDigits: 2 })})
              </option>
            ))}
          </select>
          {errors.accountId && <span className="field-error">{errors.accountId}</span>}
        </div>

        <div className="form-field">
          <label>CBU destino (22 dígitos)</label>
          <input
            type="text"
            inputMode="numeric"
            maxLength={22}
            value={destinationCbu}
            onChange={(e) => setDestinationCbu(e.target.value.replace(/\D/g, ''))}
            placeholder="0000000000000000000000"
          />
          {errors.destinationCbu && <span className="field-error">{errors.destinationCbu}</span>}
        </div>

        <div className="form-field">
          <label>Monto</label>
          <input type="text" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="0.00" />
          {errors.amount && <span className="field-error">{errors.amount}</span>}
        </div>

        <div className="modal-actions">
          <button className="btn" onClick={onClose}>
            Cancelar
          </button>
          <button className="btn btn-primary" disabled={createWithdrawal.isPending} onClick={submit}>
            Crear retiro
          </button>
        </div>
      </div>
    </div>
  );
}
