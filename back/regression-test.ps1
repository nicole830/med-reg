param([string]$Base = 'http://127.0.0.1:8081')
$ErrorActionPreference = 'Continue'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$D1 = (Get-Date).AddDays(1).ToString('yyyy-MM-dd')
$D2 = (Get-Date).AddDays(2).ToString('yyyy-MM-dd')
$D0 = (Get-Date).ToString('yyyy-MM-dd')
$docId = '1474207434229813248'
$docName = '王海花'

function SQL($q) { (& $mysql -uroot -p1234567890 -D order -e $q 2>$null) | Out-String }

# Redis 容器名不固定（redis / redis-hospital 等），自动探测；也可用 ZWZ_REDIS_CONTAINER 指定
$redisC = if ($env:ZWZ_REDIS_CONTAINER) { $env:ZWZ_REDIS_CONTAINER } else {
    (& docker ps --format '{{.Names}}' 2>$null | Where-Object { $_ -match 'redis' } | Select-Object -First 1)
}
if (-not $redisC) { Write-Output '警告：未找到 redis 容器，验证码相关用例会失败' }

function Req($method, $path, $token, $body, $form) {
    $h = @{}
    if ($token) { $h['accessToken'] = $token }
    $p = @{ Uri = "$Base$path"; Method = $method; Headers = $h; SkipHttpErrorCheck = $true; UseBasicParsing = $true }
    if ($body) { $p['ContentType'] = 'application/x-www-form-urlencoded'; $p['Body'] = $body }
    if ($form) { $p['Form'] = $form }
    try { $r = Invoke-WebRequest @p } catch { return [pscustomobject]@{ Status = -1; Body = $_.Exception.Message } }
    return [pscustomobject]@{ Status = [int]$r.StatusCode; Body = [string]$r.Content }
}

function Login($u, $p) {
    $ci = (((Req 'GET' '/zwz/common/captcha/init' $null).Body) | ConvertFrom-Json).result
    $code = ((docker exec $redisC redis-cli -n 1 GET $ci) -join '').Trim().Trim('"')
    $r = Req 'POST' '/zwz/login' $null "username=$u&password=$p&captchaId=$ci&code=$code&saveLogin=true"
    $j = $null
    try { $j = $r.Body | ConvertFrom-Json } catch {}
    if ($j -and $j.success -eq $true) { return [pscustomobject]@{ Ok = $true; Token = $j.result; Msg = $j.message } }
    return [pscustomobject]@{ Ok = $false; Token = $null; Msg = $(if ($j) { $j.message } else { $r.Body }) }
}

$script:rows = @()
function Denied($r) {
    if ($r.Status -eq 403) { return $true }
    return ($r.Body -match '"code"\s*:\s*403')
}
function Check($id, $desc, $cond, $detail) {
    $mark = if ($cond) { 'PASS' } else { 'FAIL' }
    $script:rows += [pscustomobject]@{ Id = $id; Result = $mark; Desc = $desc; Detail = $detail }
    Write-Output ("[{0}] {1} {2} :: {3}" -f $mark, $id, $desc, $detail)
}

# ---------- 测试数据准备 ----------
SQL "DELETE FROM a_hospital_order WHERE order_id IN ('T-SLOT-801','T-SLOT-802','T-SLOT-804'); DELETE FROM a_doctor_scheduling WHERE id IN ('T-SLOT-801','T-SLOT-802','T-SLOT-803','T-SLOT-804'); DELETE FROM a_doctor_scheduling WHERE doctor_id='$docId' AND date='$D2';"
SQL "INSERT INTO a_doctor_scheduling (id,doctor_id,doctor_name,date,step,number,order_flag,del_flag,create_time) VALUES ('T-SLOT-801','$docId','$docName','$D1','0','801',0,0,NOW()),('T-SLOT-802','$docId','$docName','$D1','0','802',0,0,NOW()),('T-SLOT-803','$docId','$docName','2020-01-01','0','803',0,0,NOW()),('T-SLOT-804','$docId','$docName','$D0','0','804',0,0,NOW());"

# ---------- 登录（P3-2 / 禁用账号） ----------
$a = Login 'admin' '123456'
$u = Login 'user1' '123456'
$ta = $a.Token; $tu = $u.Token
Check 'P3-2' 'admin 与 user1 均可登录' ($a.Ok -and $u.Ok) "admin=$($a.Ok) user1=$($u.Ok)"
Check 'LOGIN' '禁用账号(17859654125)被拒绝' (-not (Login '17859654125' '123456').Ok) ''
if (-not $ta) { Write-Output '登录失败，后续测试跳过'; exit 1 }

