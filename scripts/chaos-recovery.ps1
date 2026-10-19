param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$MasterKey = "local-master-key"
)

$ErrorActionPreference = "Stop"
$base = $BaseUrl.TrimEnd('/')
$suffix = [guid]::NewGuid().ToString("N")
$workerA = "relaybox-chaos-a-$($suffix.Substring(0, 8))"
$initialWorkers = 0
$normalWorkersStopped = $false
$containerA = $null
$phase = 'initialization'
$originalLeaseSeconds = $env:WORKER_LEASE_SECONDS
Push-Location (Resolve-Path (Join-Path $PSScriptRoot '..'))

function Invoke-Api {
    param([string]$Method, [string]$Path, [string]$Key, [object]$Body = $null)
    $requestArgs = @{ Method = $Method; Uri = "$base$Path"; Headers = @{ 'X-API-Key' = $Key } }
    if ($null -ne $Body) {
        $requestArgs.ContentType = 'application/json'
        $requestArgs.Body = ConvertTo-Json -InputObject $Body -Depth 12 -Compress
    }
    for ($attempt = 1; $attempt -le 12; $attempt++) {
        try { return Invoke-RestMethod @requestArgs }
        catch {
            $statusCode = 0
            try { $statusCode = [int]$_.Exception.Response.StatusCode } catch { }
            if ($Method -ne 'GET' -or $statusCode -lt 500 -or $attempt -eq 12) {
                $responseBody = $_.ErrorDetails.Message
                Write-Warning "Request failed: $Method $Path, HTTP $statusCode, response: $responseBody"
                throw
            }
            Start-Sleep -Milliseconds 500
        }
    }
}

function Wait-Until {
    param([scriptblock]$Condition, [int]$TimeoutSeconds, [string]$Description)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $value = & $Condition
        if ($value) { return $value }
        Start-Sleep -Milliseconds 250
    }
    throw "Timed out waiting for $Description."
}

