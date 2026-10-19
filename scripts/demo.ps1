param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$MasterKey = "local-master-key"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http
$http = [System.Net.Http.HttpClient]::new()
$http.Timeout = [TimeSpan]::FromSeconds(20)

function Invoke-RelayApi {
    param(
        [string]$Method,
        [string]$Path,
        [string]$ApiKey,
        [object]$Body = $null
    )

    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::new($Method),
        ($BaseUrl.TrimEnd('/') + $Path)
    )
    $request.Headers.Add("X-API-Key", $ApiKey)
    if ($null -ne $Body) {
        $json = ConvertTo-Json -InputObject $Body -Depth 20 -Compress
        $request.Content = [System.Net.Http.StringContent]::new(
            $json,
            [System.Text.Encoding]::UTF8,
            "application/json"
        )
    }

    try {
        $response = $http.SendAsync($request).GetAwaiter().GetResult()
        $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $parsedBody = $null
        if (-not [string]::IsNullOrWhiteSpace($raw)) {
            $parsedBody = ConvertFrom-Json -InputObject $raw
        }
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Body       = $parsedBody
            RetryAfterSeconds = if ($null -ne $response.Headers.RetryAfter.Delta) {
                [math]::Ceiling($response.Headers.RetryAfter.Delta.TotalSeconds)
            } else { 0 }
        }
    }
    finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
    }
}

function Assert-Status {
    param([object]$Result, [int]$Expected, [string]$Action)
    if ($Result.StatusCode -ne $Expected) {
        throw "$Action failed: expected HTTP $Expected, received HTTP $($Result.StatusCode)."
    }
}

function New-Event {
    param([string]$ApiKey, [string]$IdempotencyKey, [string]$OrderId)
    $result = Invoke-RelayApi -Method "POST" -Path "/api/v1/events" -ApiKey $ApiKey -Body @{
        type           = "order.paid"
        idempotencyKey = $IdempotencyKey
        payload        = @{ orderId = $OrderId; amount = 12900 }
    }
    Assert-Status $result 202 "Publishing event"
    return $result.Body
}