# ---------- P0-1 越权 ----------
$r = Req 'POST' '/zwz/hospitalOrder/delByIds' $tu 'ids=999999999999'
Check 'P0-1a' '普通用户删除挂号单被拦截(403)' (Denied $r) "status=$($r.Status)"
$r = Req 'GET' '/zwz/hospitalOrder/getAll' $tu
Check 'P0-1b' '普通用户读取全部挂号被拦截(403)' (Denied $r) "status=$($r.Status)"
$r = Req 'GET' '/zwz/hospitalOrder/getAll' $ta
Check 'P0-1c' '管理员读取全部挂号正常(200)' ($r.Status -eq 200) "status=$($r.Status)"

# ---------- P0-2 密码泄露 ----------
$r = Req 'GET' '/zwz/myUser/getByPage?pageNumber=1&pageSize=5' $tu
Check 'P0-2a' '普通用户访问用户列表被拦截' (Denied $r) "status=$($r.Status)"
$r = Req 'GET' '/zwz/myUser/getByPage?pageNumber=1&pageSize=5' $ta
$leak = $r.Body -match '"password"\s*:'
Check 'P0-2b' '管理员用户列表不返回 password 字段' (($r.Status -eq 200) -and (-not $leak)) "status=$($r.Status) leak=$leak"

# ---------- P0-3 IDOR ----------
$otherOrder = (SQL "SELECT id FROM a_hospital_order WHERE user_id <> (SELECT id FROM a_user WHERE username='user1') OR user_id IS NULL LIMIT 1;").Split("`n")[1].Trim()
$r = Req 'POST' '/zwz/hospitalOrder/payMoney' $tu "orderId=$otherOrder"
$j = $null; try { $j = $r.Body | ConvertFrom-Json } catch {}
Check 'P0-3a' '他人订单付款被拒绝' ($j -and $j.success -eq $false -and $j.message -like '*无权*') "order=$otherOrder msg=$(if($j){$j.message})"
$r = Req 'POST' '/zwz/hospitalOrder/payMoney' $tu 'orderId=NOT-EXIST-ORDER'
$j = $null; try { $j = $r.Body | ConvertFrom-Json } catch {}
Check 'P0-3b' '不存在的订单付款报错' ($j -and $j.success -eq $false) "msg=$(if($j){$j.message})"

# ---------- P0-4 并发抢号 ----------
$slot = 'T-SLOT-801'
$res = 1..4 | ForEach-Object -Parallel {
    $body = "orderId=$using:slot"
    try { $r = Invoke-WebRequest -Uri "$using:Base/zwz/hospitalOrder/addOrder" -Method Post -Headers @{ accessToken = $using:tu } -ContentType 'application/x-www-form-urlencoded' -Body $body -SkipHttpErrorCheck -UseBasicParsing } catch { return 'ERR' }
    return [string]$r.Content
} -ThrottleLimit 4
$okCnt = ($res | Where-Object { $_ -like '*预约成功*' }).Count
$dbCnt = (SQL "SELECT COUNT(*) FROM a_hospital_order WHERE order_id='$slot';").Split("`n")[1].Trim()
Check 'P0-4' '同一号源4并发仅1人成功' ($okCnt -eq 1 -and $dbCnt -eq '1') "apiSuccess=$okCnt dbOrder=$dbCnt"

