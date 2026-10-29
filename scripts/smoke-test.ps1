#requires -Version 7.0
<#
.SYNOPSIS
  AgentForge v0.1.0 端到端冒烟测试（真实 HTTP，按 docs/04-API契约.md 校验）。

.DESCRIPTION
  用法：
    pwsh -File scripts/smoke-test.ps1
    pwsh -File scripts/smoke-test.ps1 -BaseUrl http://127.0.0.1:18080 -AdminAccount admin@agentforge.dev -AdminPassword 'Admin@123456'

  依赖：后端以 dev profile 启动（H2 + Mock 模型），并已通过 Flyway V1~V6。
  说明：脚本不会修改业务数据语义，只创建带时间戳前缀的临时数据（知识库/agent/数据源）。
        某模块尚未完成时该步骤记为 SKIP 而不是 FAIL（FAIL 仅表示已实现但行为不符合契约）。

.OUTPUTS
  退出码 0 = 全部 PASS/SKIP；1 = 存在 FAIL。
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [string]$AdminAccount = 'admin@agentforge.dev',
    [string]$AdminPassword = 'Admin@123456',
    [string]$ApiPrefix = '/api/v1'
)

$ErrorActionPreference = 'Stop'
$script:Pass = 0
$script:Fail = 0
$script:Skip = 0
$script:Failures = [System.Collections.Generic.List[string]]::new()
$script:Token = $null

function Write-Step([string]$Name, [string]$Status, [string]$Detail = '') {
    $color = switch ($Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'Yellow' } }
    Write-Host ("[{0,-4}] {1,-48} {2}" -f $Status, $Name, $Detail) -ForegroundColor $color
    switch ($Status) {
        'PASS' { $script:Pass++ }
        'FAIL' { $script:Fail++; $script:Failures.Add("$Name :: $Detail") }
        default { $script:Skip++ }
    }
}

function Invoke-Api {
    <# 调用后端并解包 ApiResponse；返回 data。失败抛异常，异常信息含 code/message。 #>
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body,
        [hashtable]$Headers = @{},
        [int]$TimeoutSec = 30
    )
    $uri = "$BaseUrl$ApiPrefix$Path"
    $reqHeaders = @{ 'Accept' = 'application/json' }
    if ($script:Token) { $reqHeaders['Authorization'] = "Bearer $script:Token" }
    foreach ($k in $Headers.Keys) { $reqHeaders[$k] = $Headers[$k] }

    $params = @{ Method = $Method; Uri = $uri; Headers = $reqHeaders; TimeoutSec = $TimeoutSec; SkipHttpErrorCheck = $true }
    if ($null -ne $Body) {
        $params['ContentType'] = 'application/json; charset=utf-8'
        $params['Body'] = ($Body | ConvertTo-Json -Depth 10 -Compress)
    }
    $resp = Invoke-WebRequest @params
    $text = $resp.Content
    $parsed = $null
    try { $parsed = $text | ConvertFrom-Json } catch { }
    if ($resp.StatusCode -ge 400) {
        $msg = if ($parsed) { "HTTP $($resp.StatusCode) code=$($parsed.code) message=$($parsed.message)" } else { "HTTP $($resp.StatusCode): $text" }
        throw $msg
    }
    if ($null -eq $parsed) { return $text }
    if ($null -ne $parsed.code -and $parsed.code -ne 0) {
        throw "code=$($parsed.code) message=$($parsed.message)"
    }
    return $parsed.data
}

function Test-Step {
    param([string]$Name, [scriptblock]$Action, [switch]$Optional)
    try {
        $result = & $Action
        if ($result -eq 'SKIP') { Write-Step $Name 'SKIP' '接口未实现/模块未就绪'; return $null }
        Write-Step $Name 'PASS'
        return $result
    } catch {
        $detail = $_.Exception.Message
        if ($Optional -and ($detail -match 'HTTP 404|HTTP 503|code=50300|code=40400')) {
            Write-Step $Name 'SKIP' $detail
        } else {
            Write-Step $Name 'FAIL' $detail
        }
        return $null
    }
}

Write-Host "`n=== AgentForge smoke test @ $BaseUrl ===`n" -ForegroundColor Cyan

# ---------- 0. 存活与文档 ----------
Test-Step 'GET /actuator/health' {
    $h = Invoke-WebRequest -Method GET -Uri "$BaseUrl/actuator/health" -SkipHttpErrorCheck -TimeoutSec 10
    if ($h.StatusCode -ne 200) { throw "HTTP $($h.StatusCode)" }
    $null
} | Out-Null

Test-Step 'GET /v3/api-docs（OpenAPI 可访问）' {
    $d = Invoke-WebRequest -Method GET -Uri "$BaseUrl/v3/api-docs" -SkipHttpErrorCheck -TimeoutSec 15
    if ($d.StatusCode -ne 200) { throw "HTTP $($d.StatusCode)" }
    $null
} -Optional | Out-Null