try {
    $health = Invoke-RestMethod "$base/actuator/health"
    if ($health.status -ne 'UP') { throw 'RelayBox API is not healthy.' }

    $tenant = Invoke-Api POST '/api/v1/tenants' $MasterKey @{
        name = "chaos-$suffix"
        eventRateLimitPerMinute = 120
    }
    $eventType = "demo.chaos.$suffix"
    $endpoint = Invoke-Api POST '/api/v1/endpoints' $tenant.apiKey @{
        url = 'http://app:8080/dev/receiver/chaos/deliver'
        eventTypes = @($eventType)
        circuitFailureThreshold = 5
        circuitOpenDurationSeconds = 30
    }

    $runningWorkers = @(docker compose ps -q delivery-service)
    if ($LASTEXITCODE -ne 0) { throw 'Could not read Compose worker state.' }
    $initialWorkers = @($runningWorkers | Where-Object { $_ }).Count
    if ($initialWorkers -gt 0) {
        $normalWorkersStopped = $true
        docker compose stop delivery-service | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop normal workers.' }
    }

    Invoke-Api POST '/dev/receiver/chaos/arm' $MasterKey | Out-Null
    $phase = 'start-worker-a'
    $containerA = (docker compose run -d --no-deps --use-aliases --name $workerA `
        -e WORKER_LEASE_SECONDS=12 -e WORKER_POLL_INTERVAL_MS=250 delivery-service).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $containerA) { throw 'Could not start delivery service A.' }
    $workerNetworks = docker inspect --format '{{json .NetworkSettings.Networks}}' $containerA | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect worker A network aliases.' }
    $workerAliases = @($workerNetworks.PSObject.Properties | ForEach-Object { $_.Value.Aliases })
    if ($workerAliases -notcontains 'delivery-service') { throw 'Worker A is missing the delivery-service DNS alias.' }

    $phase = 'first-delivery'
    $event = Invoke-Api POST '/api/v1/events' $tenant.apiKey @{
        type = $eventType
        idempotencyKey = "chaos-$suffix"
        payload = @{ orderId = "chaos-$suffix"; note = 'worker takeover demo' }
    }
    Write-Host "Event $($event.eventId) published; waiting for worker A to reach the receiver..."

    $firstReceipt = Wait-Until {
        $state = Invoke-Api GET '/dev/receiver/chaos/state' $MasterKey
        if ($state.receivedCount -ge 1) { $state.receipts[0] }
    } 20 'the first receiver request'

    $deliveries = Invoke-Api GET "/api/v1/events/$($event.eventId)/deliveries" $tenant.apiKey
    $delivery = @($deliveries.items)[0]
    if (-not $delivery -or $delivery.status -ne 'IN_FLIGHT' -or
        $firstReceipt.deliveryId -ne $delivery.id) {
        throw 'The first receipt does not match an in-flight delivery.'
    }
    Write-Host "Worker A claimed delivery $($delivery.id). Killing it while the receiver holds the response."
    $phase = 'kill-and-recover'
    docker update --restart=no $workerA | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not disable worker A restart policy.' }
    docker kill $workerA | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not kill worker A.' }
    docker rm $workerA | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not remove stopped worker A.' }
    $containerA = $null

    $env:WORKER_LEASE_SECONDS = '12'
    docker compose up -d --scale delivery-service=1 delivery-service | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start delivery service B.' }

    $complete = Wait-Until {
        $page = Invoke-Api GET "/api/v1/events/$($event.eventId)/deliveries" $tenant.apiKey
        $item = @($page.items)[0]
        if ($item.status -eq 'DEAD') { throw 'Delivery entered DEAD instead of recovering.' }
        if ($item.status -eq 'SUCCEEDED') { $item }
    } 50 'worker B to reclaim and complete the delivery'

    $attemptPage = Invoke-Api GET "/api/v1/deliveries/$($complete.id)/attempts" $tenant.apiKey
    $phase = 'assert-evidence'
    $attempts = @($attemptPage.items | Sort-Object attemptNumber)
    $receiver = Invoke-Api GET '/dev/receiver/chaos/state' $MasterKey
    if ($attempts.Count -ne 2 -or
        $attempts[0].status -ne 'FAILED' -or
        $attempts[0].error -notlike '*worker lease expired*' -or
        $attempts[1].status -ne 'SUCCEEDED' -or
        $receiver.receivedCount -ne 2 -or
        $receiver.appliedBusinessEffects -ne 1 -or
        -not $receiver.receipts[0].businessEffectApplied -or
        $receiver.receipts[1].businessEffectApplied -or
        @($receiver.receipts | Where-Object { $_.deliveryId -ne $complete.id }).Count -ne 0) {
        throw 'Recovery evidence did not match the expected two-attempt takeover.'
    }

    $report = [ordered]@{
        scenario = 'delivery-service-crash-lease-recovery'
        recordedAt = [DateTime]::UtcNow.ToString('o')
        leaseSeconds = 12
        eventId = $event.eventId
        deliveryId = $complete.id
        finalStatus = $complete.status
        firstWorkerKilledAfterReceiverAccepted = $true
        workerANetworkAliases = $workerAliases
        attempts = $attempts
        receiverAppliedBusinessEffects = $receiver.appliedBusinessEffects
        receiverReceipts = $receiver.receipts
        note = 'Two physical HTTP requests share one delivery ID; receiver deduplication applies the demo business effect once.'
    }
    $resultDir = Join-Path $PSScriptRoot '..\benchmarks\results'
    New-Item -ItemType Directory -Force -Path $resultDir | Out-Null
    $resultPath = Join-Path $resultDir "chaos-$($suffix.Substring(0, 12)).json"
    ConvertTo-Json -InputObject $report -Depth 12 | Set-Content -Path $resultPath -Encoding UTF8
    Write-Host "Recovered: first attempt FAILED (lease expired), second SUCCEEDED; receiver observed two requests and one business effect."
    Write-Host "Evidence: $resultPath"
}
catch {
    $resultDir = Join-Path $PSScriptRoot '..\benchmarks\results'
    New-Item -ItemType Directory -Force -Path $resultDir | Out-Null
    [ordered]@{
        scenario = 'delivery-service-crash-lease-recovery'
        recordedAt = [DateTime]::UtcNow.ToString('o')
        complete = $false
        phase = $phase
        error = $_.Exception.Message
        response = $_.ErrorDetails.Message
    } | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $resultDir "chaos-failed-$($suffix.Substring(0,12)).json") -Encoding UTF8
    throw
}
finally {
    if ($containerA) {
        try { docker rm -f $workerA 2>$null | Out-Null }
        catch { Write-Warning "Could not remove temporary worker ${workerA}: $_" }
    }
    if ($null -eq $originalLeaseSeconds) { Remove-Item Env:\WORKER_LEASE_SECONDS -ErrorAction SilentlyContinue }
    else { $env:WORKER_LEASE_SECONDS = $originalLeaseSeconds }
    if ($normalWorkersStopped) {
        try {
            docker compose up -d --scale "delivery-service=$initialWorkers" delivery-service | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "docker compose returned $LASTEXITCODE" }
        } catch {
            Write-Warning "Normal delivery services did not restart. Run: docker compose up -d --scale delivery-service=$initialWorkers delivery-service. $_"
        }
    }
    Pop-Location
}