# ---------- P1-1 取消释放号源 + 支付流水 ----------
$r = Req 'POST' '/zwz/hospitalOrder/addOrder' $tu 'orderId=T-SLOT-802'
$j = $r.Body | ConvertFrom-Json
$newOrder = (SQL "SELECT id FROM a_hospital_order WHERE order_id='T-SLOT-802' LIMIT 1;").Split("`n")[1].Trim()
$r = Req 'POST' '/zwz/hospitalOrder/payMoney' $tu "orderId=$newOrder"
$j1 = $r.Body | ConvertFrom-Json
Check 'P3-8a' '付款成功并写入支付流水' ($j1.success -eq $true -and (SQL "SELECT COUNT(*) FROM a_payment_record WHERE order_id='$newOrder';").Split("`n")[1].Trim() -eq '1') "msg=$($j1.message)"
$r = Req 'POST' '/zwz/hospitalOrder/payMoney' $tu "orderId=$newOrder"
$j2 = $r.Body | ConvertFrom-Json
Check 'P3-8b' '重复付款被拒绝(幂等)' ($j2.success -eq $false) "msg=$($j2.message)"
$r = Req 'POST' '/zwz/hospitalOrder/noneOrder' $tu "orderId=$newOrder"
$j3 = $r.Body | ConvertFrom-Json
$flag = (SQL "SELECT order_flag FROM a_doctor_scheduling WHERE id='T-SLOT-802';").Split("`n")[1].Trim()
$refund = (SQL "SELECT COUNT(*) FROM a_payment_record WHERE order_id='$newOrder' AND status=1;").Split("`n")[1].Trim()
Check 'P1-1' '取消挂号后号源释放且写退款流水' ($j3.success -eq $true -and $flag -eq '0' -and $refund -eq '1') "msg=$($j3.message) orderFlag=$flag refund=$refund"

# ---------- P1-2 历史号源 ----------
$r = Req 'POST' '/zwz/hospitalOrder/addOrder' $tu 'orderId=T-SLOT-803'
$j = $r.Body | ConvertFrom-Json
Check 'P1-2' '2020年号源不能预约' ($j.success -eq $false -and $j.message -like '*过期*') "msg=$($j.message)"

# ---------- P1-3 重复放号 / 连点幂等 ----------
$addBody = "doctorId=$docId&date=$D2&step=0&number=2"
$r1 = Req 'POST' '/zwz/doctorScheduling/addNumber' $ta $addBody
$j1 = $null; try { $j1 = $r1.Body | ConvertFrom-Json } catch {}
$r2 = Req 'POST' '/zwz/doctorScheduling/addNumber' $ta $addBody
$j2 = $null; try { $j2 = $r2.Body | ConvertFrom-Json } catch {}
$nums = (SQL "SELECT GROUP_CONCAT(number ORDER BY CAST(number AS UNSIGNED)) FROM a_doctor_scheduling WHERE doctor_id='$docId' AND date='$D2' AND step='0';").Split("`n")[1].Trim()
$dup = (SQL "SELECT COUNT(*) FROM (SELECT number FROM a_doctor_scheduling WHERE doctor_id='$docId' AND date='$D2' AND step='0' GROUP BY number HAVING COUNT(*)>1) t;").Split("`n")[1].Trim()
Check 'P1-3a' '首次放号成功' ($j1 -and $j1.success -eq $true) "msg=$(if($j1){$j1.message})"
Check 'P1-3b' '连点第二次被拒(5秒窗口)' ($j2 -and $j2.success -eq $false) "msg=$(if($j2){$j2.message})"
Check 'P1-3c' '只放一批号且编号不重复' ($dup -eq '0' -and $nums -eq '1,2') "numbers=$nums dupGroups=$dup"

# ---------- P1-4 今日筛选 ----------
$r = Req 'GET' '/zwz/doctor/getByPage?pageNumber=1&pageSize=10&noeDate=%E6%98%AF' $ta
$j = $null; try { $j = $r.Body | ConvertFrom-Json } catch {}
Check 'P1-4' '今日筛选(noeDate=是)返回正常JSON而非空body' (($r.Status -eq 200) -and $j -and $j.success -eq $true) "status=$($r.Status) bodyLen=$($r.Body.Length) total=$(if($j){$j.result.total})"

# ---------- P1-5 排班缺 step ----------
$r = Req 'GET' '/zwz/doctorScheduling/getByPage?pageNumber=1&pageSize=10' $ta
$j = $null; try { $j = $r.Body | ConvertFrom-Json } catch {}
Check 'P1-5' '排班分页不带step不报错' (($r.Status -eq 200) -and $j -and $j.success -eq $true) "status=$($r.Status) total=$(if($j){$j.result.total})"