# ---------- 1. 认证与 RBAC ----------
Test-Step 'POST /auth/login（默认管理员）' {
    $data = Invoke-Api -Method POST -Path '/auth/login' -Body @{ account = $AdminAccount; password = $AdminPassword }
    if (-not $data.accessToken) { throw '响应缺少 accessToken' }
    $script:Token = $data.accessToken
    if (-not $data.refreshToken) { throw '响应缺少 refreshToken' }
    $null
} | Out-Null

if ($script:Token) {
    Test-Step 'GET /auth/me（含 permissions）' {
        $me = Invoke-Api -Method GET -Path '/auth/me'
        $perms = @($me.permissions)
        if ($perms.Count -lt 5) { throw "permissions 数量异常: $($perms.Count)" }
        Write-Host "        roles=$($me.roles -join ',')" -ForegroundColor DarkGray
        $null
    } | Out-Null

    Test-Step 'GET /users（user:read 分页）' {
        $p = Invoke-Api -Method GET -Path '/users?page=1&size=10'
        if ($null -eq $p.total) { throw '响应缺少 total' }
        $null
    } | Out-Null
    Test-Step 'GET /roles' { Invoke-Api -Method GET -Path '/roles' | Out-Null } | Out-Null
    Test-Step 'GET /permissions' { Invoke-Api -Method GET -Path '/permissions' | Out-Null } | Out-Null
    Test-Step 'POST /auth/email-code（注册场景）' {
        Invoke-Api -Method POST -Path '/auth/email-code' -Body @{ email = "smoke-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())@example.com"; scene = 'REGISTER' } | Out-Null
    } | Out-Null
    Test-Step 'POST /auth/sms-code（预留→50300）' {
        try { Invoke-Api -Method POST -Path '/auth/sms-code' -Body @{ phone = '13800000000' } | Out-Null }
        catch { if ($_.Exception.Message -match '50300') { return $null } throw }
        throw '未按契约返回 50300'
    } | Out-Null

    # ---------- 2. 模型与 Token 审计 ----------
    $models = Test-Step 'GET /models' {
        $m = Invoke-Api -Method GET -Path '/models'
        if ($null -eq $m) { throw '响应为空' }
        return $m
    }
    if ($models) {
        $modelArr = if ($models -is [array]) { $models } else { @($models) }
        $first = $modelArr | Select-Object -First 1
        if ($first.id) {
            Test-Step "POST /models/$($first.id)/test（Mock 连通性）" {
                Invoke-Api -Method POST -Path "/models/$($first.id)/test" -TimeoutSec 60 | Out-Null
            } | Out-Null
        }
    }
    Test-Step 'GET /audit/usage/summary' { Invoke-Api -Method GET -Path '/audit/usage/summary' | Out-Null } | Out-Null
    Test-Step 'GET /audit/usage/trend?granularity=day' { Invoke-Api -Method GET -Path '/audit/usage/trend?granularity=day' | Out-Null } | Out-Null
    Test-Step 'GET /audit/overview' { Invoke-Api -Method GET -Path '/audit/overview' | Out-Null } | Out-Null

    # ---------- 3. 智能体 + SSE 对话 + 运行记录 ----------
    $stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $agent = Test-Step 'POST /agents（创建 SINGLE 智能体）' {
        return Invoke-Api -Method POST -Path '/agents' -Body @{
            name        = "smoke-agent-$stamp"
            description = 'smoke test agent'
            type        = 'SINGLE'
            systemPrompt = '你是冒烟测试助手，回答尽量简短。'
            temperature = 0.7
            maxIterations = 5
            enabled     = $true
        }
    }
    $agentId = if ($agent) { $agent.id } else { $null }

    if ($agentId) {
        Test-Step 'POST /chat/stream（SSE 事件流）' {
            $sseHeaders = @{ 'Accept' = 'text/event-stream'; 'Authorization' = "Bearer $($script:Token)" }
            $sseBody = @{ agentId = $agentId; message = '你好，请用一句话介绍你自己。' } | ConvertTo-Json -Compress
            $resp = Invoke-WebRequest -Method POST -Uri "$BaseUrl$ApiPrefix/chat/stream" -Headers $sseHeaders \
                -ContentType 'application/json; charset=utf-8' -Body $sseBody -TimeoutSec 120 -SkipHttpErrorCheck
            if ($resp.StatusCode -ne 200) { throw "HTTP $($resp.StatusCode): $($resp.Content)" }
            $text = $resp.Content
            if ($text -notmatch 'message\.delta') { throw '未收到 message.delta 事件' }
            if ($text -notmatch 'run\.completed') { throw '未收到 run.completed 事件' }
            if ($text -match 'event: error') { throw "SSE 返回 error 事件: $text" }
            $null
        } | Out-Null

        Test-Step 'GET /runs 列表（若契约提供）' {
            Invoke-Api -Method GET -Path '/runs?page=1&size=5' | Out-Null
        } -Optional | Out-Null

        Test-Step 'POST /agents/{id}/run（同步运行）' {
            Invoke-Api -Method POST -Path "/agents/$agentId/run" -Body @{ message = '1+1 等于几？' } -TimeoutSec 60 | Out-Null
        } -Optional | Out-Null
    }

    Test-Step 'GET /tools' { Invoke-Api -Method GET -Path '/tools' | Out-Null } | Out-Null

    # ---------- 4. RAG ----------
    $kb = Test-Step 'POST /knowledge-bases' {
        Invoke-Api -Method POST -Path '/knowledge-bases' -Body @{ name = "smoke-kb-$stamp"; description = 'smoke test kb' }
    }
    $kbId = if ($kb) { $kb.id } else { $null }
    if ($kbId) {
        $docPath = Join-Path ([System.IO.Path]::GetTempPath()) "smoke-doc-$stamp.txt"
        @"
AgentForge 冒烟测试知识片段 $stamp。
企业级多智能体框架支持 RAG 检索增强、Token 审计、RBAC 与安全沙箱。
唯一校验串：SMOKE_TOKEN_$stamp
"@ | Set-Content -Path $docPath -Encoding utf8

        $doc = Test-Step 'POST /knowledge-bases/{id}/documents（上传）' {
            $form = @{ file = Get-Item $docPath }
            $headers = @{ Authorization = "Bearer $($script:Token)" }
            $resp = Invoke-RestMethod -Method POST -Uri "$BaseUrl$ApiPrefix/knowledge-bases/$kbId/documents" -Headers $headers -Form $form -TimeoutSec 60
            if ($null -ne $resp.code -and $resp.code -ne 0) { throw "code=$($resp.code) message=$($resp.message)" }
            return $resp.data
        }
        if ($doc -and $doc.id) {
            Test-Step 'GET /documents/{id}（等待 READY）' {
                for ($i = 0; $i -lt 30; $i++) {
                    $d = Invoke-Api -Method GET -Path "/documents/$($doc.id)"
                    if ($d.status -in @('READY', 'FAILED')) {
                        if ($d.status -eq 'FAILED') { throw "解析失败: $($d.errorMessage)" }
                        return $null
                    }
                    Start-Sleep -Milliseconds 800
                }
                throw '等待解析超时（24s）'
            } | Out-Null

            Test-Step 'POST /knowledge-bases/{id}/search（命中校验）' {
                $hits = Invoke-Api -Method POST -Path "/knowledge-bases/$kbId/search" -Body @{ query = "SMOKE_TOKEN_$stamp"; topK = 3; threshold = 0.0 }
                $arr = if ($hits -is [array]) { $hits } else { @($hits) }
                if ($arr.Count -eq 0) { throw '检索结果为空' }
                $null
            } | Out-Null
        }
    }

    # ---------- 5. 沙箱 ----------
    Test-Step 'POST /sandbox/execute（Java 打印）' {
        $r = Invoke-Api -Method POST -Path '/sandbox/execute' -Body @{
            language = 'java'
            code     = 'public class Main { public static void main(String[] a) { System.out.println("SMOKE_SANDBOX_OK"); } }'
            timeoutSeconds = 30
        } -TimeoutSec 90
        if ($r.stdout -notmatch 'SMOKE_SANDBOX_OK') { throw "stdout 未包含标记: $($r.stdout)" }
        $null
    } -Optional | Out-Null

    # ---------- 6. 数据源（只读校验） ----------
    $ds = Test-Step 'POST /datasources（H2 只读示例）' {
        Invoke-Api -Method POST -Path '/datasources' -Body @{
            name = "smoke-ds-$stamp"; type = 'H2'
            jdbcUrl = "jdbc:h2:file:./.data/agentforge;MODE=MySQL;DATABASE_TO_LOWER=TRUE;ACCESS_MODE_DATA=r"
            username = 'sa'; password = ''; maxPoolSize = 3
        }
    }
    if ($ds -and $ds.id) {
        Test-Step 'POST /datasources/{id}/test' { Invoke-Api -Method POST -Path "/datasources/$($ds.id)/test" | Out-Null } | Out-Null
        Test-Step 'POST /datasources/{id}/query（SELECT 1）' {
            $q = Invoke-Api -Method POST -Path "/datasources/$($ds.id)/query" -Body @{ sql = 'SELECT 1 AS one'; maxRows = 10 }
            if ($q.rowCount -lt 1) { throw '查询无结果' }
            $null
        } | Out-Null
        Test-Step 'POST /datasources/{id}/query（DELETE 应被拒绝）' {
            try { Invoke-Api -Method POST -Path "/datasources/$($ds.id)/query" -Body @{ sql = 'DELETE FROM af_user'; maxRows = 10 } | Out-Null }
            catch { return $null }
            throw '危险 SQL 未被拒绝'
        } | Out-Null
    }

    # ---------- 7. 记忆 ----------
    Test-Step 'GET /memory/facts' { Invoke-Api -Method GET -Path '/memory/facts?page=1&size=10' | Out-Null } -Optional | Out-Null
}

Write-Host "`n=== 结果：PASS=$script:Pass SKIP=$script:Skip FAIL=$script:Fail ===" -ForegroundColor Cyan
if ($script:Fail -gt 0) {
    Write-Host "`n失败明细：" -ForegroundColor Red
    $script:Failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
exit 0