function Wait-Delivery {
    param(
        [string]$ApiKey,
        [string]$EventId,
        [string]$ExpectedStatus,
        [int]$TimeoutSeconds = 120
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $result = Invoke-RelayApi -Method "GET" `
            -Path "/api/v1/events/$EventId/deliveries?limit=100&offset=0" -ApiKey $ApiKey
        Assert-Status $result 200 "Reading delivery status"
        $deliveries = @($result.Body.items)
        if ($deliveries.Count -gt 0 -and $deliveries[0].status -eq $ExpectedStatus) {
            return $deliveries[0]
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)

    $lastStatus = if ($deliveries.Count -gt 0) { $deliveries[0].status } else { "no delivery found" }
    throw "Timed out waiting for delivery status '$ExpectedStatus'; last state: $lastStatus."
}

function Wait-EndpointCircuit {
    param([string]$ApiKey, [string]$EndpointId, [string]$ExpectedState, [int]$TimeoutSeconds = 15)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $result = Invoke-RelayApi -Method "GET" -Path "/api/v1/endpoints" -ApiKey $ApiKey
        Assert-Status $result 200 "Reading endpoint circuit state"
        $endpoint = @($result.Body | Where-Object { $_.id -eq $EndpointId }) | Select-Object -First 1
        if ($null -ne $endpoint -and $endpoint.circuitState -eq $ExpectedState) { return $endpoint }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)

    $lastState = if ($null -ne $endpoint) { $endpoint.circuitState } else { "endpoint not found" }
    throw "Timed out waiting for endpoint circuit '$ExpectedState'; last state: $lastState."
}

try {
    $health = Invoke-RestMethod -Uri ($BaseUrl.TrimEnd('/') + "/actuator/health")
    if ($health.status -ne "UP") { throw "RelayBox is not healthy." }
    Write-Host "RelayBox is healthy at $BaseUrl" -ForegroundColor Green

    $suffix = [guid]::NewGuid().ToString("N")
    $tenantResult = Invoke-RelayApi -Method "POST" -Path "/api/v1/tenants" -ApiKey $MasterKey -Body @{
        name                     = "relaybox-demo-$suffix"
        eventRateLimitPerMinute = 120
    }
    Assert-Status $tenantResult 201 "Creating demo tenant"
    $tenantKey = $tenantResult.Body.apiKey
    Write-Host "Created demo tenant $($tenantResult.Body.id)"

    $endpointResult = Invoke-RelayApi -Method "POST" -Path "/api/v1/endpoints" -ApiKey $tenantKey -Body @{
        url        = "http://app:8080/dev/receiver/deliver"
        eventTypes = @("order.paid")
    }
    Assert-Status $endpointResult 201 "Creating webhook endpoint"
    $endpointId = $endpointResult.Body.id
    Write-Host "Registered endpoint $endpointId"

    $receiverConfig = Invoke-RelayApi -Method "POST" -Path "/dev/receiver/config" -ApiKey $MasterKey -Body @{
        failuresBeforeSuccess = 2
        failureStatus         = 500
    }
    Assert-Status $receiverConfig 200 "Configuring retry demonstration"
    $retryEvent = New-Event -ApiKey $tenantKey -IdempotencyKey "retry-$suffix" -OrderId "order-retry-$suffix"
    $retryDelivery = Wait-Delivery -ApiKey $tenantKey -EventId $retryEvent.eventId -ExpectedStatus "SUCCEEDED"
    $attemptResult = Invoke-RelayApi -Method "GET" `
        -Path "/api/v1/deliveries/$($retryDelivery.id)/attempts?limit=100&offset=0" -ApiKey $tenantKey
    Assert-Status $attemptResult 200 "Reading retry attempts"
    Write-Host "Retry demonstration: $($retryDelivery.attemptCount) attempts, final state $($retryDelivery.status)" -ForegroundColor Cyan
    $attemptResult.Body.items | Select-Object attemptNumber, status, responseCode, durationMs | Format-Table -AutoSize

    $receiverConfig = Invoke-RelayApi -Method "POST" -Path "/dev/receiver/config" -ApiKey $MasterKey -Body @{
        failuresBeforeSuccess = 100
        failureStatus         = 500
    }
    Assert-Status $receiverConfig 200 "Configuring dead-letter demonstration"
    $deadEvent = New-Event -ApiKey $tenantKey -IdempotencyKey "dead-$suffix" -OrderId "order-dead-$suffix"
    $deadDelivery = Wait-Delivery -ApiKey $tenantKey -EventId $deadEvent.eventId -ExpectedStatus "DEAD"
    Write-Host "Dead-letter demonstration: delivery $($deadDelivery.id) reached DEAD after $($deadDelivery.attemptCount) attempts" -ForegroundColor Yellow

    $receiverConfig = Invoke-RelayApi -Method "POST" -Path "/dev/receiver/config" -ApiKey $MasterKey -Body @{
        failuresBeforeSuccess = 0
        failureStatus         = 500
    }
    Assert-Status $receiverConfig 200 "Restoring successful receiver"
    $replayResult = Invoke-RelayApi -Method "POST" `
        -Path "/api/v1/deliveries/$($deadDelivery.id)/replay" -ApiKey $tenantKey
    Assert-Status $replayResult 202 "Replaying dead-letter delivery"
    $replayedDelivery = Wait-Delivery -ApiKey $tenantKey -EventId $deadEvent.eventId -ExpectedStatus "SUCCEEDED"
    Write-Host "Replay demonstration: delivery succeeded on attempt $($replayedDelivery.attemptCount)" -ForegroundColor Green

    $circuitEndpointResult = Invoke-RelayApi -Method "POST" -Path "/api/v1/endpoints" -ApiKey $tenantKey -Body @{
        url                         = "http://app:8080/dev/receiver/deliver"
        eventTypes                  = @("circuit.probe")
        circuitFailureThreshold    = 1
        circuitOpenDurationSeconds = 2
    }
    Assert-Status $circuitEndpointResult 201 "Creating circuit-breaker endpoint"
    $circuitEndpointId = $circuitEndpointResult.Body.id
    $receiverConfig = Invoke-RelayApi -Method "POST" -Path "/dev/receiver/config" -ApiKey $MasterKey -Body @{
        failuresBeforeSuccess = 1
        failureStatus         = 500
    }
    Assert-Status $receiverConfig 200 "Configuring circuit-breaker demonstration"
    $circuitEventResult = Invoke-RelayApi -Method "POST" -Path "/api/v1/events" -ApiKey $tenantKey -Body @{
        type           = "circuit.probe"
        idempotencyKey = "circuit-$suffix"
        payload        = @{ probeId = "probe-$suffix" }
    }
    Assert-Status $circuitEventResult 202 "Publishing circuit-breaker probe event"
    $circuitEvent = $circuitEventResult.Body
    $openedEndpoint = Wait-EndpointCircuit -ApiKey $tenantKey -EndpointId $circuitEndpointId -ExpectedState "OPEN"
    $recoveredDelivery = Wait-Delivery -ApiKey $tenantKey -EventId $circuitEvent.eventId -ExpectedStatus "SUCCEEDED"
    $recoveredEndpoint = Wait-EndpointCircuit -ApiKey $tenantKey -EndpointId $circuitEndpointId -ExpectedState "CLOSED"
    Write-Host "Circuit-breaker demonstration: opened after $($openedEndpoint.consecutiveFailures) failure, recovered to $($recoveredEndpoint.circuitState) after $($recoveredDelivery.attemptCount) attempts" -ForegroundColor Green

    $limitedTenantResult = Invoke-RelayApi -Method "POST" -Path "/api/v1/tenants" -ApiKey $MasterKey -Body @{
        name                     = "relaybox-limit-$suffix"
        eventRateLimitPerMinute = 1
    }
    Assert-Status $limitedTenantResult 201 "Creating rate-limited tenant"
    $limitedKey = $limitedTenantResult.Body.apiKey
    $null = New-Event -ApiKey $limitedKey -IdempotencyKey "limit-1-$suffix" -OrderId "order-limit-1"
    $limitedResult = Invoke-RelayApi -Method "POST" -Path "/api/v1/events" -ApiKey $limitedKey -Body @{
        type           = "order.paid"
        idempotencyKey = "limit-2-$suffix"
        payload        = @{ orderId = "order-limit-2" }
    }
    Assert-Status $limitedResult 429 "Verifying tenant rate limit"
    Write-Host "Rate-limit demonstration: second event returned HTTP 429 (Retry-After: $($limitedResult.RetryAfterSeconds) seconds)" -ForegroundColor Cyan

    Write-Host "Demo completed. Tenant API keys were kept in memory and are not printed." -ForegroundColor Green
}
finally {
    $http.Dispose()
}
