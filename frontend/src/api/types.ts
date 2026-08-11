export type WithdrawalStatus =
  | 'EVALUATING_RISK'
  | 'PENDING_AUTHORIZATION'
  | 'AUTHORIZED'
  | 'REJECTED'
  | 'PROCESSING_TRANSFER'
  | 'EXECUTED'
  | 'FINAL_ERROR'
  | 'RETRYABLE_ERROR'
  | 'MANUAL_REVIEW';

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH';

export type TransferStatus =
  | 'PENDING'
  | 'SUCCEEDED'
  | 'FAILED_INVALID_ACCOUNT'
  | 'FAILED_INTERNAL_ERROR'
  | 'AWAITING_RECONCILIATION';

export interface WithdrawalResponse {
  id: string;
  accountId: string;
  destinationCbu: string;
  amount: string;
  status: WithdrawalStatus;
  riskLevel: RiskLevel | null;
  createdAt: string;
  updatedAt: string;
  updatedBy: string | null;
}

export interface TransferInfo {
  status: TransferStatus;
  bankReference: string | null;
  attemptCount: number;
  lastError: string | null;
  resolvedAt: string | null;
}

export interface HistoryEntry {
  previousStatus: WithdrawalStatus | null;
  newStatus: WithdrawalStatus;
  actor: string;
  occurredAt: string;
}

export interface WithdrawalDetailResponse extends WithdrawalResponse {
  transfer: TransferInfo | null;
  history: HistoryEntry[];
}

export interface AccountResponse {
  id: string;
  accountNumber: string;
  holderName: string;
  balance: string;
  reservedBalance: string;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
}

export interface ErrorResponse {
  error: string;
  message: string;
}

export interface ConflictResponse extends ErrorResponse {
  currentStatus: WithdrawalStatus | null;
  updatedBy: string | null;
}

export interface WithdrawalFilters {
  status?: WithdrawalStatus | '';
  dateFrom?: string;
  dateTo?: string;
  search?: string;
  page?: number;
  size?: number;
}
