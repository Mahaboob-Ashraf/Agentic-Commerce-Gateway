[CmdletBinding()]
param(
    [ValidateSet('Prepare', 'Resume')]
    [string] $Phase = 'Prepare',
    [uri] $BaseUrl = 'http://localhost:8080',
    [uri] $MerchantBaseUrl,
    [string] $Identity = $env:DEMO_BUYER_IDENTITY,
    [System.Management.Automation.PSCredential] $Credential,
    [guid] $RequestId = [guid]::NewGuid(),
    [string] $StatePath = (Join-Path ([IO.Path]::GetTempPath()) 'agentic-commerce-razorpay-verification.json'),
    [string] $AuthorizationPhrase,
    [string] $RazorpayPaymentId,
    [string] $RazorpayOrderId,
    [string] $RazorpaySignature,
    [ValidateRange(1, 3)]
    [int] $ReconcilePollCount = 3,
    [ValidateRange(1, 120)]
    [int] $FulfillmentPollSeconds = 30,
    [switch] $NoOpen
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($null -eq $Credential) {
    if ([string]::IsNullOrWhiteSpace($Identity)) {
        $Identity = Read-Host 'Buyer identity'
    }
    $Credential = Get-Credential -UserName $Identity -Message 'Enter the buyer password'
}
if ($null -eq $Credential -or [string]::IsNullOrWhiteSpace($Credential.UserName)) {
    throw 'A buyer credential is required.'
}

$root = $BaseUrl.AbsoluteUri.TrimEnd('/')
$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()

function Get-SessionFingerprint {
    $cookie = $session.Cookies.GetCookies($BaseUrl) |
        Where-Object { $_.Name -eq 'ACG_SESSION' } |
        Select-Object -First 1
    if ($null -eq $cookie) { return 'none' }
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $digest = $sha256.ComputeHash([Text.Encoding]::UTF8.GetBytes($cookie.Value))
        return ([BitConverter]::ToString($digest) -replace '-', '').Substring(0, 12).ToLowerInvariant()
    }
    finally { $sha256.Dispose() }
}

function New-CsrfHeaders {
    param([Parameter(Mandatory)] $Csrf)
    if ([string]::IsNullOrWhiteSpace([string] $Csrf.headerName) -or
        [string]::IsNullOrWhiteSpace([string] $Csrf.token)) {
        throw 'CSRF response did not contain a usable header name and token.'
    }
    $headers = [System.Collections.Generic.Dictionary[string,string]]::new()
    $headers.Add([string] $Csrf.headerName, [string] $Csrf.token)
    return $headers
}

function Invoke-AcgJson {
    param(
        [Parameter(Mandatory)] [string] $Step,
        [Parameter(Mandatory)] [ValidateSet('GET', 'POST')] [string] $Method,
        [Parameter(Mandatory)] [string] $Uri,
        [System.Collections.IDictionary] $Headers,
        [string] $Body
    )
    try {
        Write-Verbose "$Step request method=$Method path=$(([uri] $Uri).AbsolutePath) session=$(Get-SessionFingerprint)"
        $arguments = @{ Method = $Method; Uri = $Uri; WebSession = $session; ErrorAction = 'Stop' }
        if ($null -ne $Headers) { $arguments.Headers = $Headers }
        if ($Method -eq 'POST') {
            $arguments.ContentType = 'application/json'
            $arguments.Body = $(if ($null -eq $Body) { '{}' } else { $Body })
        }
        $response = Invoke-RestMethod @arguments
        Write-Verbose "$Step accepted session=$(Get-SessionFingerprint)"
        return $response
    }
    catch {
        $failure = $_
        $status = if ($null -ne $failure.Exception.Response) { [int] $failure.Exception.Response.StatusCode } else { 'unavailable' }
        $classification = $null
        if ($null -ne $failure.ErrorDetails -and -not [string]::IsNullOrWhiteSpace($failure.ErrorDetails.Message)) {
            try { $classification = ($failure.ErrorDetails.Message | ConvertFrom-Json).error } catch { $classification = $null }
        }
        $reason = if ([string]::IsNullOrWhiteSpace([string] $classification)) { $failure.Exception.GetType().FullName } else { [string] $classification }
        throw "$Step failed (HTTP status: $status, reason: $reason)."
    }
}

