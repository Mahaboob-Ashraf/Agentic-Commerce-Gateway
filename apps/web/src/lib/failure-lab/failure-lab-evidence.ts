export type FailureLabReport = {
  schemaVersion: string;
  provenance: {
    generatedAtUtc: string;
    command: string;
    sampleSize: number;
  };
  summary: {
    status: string;
    verdict: string;
    executionReservations: number;
    providerCreateCalls: number;
    providerOrdersObserved: number;
    persistedProviderOrders: number;
    duplicateProviderOrders: number;
    recoveredByReconciliation: boolean;
    paymentConfirmed: boolean;
  };
  details: {
    testClass: string;
    testMethod: string;
    providerAdapter: string;
    providerBoundary: string;
    canCallRealRazorpay: boolean;
    deployedStateTouched: boolean;
    executionId: string;
    executionIdempotencyKey: string;
    stableReceipt: string;
    providerOrderIdRecovered: string;
    initialExecutionState: string;
    uncertainAttemptOutcome: string;
    uncertainPaymentState: string;
    reconciliationAttempts: number;
    reconciliationStatus: string;
    finalExecutionState: string;
    finalPaymentState: string;
    injectedFailure: {
      providerSideEffectCreatedOrder: boolean;
      responseDelivery: string;
      exceptionCategory: string;
      requestMayHaveReachedProvider: boolean;
    };
    productionLogic: string[];
    executionStateTransitions: string[];
    paymentStateTransitions: string[];
  };
  limitations: string[];
  whatThisDoesNotProve: string;
};

export type FailureLabEvidence = ReturnType<typeof buildFailureLabEvidence>;

export function buildFailureLabEvidence(report: FailureLabReport) {
  const { summary, details } = report;
  const valid =
    report.schemaVersion === "amana-failure-lab-evidence-v1" &&
    report.provenance.sampleSize === 1 &&
    summary.status === "PASS" &&
    summary.verdict === "NO_DUPLICATE_PROVIDER_ORDER" &&
    summary.executionReservations === 1 &&
    summary.providerCreateCalls === 1 &&
    summary.providerOrdersObserved === 1 &&
    summary.persistedProviderOrders === 1 &&
    summary.duplicateProviderOrders === 0 &&
    summary.recoveredByReconciliation &&
    !summary.paymentConfirmed &&
    details.providerBoundary === "INERT_TEST_ADAPTER" &&
    !details.canCallRealRazorpay &&
    !details.deployedStateTouched &&
    details.injectedFailure.providerSideEffectCreatedOrder &&
    details.injectedFailure.responseDelivery === "LOST" &&
    details.injectedFailure.exceptionCategory === "TIMEOUT" &&
    details.injectedFailure.requestMayHaveReachedProvider &&
    details.uncertainAttemptOutcome === "UNCERTAIN" &&
    details.uncertainPaymentState === "PAYMENT_UNCERTAIN" &&
    details.reconciliationAttempts === 1 &&
    details.finalExecutionState === "PAYMENT_PENDING" &&
    details.finalPaymentState === "PAYMENT_UNCERTAIN";

  if (!valid) {
    throw new Error("Failure Lab evidence is missing or does not prove the unknown-order recovery invariant");
  }

  return {
    ...summary,
    ...details,
    ...details.injectedFailure,
    generatedAtUtc: report.provenance.generatedAtUtc,
    reproductionCommand: report.provenance.command,
    limitations: report.limitations,
    whatThisDoesNotProve: report.whatThisDoesNotProve,
  };
}
