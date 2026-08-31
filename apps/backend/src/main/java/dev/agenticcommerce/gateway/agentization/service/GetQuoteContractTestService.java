package dev.agenticcommerce.gateway.agentization.service;

import dev.agenticcommerce.gateway.agentization.execution.ApprovedMerchantExecutor;
import dev.agenticcommerce.gateway.agentization.execution.MerchantExecutionException;
import dev.agenticcommerce.gateway.agentization.execution.MerchantExecutionMode;
import dev.agenticcommerce.gateway.agentization.model.AgentizationRun;
import dev.agenticcommerce.gateway.agentization.model.CapabilityContractTestRun;
import dev.agenticcommerce.gateway.agentization.model.CapabilityMappingProposal;
import dev.agenticcommerce.gateway.agentization.model.CanonicalCapability;
import dev.agenticcommerce.gateway.agentization.model.ContractTestOutcome;
import dev.agenticcommerce.gateway.agentization.model.GetQuoteTestCase;
import dev.agenticcommerce.gateway.agentization.model.MappingTransformation;
import dev.agenticcommerce.gateway.agentization.persistence.CapabilityContractTestRunRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class GetQuoteContractTestService {

    private final ApprovedMerchantExecutor executor;
    private final CapabilityContractTestRunRepository repository;
    private final CanonicalJsonService canonicalJsonService;
    private final ObjectMapper objectMapper;

    public GetQuoteContractTestService(
            ApprovedMerchantExecutor executor,
            CapabilityContractTestRunRepository repository,
            CanonicalJsonService canonicalJsonService,
            ObjectMapper objectMapper) {
        this.executor = executor;
        this.repository = repository;
        this.canonicalJsonService = canonicalJsonService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CapabilityContractTestRun runCanonical(
            AgentizationRun run, CapabilityMappingProposal mapping, String requestedTestCaseId) {
        GetQuoteTestCase testCase = GetQuoteTestCase.canonicalRupeesFixture();
        if (requestedTestCaseId != null && !testCase.testCaseId().equals(requestedTestCaseId)) {
            throw new AgentizationException(
                    "CONTRACT_TEST_CASE_NOT_ALLOWED", org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Only the registered deterministic contract test case can be selected");
        }
        return run(run, mapping, testCase);
    }

    public CapabilityContractTestRun run(
            AgentizationRun run, CapabilityMappingProposal mapping, GetQuoteTestCase testCase) {
        Instant startedAt = Instant.now();
        if (mapping.capability() != CanonicalCapability.GET_QUOTE
                || run.currentCapability() != CanonicalCapability.GET_QUOTE) {
            throw new AgentizationException(
                    "CONTRACT_TEST_CAPABILITY_UNSUPPORTED", org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Task 005 contract testing supports GET_QUOTE only");
        }
        TestEvaluation evaluation;
        String responseHash = null;
        try {
            byte[] request = "{\"productId\":\"demo-product\",\"quantity\":1}"
                    .getBytes(StandardCharsets.UTF_8);
            var response = executor.execute(
                    run.merchantId(), mapping, Map.of(), request, MerchantExecutionMode.CONTRACT_TEST);
            responseHash = canonicalJsonService.hashText(new String(response.body(), StandardCharsets.UTF_8));
            evaluation = evaluateResponse(mapping, testCase, response.statusCode(), response.contentType(), response.body());
        } catch (MerchantExecutionException exception) {
            ContractTestOutcome outcome = "MERCHANT_RESPONSE_TOO_LARGE".equals(exception.code())
                    ? ContractTestOutcome.FAIL : ContractTestOutcome.UNKNOWN;
            evaluation = failure(outcome, exception.code(), testCase, null, null, null);
        } catch (AgentizationException exception) {
            if (!exception.code().startsWith("ENDPOINT_")
                    && !exception.code().equals("MAPPING_ENDPOINT_NOT_APPROVED")) {
                throw exception;
            }
            evaluation = failure(
                    ContractTestOutcome.UNKNOWN, exception.code(), testCase, null, null, null);
        }
        String evidenceHash = canonicalJsonService.hash(evaluation.evidence());
        String failureSignature = evaluation.outcome() == ContractTestOutcome.PASS
                ? null
                : canonicalJsonService.hashText(mapping.mappingProposalId() + "|" + testCase.testCaseId()
                        + "|" + evaluation.failureCode() + "|" + evidenceHash);
        return repository.createCompleted(
                run.merchantId(), run.runId(), mapping, testCase, startedAt,
                evaluation.outcome(), evaluation.failureCode(), evaluation.evidence(),
                responseHash, evidenceHash, failureSignature);
    }

    private TestEvaluation evaluateResponse(
            CapabilityMappingProposal mapping,
            GetQuoteTestCase testCase,
            int statusCode,
            String contentType,
            byte[] body) {
        if (statusCode < 200 || statusCode >= 300) {
            return failure(ContractTestOutcome.FAIL, "HTTP_STATUS_UNEXPECTED", testCase, null, null, null);
        }
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            return failure(ContractTestOutcome.FAIL, "RESPONSE_CONTENT_TYPE_INVALID", testCase, null, null, null);
        }
        JsonNode response;
        try {
            response = objectMapper.readTree(body);
        } catch (RuntimeException exception) {
            return failure(ContractTestOutcome.FAIL, "RESPONSE_JSON_INVALID", testCase, null, null, null);
        }
        JsonNode amountNode = extract(response, mapping.responseBindings().path("amount").asText(null));
        if (amountNode == null || amountNode.isMissingNode() || amountNode.isNull()) {
            return failure(ContractTestOutcome.FAIL, "MISSING_AMOUNT", testCase, null, null, null);
        }
        if (!amountNode.isIntegralNumber() || !amountNode.canConvertToLong()) {
            return failure(ContractTestOutcome.FAIL, "INVALID_AMOUNT", testCase, null, null, null);
        }
        long rawAmount = amountNode.longValue();
        if (rawAmount < 0) {
            return failure(ContractTestOutcome.FAIL, "INVALID_AMOUNT", testCase, rawAmount, null, null);
        }
        long normalized;
        try {
            String transformationName = mapping.transformations().path("amount").asText("IDENTITY");
            MappingTransformation transformation = MappingTransformation.valueOf(transformationName);
            normalized = transformation == MappingTransformation.MONEY_RUPEES_TO_PAISE
                    ? Math.multiplyExact(rawAmount, 100L) : rawAmount;
        } catch (IllegalArgumentException | ArithmeticException exception) {
            return failure(ContractTestOutcome.FAIL, "INVALID_AMOUNT", testCase, rawAmount, null, null);
        }
        String currencyBinding = mapping.responseBindings().path("currency").asText(null);
        if (currencyBinding == null) {
            String field = mapping.currencyInterpretation().path("field").asText("currency");
            currencyBinding = field.startsWith("body.") ? field : "body." + field;
        }
        JsonNode currencyNode = extract(response, currencyBinding);
        String currency = currencyNode == null || !currencyNode.isTextual() ? null : currencyNode.asText();
        if (!testCase.expectedCurrency().equals(currency)) {
            return failure(ContractTestOutcome.FAIL, "WRONG_CURRENCY", testCase, rawAmount, normalized, currency);
        }
        JsonNode quoteId = extract(response, mapping.responseBindings().path("quoteId").asText(null));
        if (testCase.quoteIdentityRequired()
                && (quoteId == null || !quoteId.isTextual() || quoteId.asText().isBlank())) {
            return failure(ContractTestOutcome.FAIL, "MISSING_QUOTE_IDENTITY", testCase,
                    rawAmount, normalized, currency);
        }
        if (normalized != testCase.expectedAmountPaise()) {
            String code = rawAmount <= Long.MAX_VALUE / 100
                            && rawAmount * 100 == testCase.expectedAmountPaise()
                            && normalized == rawAmount
                    ? "MONEY_UNIT_MISMATCH" : "AMOUNT_MISMATCH";
            return failure(ContractTestOutcome.FAIL, code, testCase, rawAmount, normalized, currency);
        }
        ObjectNode evidence = baseEvidence(testCase);
        evidence.put("rawAmount", rawAmount);
        evidence.put("normalizedAmountPaise", normalized);
        evidence.put("currency", currency);
        if (quoteId != null && quoteId.isTextual()) {
            evidence.put("quoteId", bounded(quoteId.asText(), 256));
        }
        JsonNode lineItems = response.path("lineItems");
        if (lineItems.isArray()) {
            evidence.put("lineItemCount", Math.min(lineItems.size(), 100));
        }
        copyBoundedText(response, evidence, "expiresAt", 128);
        copyBoolean(response, evidence, "stockGuaranteed");
        copyBoolean(response, evidence, "priceGuaranteed");
        return new TestEvaluation(ContractTestOutcome.PASS, null, evidence);
    }

    private TestEvaluation failure(
            ContractTestOutcome outcome,
            String code,
            GetQuoteTestCase testCase,
            Long rawAmount,
            Long normalizedAmount,
            String currency) {
        ObjectNode evidence = baseEvidence(testCase);
        evidence.put("failureCode", code);
        if (rawAmount != null) evidence.put("rawAmount", rawAmount);
        if (normalizedAmount != null) evidence.put("normalizedAmountPaise", normalizedAmount);
        if (currency != null) evidence.put("currency", bounded(currency, 16));
        return new TestEvaluation(outcome, code, evidence);
    }

    private ObjectNode baseEvidence(GetQuoteTestCase testCase) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("testCaseId", testCase.testCaseId());
        evidence.put("testVersion", testCase.testVersion());
        evidence.put("expectedAmountPaise", testCase.expectedAmountPaise());
        evidence.put("expectedCurrency", testCase.expectedCurrency());
        evidence.put("sourceMoneyUnit", testCase.sourceMoneyUnit());
        evidence.put("quoteIdentityRequired", testCase.quoteIdentityRequired());
        return evidence;
    }

    private static JsonNode extract(JsonNode root, String binding) {
        if (binding == null || !binding.startsWith("body.")) return null;
        JsonNode current = root;
        for (String segment : binding.substring(5).split("\\.")) {
            current = current.path(segment);
        }
        return current;
    }

    private static void copyBoundedText(JsonNode source, ObjectNode target, String field, int max) {
        if (source.path(field).isTextual()) target.put(field, bounded(source.path(field).asText(), max));
    }

    private static void copyBoolean(JsonNode source, ObjectNode target, String field) {
        if (source.path(field).isBoolean()) target.put(field, source.path(field).booleanValue());
    }

    private static String bounded(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private record TestEvaluation(
            ContractTestOutcome outcome, String failureCode, ObjectNode evidence) {
    }
}
