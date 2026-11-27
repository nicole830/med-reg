# 医院门诊挂号系统 - 后端标准启动脚本
# 用法：在 back 目录执行  pwsh -File .\start-backend.ps1
# 说明：不要在命令行里写 -Dfile.encoding=UTF-8 给 java（会变成“主类”），
#      Windows 下用 JAVA_TOOL_OPTIONS 传 JVM 参数才是正确做法。

$ErrorActionPreference = 'Stop'
$jdk = 'C:\Program Files\Java\jdk-17'
$jar = Join-Path $PSScriptRoot 'target\order-register-1.0.0.jar'

if (-not (Test-Path $jar)) {
    Write-Output "未找到 $jar，先执行打包：mvn -o -DskipTests package"
    exit 1
}

# 端口占用先停掉旧进程
$conns = Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue
foreach ($c in $conns) { Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 2

$env:JAVA_HOME = $jdk
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'

Start-Process -FilePath (Join-Path $jdk 'bin\java.exe') `
    -ArgumentList '-jar', $jar `
    -RedirectStandardOutput (Join-Path $PSScriptRoot 'run-out.log') `
    -RedirectStandardError (Join-Path $PSScriptRoot 'run-err.log') `
    -WindowStyle Hidden

# 等待端口就绪（最多 90 秒）
$deadline = (Get-Date).AddSeconds(90)
do {
    Start-Sleep -Seconds 3
    $ok = Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue
} until ($ok -or (Get-Date) -gt $deadline)

if ($ok) { Write-Output '后端已启动: http://127.0.0.1:8081/zwz' } else { Write-Output '启动超时，请查看 run-err.log'; exit 1 }