# ---------- P1-6 排序 ----------
$r = Req 'GET' '/zwz/doctor/getByPage?pageNumber=1&pageSize=10&sort=updateTime&order=desc' $ta
$j = $null; try { $j = $r.Body | ConvertFrom-Json } catch {}
Check 'P1-6a' '按修改时间排序可用' (($r.Status -eq 200) -and $j -and $j.success -eq $true) "status=$($r.Status) total=$(if($j){$j.result.total})"
$r = Req 'GET' '/zwz/doctor/getByPage?pageNumber=1&pageSize=10&sort=id%3Bdrop%20table%20a_user&order=desc' $ta
$j = $null; try { $j = $r.Body | ConvertFrom-Json } catch {}
Check 'P1-6b' '非法排序字段被拦截' ($j -and $j.success -eq $false) "msg=$(if($j){$j.message})"

# ---------- P2-1 / P2-2 兼容接口 ----------
$r = Req 'GET' '/zwz/user/getByCondition?pageNumber=1&pageSize=5' $ta
$j = $null; try { $j = $r.Body | ConvertFrom-Json } catch {}
Check 'P2-1' '/user/getByCondition 可用' (($r.Status -eq 200) -and $j -and $j.success -eq $true) "status=$($r.Status)"
$r = Req 'GET' '/zwz/dynamic_new/get_service_by_ids' $ta
$j = $null; try { $j = $r.Body | ConvertFrom-Json } catch {}
Check 'P2-2' '/dynamic_new/get_service_by_ids 可用' (($r.Status -eq 200) -and $j -and $j.success -eq $true) "status=$($r.Status)"

# ---------- P2-3 上传返回可访问URL ----------
$tmp = Join-Path $env:TEMP 'zwz-reg-test.png'
Copy-Item 'C:\Users\nicole\Desktop\proj\hospital\med-reg\front\public\favicon.ico' $tmp -ErrorAction SilentlyContinue
if (-not (Test-Path $tmp)) { [IO.File]::WriteAllBytes($tmp, [byte[]](1..64)) }
$r = Req 'POST' '/zwz/upload/file' $ta $null @{ file = Get-Item $tmp }
$j = $null; try { $j = $r.Body | ConvertFrom-Json } catch {}
$u1 = if ($j -and $j.result) { [string]$j.result } else { '' }
$full = if ($u1 -like 'http*') { $u1 } else { "$Base$u1" }
$view = if ($full) { Req 'GET' ($full -replace [regex]::Escape($Base), '') $null } else { [pscustomobject]@{ Status = 0 } }
Check 'P2-3' '上传返回可访问的图片地址' ($u1 -ne '' -and $view.Status -eq 200) "url=$u1 viewStatus=$($view.Status)"
Check 'P2-3b' 'a_setting.FILE_VIEW 不再指向8080' ((SQL "SELECT value FROM a_setting WHERE id='FILE_VIEW';").Split("`n")[1] -notlike '*8080*') (SQL "SELECT value FROM a_setting WHERE id='FILE_VIEW';").Split("`n")[1]

# ---------- P2-4 上传缺文件 ----------
$r = Req 'POST' '/zwz/upload/file' $ta
$j = $null; try { $j = $r.Body | ConvertFrom-Json } catch {}
Check 'P2-4' '上传缺文件参数返回友好提示' ($j -and $j.success -eq $false -and $j.message -like '*选择*') "msg=$(if($j){$j.message})"

# ---------- P2-5 注册绑定默认角色 ----------
$regUser = 'zwz_reg_test'
SQL "DELETE FROM a_user_role WHERE user_id IN (SELECT id FROM a_user WHERE username='$regUser'); DELETE FROM a_user WHERE username='$regUser';"
$ci = ((Req 'GET' '/zwz/common/captcha/init' $null).Body | ConvertFrom-Json).result
$code = ((docker exec $redisC redis-cli -n 1 GET $ci) -join '').Trim().Trim('"')
$r = Req 'POST' '/zwz/user/regist' $null "username=$regUser&nickname=%E5%9B%9E%E5%BD%92%E6%B5%8B%E8%AF%95&password=123456&captchaId=$ci&code=$code"
$j = $null; try { $j = $r.Body | ConvertFrom-Json } catch {}
$role = (SQL "SELECT r.name FROM a_user_role ur JOIN a_role r ON r.id=ur.role_id JOIN a_user u ON u.id=ur.user_id WHERE u.username='$regUser';").Split("`n")[1].Trim()
Check 'P2-5' '新注册用户绑定到 ROLE_USER' ($j -and $j.success -eq $true -and $role -eq 'ROLE_USER') "msg=$(if($j){$j.message}) role=$role"

