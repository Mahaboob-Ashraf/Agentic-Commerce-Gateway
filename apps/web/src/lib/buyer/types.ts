export type ActorSession = {
  actorId: string;
  identityHandle: string;
  role: "BUYER" | "MERCHANT_ADMIN" | "PLATFORM_ADMIN" | "SYSTEM";
};

export type CommerceThread = {
  threadId: string;
  buyerActorId: string;
  title: string;
  state: string;
  stepCount: number;
  createdAt: string;
  updatedAt: string;
};

export type ThreadMessage = {
  messageId: string;
  normalizedText: string;
  inputSource: string;
  messageNumber: number;
  createdAt: string;
};

export type BuyerProfile = {
  buyerActorId: string;
  recipientName: string;
  phone: string;
  email: string;
  version: number;
};

export type BuyerAddress = {
  id: string;
  label: string;
  recipientName: string;
  phone: string;
  addressLine1: string;
  addressLine2: string | null;
  locality: string;
  city: string;
  state: string;
  postalCode: string;
  country: string;
  selected: boolean;
  active: boolean;
  version: number;
};

export type MerchantAccountLink = {
  id: string;
  merchantId: string;
  status: "LINKED" | "EXPIRED" | "REVOKED" | "FAILED";
  linkMethod: string;
  linkedAt: string;
  expiresAt: string | null;
};

export type OnboardingStatus = {
  profileComplete: boolean;
  addressSelected: boolean;
  activeMerchantLinks: number;
  ready: boolean;
  selectedAddressId: string | null;
  linkedMerchants: string[];
};

export type ProfileInput = Pick<BuyerProfile, "recipientName" | "phone" | "email">;

export type AddressInput = Omit<
  BuyerAddress,
  "id" | "country" | "selected" | "active" | "version"
>;

export type EvidenceOutcome = "PASS" | "FAIL" | "UNKNOWN";

export type ConstraintSummary = {
  key: string;
  result: EvidenceOutcome;
  safetyCritical: boolean;
  normalizedRequirement: unknown;
  evidenceReferences: string[];
};

export type AuthoritativeProductFact = {
  factId: string;
  type: string;
  value: unknown;
  authorityTier: string;
  source: string;
  resolutionState: string;
  observedAt: string;
  expiresAt: string | null;
  evidenceReference: string;
};

export type AuthoritativeProductLine = {
  productId: string;
  merchantSku: string;
  productName: string;
  brand: string | null;
  variant: string | null;
  sizeStorage: string | null;
  colour: string | null;
  category: string | null;
  quantity: number;
  unitAmountMinor: number | null;
  lineAmountMinor: number | null;
  facts: AuthoritativeProductFact[];
};

export type CommerceProgressStep = {
  code: string;
  label: string;
  evidenceReferences: string[];
};

export type CommerceRequestResult = {
  requestId: string;
  threadId: string;
  state: string;
  requestStatus: "RUNNING" | "COMPLETED" | "WAITING_FOR_USER" | "FAILED";
  clarificationRequired: boolean;
  clarificationQuestion: string | null;
  currentIntentVersion: number | null;
  goal: string | null;
  category: string | null;
  budgetAmountMinor: number | null;
  budgetCurrency: string | null;
  hardRequirements: Array<{ field: string; classification: string; startOffset: number; endOffset: number; ambiguity: string }>;
  softPreferences: string[];
  merchantId: string | null;
  merchantDisplayName: string | null;
  catalogueVersionId: string | null;
  catalogueVersion: string | null;
  cartId: string | null;
  cartHash: string | null;
  products: AuthoritativeProductLine[];
  quoteRecordId: string | null;
  merchantQuoteId: string | null;
  merchantQuoteVersion: string | null;
  subtotalMinor: number | null;
  taxMinor: number | null;
  deliveryMinor: number | null;
  feesMinor: number | null;
  authoritativeFinalAmountMinor: number | null;
  authoritativeCurrency: string | null;
  quoteExpiresAt: string | null;
  availabilityRefreshId: string | null;
  availabilityOutcome: EvidenceOutcome | null;
  availabilityReasonCode: string | null;
  serviceabilityEvidenceId: string | null;
  serviceabilityOutcome: EvidenceOutcome | null;
  serviceabilityReasonCode: string | null;
  constraintCertificateId: string | null;
  constraintCertificateHash: string | null;
  constraintOverall: EvidenceOutcome | null;
  constraints: ConstraintSummary[];
  transactionProposalId: string | null;
  transactionProposalHash: string | null;
  proposalExpiresAt: string | null;
  riskOutcome: "AUTO_EXECUTE" | "CLARIFY" | "EXPLICIT_CONFIRMATION" | "BLOCK" | null;
  riskReasonCodes: string[];
  explicitAuthorizationRequired: boolean;
  paymentReady: boolean;
  authorizationState: string;
  nextAction: string;
  progress: CommerceProgressStep[];
  evidenceReferences: string[];
  failureCode: string | null;
};

export type AuthorizationDecision = {
  authorizationId: string;
  proposalId: string;
  proposalHash: string;
  decision: "AUTHORIZED" | "DENIED";
  authorizationMethod: string;
  issuedAt: string;
  expiresAt: string;
  consumedAt: string | null;
  consumedByExecutionId: string | null;
};

export type TransactionExecution = {
  executionId: string;
  proposalId: string;
  proposalHash: string;
  status: "RESERVED" | "PAYMENT_PENDING" | "FAILED";
  providerOrderReference: string | null;
};

export type ExecutionGateResult = {
  decision: "ALLOW" | "DENY";
  reasonCode: string;
  execution: TransactionExecution | null;
  duplicateResolution: boolean;
  evidenceReferences: string[];
};

export type PaymentState = "NOT_STARTED" | "ORDER_CREATED" | "PAYMENT_PENDING" | "PAYMENT_UNCERTAIN" | "PAYMENT_CONFIRMED" | "PAYMENT_FAILED";
export type FulfillmentState = "PENDING" | "IN_PROGRESS" | "FULFILLED" | "RETRYABLE_FAILURE" | "TERMINAL_FAILURE" | "COMPENSATION_REQUIRED";

export type PaymentStateView = {
  executionId: string;
  proposalId: string;
  paymentState: PaymentState;
  reasonCode: string | null;
  providerOrderId: string | null;
  confirmedPaymentId: string | null;
  amountMinor: number;
  currency: string;
  fulfillmentState: FulfillmentState;
  merchantOrderId: string | null;
  reconciliationAttempts: number;
  reconciliationMaximumAttempts: number;
  updatedAt: string;
};

export type CheckoutInitialization = {
  publicKeyId: string;
  providerOrderId: string;
  amountMinor: number;
  currency: string;
  executionId: string;
  proposalId: string;
  merchantDisplayName: string;
};

export type CallbackSubmission = {
  razorpayPaymentId: string;
  razorpayOrderId: string;
  razorpaySignature: string;
};

export type CallbackResult = { accepted: boolean; financialConfirmation: boolean; nextAction: string };
export type ReconciliationResult = { state: PaymentStateView; attemptCount: number; maximumAttempts: number; reconciliationStatus: string };
export type FulfillmentView = {
  executionId: string;
  paymentState: PaymentState;
  fulfillmentState: FulfillmentState;
  merchantOperationId: string | null;
  merchantOrderId: string | null;
  attemptCount: number;
  lastErrorCode: string | null;
};