function Get-Csrf {
    param([Parameter(Mandatory)] [string] $Step)
    $csrf = Invoke-AcgJson -Step $Step -Method GET -Uri "$root/api/auth/csrf"
    if ([string]::IsNullOrWhiteSpace([string] $csrf.token)) { throw "$Step returned no token." }
    return $csrf
}

function Invoke-AcgMutation {
    param([Parameter(Mandatory)] [string] $Step, [Parameter(Mandatory)] [string] $Path, [string] $Body = '{}')
    $csrf = Get-Csrf -Step "$Step CSRF acquisition"
    return Invoke-AcgJson -Step $Step -Method POST -Uri "$root$Path" -Headers (New-CsrfHeaders $csrf) -Body $Body
}

function Connect-BuyerSession {
    $anonymousCsrf = Get-Csrf -Step 'Anonymous CSRF acquisition'
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Credential.Password)
    $plainPassword = $null
    $loginBody = $null
    try {
        $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
        $loginBody = @{ identityHandle = $Credential.UserName; password = $plainPassword } | ConvertTo-Json -Compress
        $login = Invoke-AcgJson -Step 'Buyer login' -Method POST -Uri "$root/api/auth/login" `
            -Headers (New-CsrfHeaders $anonymousCsrf) -Body $loginBody
    }
    finally {
        $plainPassword = $null
        $loginBody = $null
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
    if ($login.role -ne 'BUYER') { throw "Authenticated actor does not have BUYER authority (actual role: $($login.role))." }
    $authenticatedSession = Get-SessionFingerprint
    if ($authenticatedSession -eq 'none') { throw 'Login succeeded without retaining an ACG_SESSION cookie.' }
    $null = Get-Csrf -Step 'Post-login CSRF acquisition'
    $me = Invoke-AcgJson -Step 'Authenticated actor verification' -Method GET -Uri "$root/api/auth/me"
    if ($me.actorId -ne $login.actorId -or $me.role -ne 'BUYER') {
        throw 'The authenticated session did not retain the logged-in BUYER principal.'
    }
    if ((Get-SessionFingerprint) -ne $authenticatedSession) {
        throw 'The authenticated session cookie changed unexpectedly after login.'
    }
    return $me
}

function Warm-MerchantEndpoint {
    $warmBase = $MerchantBaseUrl
    if ($null -eq $warmBase -and $BaseUrl.Host -like '*.onrender.com') { $warmBase = $BaseUrl }
    if ($null -eq $warmBase) { return }
    $health = $warmBase.AbsoluteUri.TrimEnd('/') + '/actuator/health'
    Write-Host "Warming merchant deployment at $health ..."
    try {
        $response = Invoke-RestMethod -Method GET -Uri $health -TimeoutSec 90 -ErrorAction Stop
        if ($response.status -ne 'UP') { throw "Health status was $($response.status)." }
    }
    catch { throw "Merchant deployment warm-up failed: $($_.Exception.Message)" }
}

function Write-CheckoutHarness {
    param([Parameter(Mandatory)] $Checkout, [Parameter(Mandatory)] [string] $OutputPath)
    $templatePath = Join-Path $PSScriptRoot 'razorpay-checkout.html'
    $template = [IO.File]::ReadAllText($templatePath)
    $json = $Checkout | ConvertTo-Json -Compress
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))
    [IO.File]::WriteAllText($OutputPath, $template.Replace('__CHECKOUT_INITIALIZATION_BASE64__', $encoded), [Text.Encoding]::UTF8)
}

$null = Connect-BuyerSession

if ($Phase -eq 'Prepare') {
    Warm-MerchantEndpoint
    $requestBody = @{
        requestId = $RequestId
        threadId = $null
        text = 'Buy one product with merchant SKU AMZ-AUDIO-032'
    } | ConvertTo-Json -Compress
    $result = Invoke-AcgMutation -Step 'Fresh Safe Buyer commerce request' -Path '/api/buyer/commerce-requests' -Body $requestBody
    if ($result.requestStatus -ne 'COMPLETED' -or $result.state -ne 'WAITING_FOR_USER' -or
        $null -eq $result.transactionProposalId -or -not $result.explicitAuthorizationRequired) {
        throw "Commerce request did not reach explicit authorization (state=$($result.state), status=$($result.requestStatus), failure=$($result.failureCode))."
    }
    if ($result.authoritativeFinalAmountMinor -ne 299900 -or $result.authoritativeCurrency -ne 'INR') {
        throw "Fresh proposal did not match the verified Auralink amount/currency (actual=$($result.authoritativeFinalAmountMinor) $($result.authoritativeCurrency))."
    }
    $line = @($result.products)[0]
    Write-Host ''
    Write-Host 'Payment authorization review'
    Write-Host "  Merchant: $($result.merchantDisplayName)"
    Write-Host "  Product:  $($line.productName) (SKU $($line.merchantSku))"
    Write-Host "  Amount:   $($result.authoritativeFinalAmountMinor) $($result.authoritativeCurrency)"
    Write-Host "  Proposal: $($result.transactionProposalId)"
    Write-Host "  Expires:  $($result.proposalExpiresAt)"
    Write-Host "  Required: $($result.authorizationState)"
    if ([string]::IsNullOrWhiteSpace($AuthorizationPhrase)) {
        $AuthorizationPhrase = Read-Host 'Type AUTHORIZE PAYMENT to create the bound AuthorizationDecision'
    }
    if ($AuthorizationPhrase -cne 'AUTHORIZE PAYMENT') { throw 'Explicit payment authorization was not granted.' }

    $transactionRoot = "/api/buyer/threads/$($result.threadId)/transaction/proposals/$($result.transactionProposalId)"
    $authorization = Invoke-AcgMutation -Step 'Explicit proposal confirmation' -Path "$transactionRoot/confirm"
    $authorizationReplay = Invoke-AcgMutation -Step 'Idempotent confirmation replay' -Path "$transactionRoot/confirm"
    if ($authorization.decision -ne 'AUTHORIZED' -or $authorization.authorizationMethod -ne 'EXPLICIT_CONFIRMATION' -or
        $authorization.authorizationId -ne $authorizationReplay.authorizationId -or
        $authorization.proposalHash -ne $result.transactionProposalHash) {
        throw 'AuthorizationDecision was not an exact idempotent explicit authorization for this proposal.'
    }
    $authorizationRead = Invoke-AcgJson -Step 'Authorization verification' -Method GET -Uri "$root$transactionRoot/authorization"
    if ($authorizationRead.authorizationId -ne $authorization.authorizationId) { throw 'Authorization read returned a different decision.' }

    $executionResult = Invoke-AcgMutation -Step 'Execution reservation' -Path "$transactionRoot/executions"
    $executionReplay = Invoke-AcgMutation -Step 'Idempotent execution replay' -Path "$transactionRoot/executions"
    if ($executionResult.decision -ne 'ALLOW' -or $executionResult.execution.status -ne 'RESERVED' -or
        $executionResult.execution.executionId -ne $executionReplay.execution.executionId) {
        throw 'Execution reservation did not converge on one RESERVED execution.'
    }
    $execution = Invoke-AcgJson -Step 'Execution verification' -Method GET -Uri "$root$transactionRoot/execution"
    if ($execution.executionId -ne $executionResult.execution.executionId) { throw 'Execution read returned a different reservation.' }

    $payment = Invoke-AcgMutation -Step 'Razorpay Test order initiation' -Path "$transactionRoot/payment/order"
    $paymentReplay = Invoke-AcgMutation -Step 'Idempotent Razorpay order replay' -Path "$transactionRoot/payment/order"
    if ([string]::IsNullOrWhiteSpace([string] $payment.providerOrderId) -or
        $payment.providerOrderId -ne $paymentReplay.providerOrderId -or
        $payment.executionId -ne $execution.executionId -or
        $payment.amountMinor -ne $result.authoritativeFinalAmountMinor -or $payment.currency -ne $result.authoritativeCurrency) {
        throw 'Razorpay order initiation did not preserve immutable execution authority or idempotency.'
    }
    $checkout = Invoke-AcgJson -Step 'Checkout initialization' -Method GET -Uri "$root$transactionRoot/payment/checkout"
    if ($checkout.providerOrderId -ne $payment.providerOrderId -or $checkout.executionId -ne $execution.executionId -or
        $checkout.proposalId -ne $result.transactionProposalId -or $checkout.amountMinor -ne $result.authoritativeFinalAmountMinor -or
        $checkout.currency -ne $result.authoritativeCurrency -or [string]::IsNullOrWhiteSpace([string] $checkout.publicKeyId)) {
        throw 'Checkout initialization did not match the proposal, execution, and provider order.'
    }

    $receipt = 'acg_' + ([string] $execution.executionId).Replace('-', '')
    $state = [ordered]@{
        baseUrl = $root; requestId = $result.requestId; threadId = $result.threadId
        proposalId = $result.transactionProposalId; proposalHash = $result.transactionProposalHash
        authorizationId = $authorization.authorizationId; authorizationDecision = $authorization.decision
        authorizationMethod = $authorization.authorizationMethod; executionId = $execution.executionId
        executionState = $execution.status; providerOrderId = $payment.providerOrderId
        amountMinor = $payment.amountMinor; currency = $payment.currency; receipt = $receipt
        merchantDisplayName = $result.merchantDisplayName; productName = $line.productName
    }
    $stateDirectory = Split-Path -Parent $StatePath
    if (-not [string]::IsNullOrWhiteSpace($stateDirectory)) { [IO.Directory]::CreateDirectory($stateDirectory) | Out-Null }
    [IO.File]::WriteAllText($StatePath, ($state | ConvertTo-Json), [Text.Encoding]::UTF8)
    $harnessPath = [IO.Path]::ChangeExtension($StatePath, '.html')
    Write-CheckoutHarness -Checkout $checkout -OutputPath $harnessPath
    if (-not $NoOpen) { Start-Process $harnessPath }

    Write-Host ''
    Write-Host 'CHECKOUT_READY'
    Write-Host "Checkout harness: $harnessPath"
    Write-Host "Resume command: .\scripts\dev\verify-razorpay-payment.ps1 -Phase Resume -BaseUrl '$root' -StatePath '$StatePath'"
    [pscustomobject]$state
    return
}

if (-not [IO.File]::Exists($StatePath)) { throw "Verification state was not found at $StatePath." }
$state = [IO.File]::ReadAllText($StatePath) | ConvertFrom-Json
if ($state.baseUrl -ne $root) { throw "State belongs to $($state.baseUrl), not $root." }
$transactionRoot = "/api/buyer/threads/$($state.threadId)/transaction/proposals/$($state.proposalId)"
$existing = Invoke-AcgJson -Step 'Prepared payment verification' -Method GET -Uri "$root$transactionRoot/payment"
if ($existing.providerOrderId -ne $state.providerOrderId -or $existing.executionId -ne $state.executionId) {
    throw 'Prepared payment state no longer matches the saved public verification state.'
}
$orderReplay = Invoke-AcgMutation -Step 'Post-checkout idempotent order replay' -Path "$transactionRoot/payment/order"
if ($orderReplay.providerOrderId -ne $state.providerOrderId) { throw 'Payment replay attempted to use a different provider order.' }

if ([string]::IsNullOrWhiteSpace($RazorpayPaymentId)) { $RazorpayPaymentId = Read-Host 'razorpay_payment_id' }
if ([string]::IsNullOrWhiteSpace($RazorpayOrderId)) { $RazorpayOrderId = Read-Host 'razorpay_order_id' }
if ([string]::IsNullOrWhiteSpace($RazorpaySignature)) { $RazorpaySignature = Read-Host 'razorpay_signature' }
if ($RazorpayOrderId -ne $state.providerOrderId) { throw 'Checkout result order ID does not match the prepared provider order.' }
$callbackBody = @{
    razorpayPaymentId = $RazorpayPaymentId
    razorpayOrderId = $RazorpayOrderId
    razorpaySignature = $RazorpaySignature
} | ConvertTo-Json -Compress
$callback = Invoke-AcgMutation -Step 'Signed Checkout callback' -Path "$transactionRoot/payment/callback" -Body $callbackBody
$callbackReplay = Invoke-AcgMutation -Step 'Duplicate signed Checkout callback' -Path "$transactionRoot/payment/callback" -Body $callbackBody
$RazorpaySignature = $null
$callbackBody = $null
if (-not $callback.accepted -or $callback.financialConfirmation -or
    -not $callbackReplay.accepted -or $callbackReplay.financialConfirmation) {
    throw 'Checkout callback did not remain idempotent evidence-only input.'
}

$afterCallback = Invoke-AcgJson -Step 'Post-callback payment state' -Method GET -Uri "$root$transactionRoot/payment"
$reconciliation = $null
for ($attempt = 1; $attempt -le $ReconcilePollCount; $attempt++) {
    $reconciliation = Invoke-AcgMutation -Step "Authoritative Razorpay reconciliation $attempt" -Path "$transactionRoot/payment/reconcile"
    if ($reconciliation.state.paymentState -eq 'PAYMENT_CONFIRMED') { break }
    if ($reconciliation.reconciliationStatus -eq 'MANUAL_REVIEW' -or $attempt -eq $ReconcilePollCount) { break }
    Start-Sleep -Seconds 2
}
$paymentState = Invoke-AcgJson -Step 'Authoritative payment state' -Method GET -Uri "$root$transactionRoot/payment"
$fulfillment = $null
if ($paymentState.paymentState -eq 'PAYMENT_CONFIRMED') {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($FulfillmentPollSeconds)
    do {
        $fulfillment = Invoke-AcgJson -Step 'Merchant fulfillment state' -Method GET -Uri "$root$transactionRoot/fulfillment"
        if ($fulfillment.fulfillmentState -eq 'FULFILLED' -or
            $fulfillment.fulfillmentState -eq 'TERMINAL_FAILURE' -or
            $fulfillment.fulfillmentState -eq 'COMPENSATION_REQUIRED') { break }
        Start-Sleep -Seconds 1
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
}

[pscustomobject]@{
    requestId = $state.requestId; threadId = $state.threadId; proposalId = $state.proposalId
    authorizationId = $state.authorizationId; executionId = $state.executionId
    providerOrderId = $state.providerOrderId; amountMinor = $state.amountMinor
    currency = $state.currency; receipt = $state.receipt
    callbackAccepted = $callback.accepted; callbackFinancialConfirmation = $callback.financialConfirmation
    stateImmediatelyAfterCallback = $afterCallback.paymentState
    reconciliationStatus = $reconciliation.reconciliationStatus
    paymentState = $paymentState.paymentState; confirmedPaymentId = $paymentState.confirmedPaymentId
    fulfillmentState = $(if ($null -eq $fulfillment) { $null } else { $fulfillment.fulfillmentState })
    merchantOperationId = $(if ($null -eq $fulfillment) { $null } else { $fulfillment.merchantOperationId })
    merchantOrderId = $(if ($null -eq $fulfillment) { $null } else { $fulfillment.merchantOrderId })
}
