import { useMutation, useQueryClient } from '@tanstack/react-query';
import { withdrawalsApi, type CreateWithdrawalRequest } from '../api/withdrawals';
import { ApiError } from '../api/client';
import { useToasts } from './useToasts';
import { actorLabel, apiErrorMessage, STATUS_LABELS } from '../labels';

type Action = (id: string, operatorId: string) => Promise<unknown>;

/** Wraps every operator action (authorize/reject/retry/resolve) with the same error handling:
 *  a 409 means someone else already resolved it (C4) — show what actually happened instead of
 *  a bare error, and refresh so the operator sees current reality instead of stale data. */
function useAction(action: Action, successMessage: string) {
  const queryClient = useQueryClient();
  const { notify } = useToasts();

  return useMutation({
    mutationFn: ({ id, operatorId }: { id: string; operatorId: string }) => action(id, operatorId),
    onSuccess: (_data, { id }) => {
      notify('success', successMessage);
      queryClient.invalidateQueries({ queryKey: ['withdrawals'] });
      queryClient.invalidateQueries({ queryKey: ['withdrawal', id] });
      queryClient.invalidateQueries({ queryKey: ['accounts'] });
    },
    onError: (error, { id }) => {
      if (error instanceof ApiError && error.isConflict) {
        const conflict = error.conflict;
        notify(
          'error',
          conflict && conflict.currentStatus
            ? `No se pudo aplicar: el retiro ya está en ${STATUS_LABELS[conflict.currentStatus]} (actualizado por ${conflict.updatedBy ? actorLabel(conflict.updatedBy) : 'otro operador'}).`
            : 'El retiro fue modificado por otra persona mientras tanto.',
        );
        queryClient.invalidateQueries({ queryKey: ['withdrawals'] });
        queryClient.invalidateQueries({ queryKey: ['withdrawal', id] });
      } else if (error instanceof ApiError) {
        notify('error', apiErrorMessage(error.body?.error));
      } else {
        notify('error', 'Error inesperado. Reintentá en unos segundos.');
      }
    },
  });
}

export function useWithdrawalActions() {
  const authorize = useAction(withdrawalsApi.authorize, 'Retiro autorizado.');
  const reject = useAction(withdrawalsApi.reject, 'Retiro rechazado.');
  const retry = useAction(withdrawalsApi.retry, 'Reintento enviado.');
  const resolveManualReview = useAction(withdrawalsApi.resolveManualReview, 'Revisión manual resuelta.');

  return { authorize, reject, retry, resolveManualReview };
}

export function useCreateWithdrawal() {
  const queryClient = useQueryClient();
  const { notify } = useToasts();

  return useMutation({
    mutationFn: (request: CreateWithdrawalRequest) => withdrawalsApi.create(request),
    onSuccess: () => {
      notify('success', 'Retiro creado. Evaluando riesgo...');
      queryClient.invalidateQueries({ queryKey: ['withdrawals'] });
      queryClient.invalidateQueries({ queryKey: ['accounts'] });
    },
    onError: (error) => {
      notify('error', error instanceof ApiError ? apiErrorMessage(error.body?.error) : 'No se pudo crear el retiro.');
    },
  });
}