# ---------- P2-6 文件列表 NPE ----------
$r = Req 'GET' '/zwz/file/getByCondition?pageNumber=1&pageSize=5' $ta
$j = $null; try { $j = $r.Body | ConvertFrom-Json } catch {}
Check 'P2-6' '文件列表接口正常' (($r.Status -eq 200) -and $j -and $j.success -eq $true) "status=$($r.Status)"

# ---------- P2-7 新闻公开过滤 ----------
$r = Req 'GET' '/zwz/hospitalNews/getByPage?pageNumber=1&pageSize=5&publicOnly=yes' $tu
$j = $null; try { $j = $r.Body | ConvertFrom-Json } catch {}
$allPub = $true
if ($j -and $j.result -and $j.result.records) { foreach ($n in $j.result.records) { if ($n.isPublic -ne 'yes') { $allPub = $false } } }
Check 'P2-7' 'publicOnly=yes 只返回公开新闻' ($j -and $j.success -eq $true -and $allPub) "total=$(if($j){$j.result.total})"

# ---------- P2-8 新建新闻置顶 ----------
$r = Req 'POST' '/zwz/hospitalNews/insertOrUpdate' $ta 'title=%E5%9B%9E%E5%BD%92%E6%B5%8B%E8%AF%95%E6%96%B0%E9%97%BB&content=%E6%B5%8B%E8%AF%95'
$j = $null; try { $j = $r.Body | ConvertFrom-Json } catch {}
$newsId = if ($j -and $j.result) { [string]$j.result.id } else { '' }
$r2 = Req 'POST' '/zwz/hospitalNews/set_top_by_id' $ta "id=$newsId"
$j2 = $null; try { $j2 = $r2.Body | ConvertFrom-Json } catch {}
Check 'P2-8' '新建新闻可正常置顶' ($j2 -and $j2.success -eq $true) "newsId=$newsId msg=$(if($j2){$j2.message})"
if ($newsId) { Req 'POST' '/zwz/hospitalNews/delByIds' $ta "ids=$newsId" | Out-Null }

# ---------- P3-3 演示模块 ----------
$r = Req 'GET' '/zwz/student/getByPage?pageNumber=1&pageSize=5' $ta
Check 'P3-3' '演示模块 /zwz/student 已下线' (($r.Status -eq 404) -or ($r.Body -match '"status"\s*:\s*404')) "status=$($r.Status)"

# ---------- P3-6 Redis 独立库 ----------
$db1 = ((docker exec $redisC redis-cli -n 1 KEYS 'ZWZ_*') -join ',').Trim()
Check 'P3-6' 'Redis 已切到独立 db1，ZWZ_ key 写入成功' ($db1 -ne '') "db1Keys=$(($db1 -split ',').Count)"

# ---------- P3-7 验证码频控 ----------
$hits = 0
for ($i = 1; $i -le 25; $i++) {
    $rr = Req 'GET' '/zwz/common/captcha/init' $null
    if ($rr.Body -match '频繁') { $hits++ }
}
docker exec $redisC redis-cli -n 1 DEL "ZWZ_CAPTCHA_INIT:127.0.0.1" | Out-Null
docker exec $redisC redis-cli -n 1 DEL "ZWZ_CAPTCHA_INIT:0:0:0:0:0:0:0:1" | Out-Null
Check 'P3-7' '同一IP高频刷验证码被限流' ($hits -gt 0) "blockedTimes=$hits"

# ---------- 清理测试数据 ----------
SQL "DELETE FROM a_payment_record WHERE order_id='$newOrder'; DELETE FROM a_hospital_order WHERE order_id IN ('T-SLOT-801','T-SLOT-802','T-SLOT-803','T-SLOT-804'); DELETE FROM a_doctor_scheduling WHERE doctor_id='$docId' AND date IN ('$D1','$D2'); DELETE FROM a_doctor_scheduling WHERE id IN ('T-SLOT-803','T-SLOT-804'); DELETE FROM a_user_role WHERE user_id IN (SELECT id FROM a_user WHERE username='$regUser'); DELETE FROM a_user WHERE username='$regUser';"

$fails = ($script:rows | Where-Object { $_.Result -eq 'FAIL' }).Count
Write-Output "---- 汇总: 共 $($script:rows.Count) 项, PASS $($script:rows.Count - $fails), FAIL $fails ----"
$script:rows | Format-Table -AutoSize | Out-String -Width 200
