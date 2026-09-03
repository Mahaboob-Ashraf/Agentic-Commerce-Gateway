[CmdletBinding()]
param(
    [uri] $BaseUrl = 'http://localhost:8080',
    [string] $Identity = $env:DEMO_BUYER_IDENTITY,
    [System.Management.Automation.PSCredential] $Credential,
    [guid] $RequestId = [guid]::NewGuid()
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
    if ($null -eq $cookie) {
        return 'none'
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes($cookie.Value)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $digest = $sha256.ComputeHash($bytes)
        return ([BitConverter]::ToString($digest) -replace '-', '').Substring(0, 12).ToLowerInvariant()
    }
    finally {
        $sha256.Dispose()
    }
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
        $arguments = @{
            Method = $Method
            Uri = $Uri
            WebSession = $session
            ErrorAction = 'Stop'
        }
        if ($null -ne $Headers) {
            $arguments.Headers = $Headers
        }
        if ($Method -eq 'POST') {
            $arguments.ContentType = 'application/json'
            $arguments.Body = $Body
        }
        $response = Invoke-RestMethod @arguments
        Write-Verbose "$Step accepted session=$(Get-SessionFingerprint)"
        return $response
    }
    catch {
        $failure = $_
        $status = if ($null -ne $failure.Exception.Response) {
            [int] $failure.Exception.Response.StatusCode
        }
        else {
            'unavailable'
        }
        $classification = $null
        if ($null -ne $failure.ErrorDetails -and
            -not [string]::IsNullOrWhiteSpace($failure.ErrorDetails.Message)) {
            try {
                $classification = ($failure.ErrorDetails.Message | ConvertFrom-Json).error
            }
            catch {
                $classification = $null
            }
        }
        $reason = if ([string]::IsNullOrWhiteSpace([string] $classification)) {
            $failure.Exception.GetType().FullName
        }
        else {
            [string] $classification
        }
        throw "$Step failed (HTTP status: $status, reason: $reason)."
    }
}

$anonymousCsrf = Invoke-AcgJson -Step 'Anonymous CSRF acquisition' -Method GET -Uri "$root/api/auth/csrf"
if ([string]::IsNullOrWhiteSpace($anonymousCsrf.token)) {
    throw 'Anonymous CSRF acquisition returned no token.'
}

$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Credential.Password)
$plainPassword = $null
$loginBody = $null
try {
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    $loginBody = @{
        identityHandle = $Credential.UserName
        password = $plainPassword
    } | ConvertTo-Json -Compress
    $login = Invoke-AcgJson -Step 'Buyer login' -Method POST -Uri "$root/api/auth/login" `
        -Headers (New-CsrfHeaders $anonymousCsrf) -Body $loginBody
}
finally {
    $plainPassword = $null
    $loginBody = $null
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
}

if ($login.role -ne 'BUYER') {
    throw "Authenticated actor does not have BUYER authority (actual role: $($login.role))."
}
$authenticatedSession = Get-SessionFingerprint
if ($authenticatedSession -eq 'none') {
    throw 'Login succeeded without retaining an ACG_SESSION cookie.'
}

# Login rotates the session CSRF token. Always retrieve the replacement from the same cookie session.
$authenticatedCsrf = Invoke-AcgJson -Step 'Post-login CSRF acquisition' -Method GET -Uri "$root/api/auth/csrf"
if ([string]::IsNullOrWhiteSpace($authenticatedCsrf.token)) {
    throw 'Post-login CSRF acquisition returned no token.'
}

$me = Invoke-AcgJson -Step 'Authenticated actor verification' -Method GET -Uri "$root/api/auth/me"
if ($me.actorId -ne $login.actorId -or $me.role -ne 'BUYER') {
    throw 'The authenticated session did not retain the logged-in BUYER principal.'
}
if ((Get-SessionFingerprint) -ne $authenticatedSession) {
    throw 'The authenticated session cookie changed unexpectedly after login.'
}

# Fetch immediately before the mutation so the submitted header is unambiguously bound to
# the authenticated session used by /me. No token or cookie value is printed.
$mutationCsrf = Invoke-AcgJson -Step 'Mutation CSRF acquisition' -Method GET -Uri "$root/api/auth/csrf"
if ((Get-SessionFingerprint) -ne $authenticatedSession) {
    throw 'The authenticated session cookie changed while acquiring the mutation CSRF token.'
}

$requestBody = @{
    requestId = $RequestId
    threadId = $null
    text = 'Buy one product with merchant SKU AMZ-AUDIO-032'
} | ConvertTo-Json -Compress

$result = $null
$result = Invoke-AcgJson -Step 'Safe Buyer commerce request' -Method POST `
    -Uri "$root/api/buyer/commerce-requests" `
    -Headers (New-CsrfHeaders $mutationCsrf) -Body $requestBody

if ($null -eq $result.transactionProposalId) {
    throw "Commerce request completed without a TransactionProposal (state=$($result.state), status=$($result.requestStatus), failure=$($result.failureCode))."
}
if (-not $result.explicitAuthorizationRequired) {
    throw 'TransactionProposal did not require explicit buyer authorization.'
}

$replayedResult = $null
$replayCsrf = Invoke-AcgJson -Step 'Replay CSRF acquisition' -Method GET -Uri "$root/api/auth/csrf"
$replayedResult = Invoke-AcgJson -Step 'Idempotent Safe Buyer replay' -Method POST `
    -Uri "$root/api/buyer/commerce-requests" `
    -Headers (New-CsrfHeaders $replayCsrf) -Body $requestBody

if ($replayedResult.transactionProposalId -ne $result.transactionProposalId -or
    $replayedResult.transactionProposalHash -ne $result.transactionProposalHash) {
    throw 'Idempotent replay returned a different TransactionProposal.'
}

[pscustomobject]@{
    requestId = $result.requestId
    threadId = $result.threadId
    state = $result.state
    requestStatus = $result.requestStatus
    transactionProposalId = $result.transactionProposalId
    transactionProposalHash = $result.transactionProposalHash
    constraintOverall = $result.constraintOverall
    availabilityOutcome = $result.availabilityOutcome
    serviceabilityOutcome = $result.serviceabilityOutcome
    authoritativeFinalAmountMinor = $result.authoritativeFinalAmountMinor
    authoritativeCurrency = $result.authoritativeCurrency
    explicitAuthorizationRequired = $result.explicitAuthorizationRequired
    authorizationState = $result.authorizationState
    idempotentReplayVerified = $true
}
