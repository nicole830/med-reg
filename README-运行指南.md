# 医院门诊预约挂号系统 · 本地运行指南

> 本文档只讲「怎么把项目跑起来」，功能说明与截图见原 `README.md`。

## 1. 项目结构

```
med-reg/
├─ back/                  Spring Boot 后端（Maven 工程）
│  ├─ pom.xml
│  └─ src/main/java/cn/zwz
├─ front/                 Vue2 前端（Vue CLI 4）
│  ├─ package.json
│  ├─ vue.config.js       端口 8080，/zwz 代理到 8081
│  └─ src
├─ image/                 原 README 用图
├─ order220912.sql        MySQL 初始化脚本（含表结构与演示数据）
└─ README.md              原项目说明
```

## 2. 技术栈与端口

| 组件 | 版本 / 地址 | 说明 |
| --- | --- | --- |
| 后端 | Spring Boot 2.7.18 + MyBatis-Plus 3.5.2 | 端口 **8081**，接口前缀 `/zwz` |
| 前端 | Vue 2.6.14 + View UI 4.7.0 | dev 端口 **8080**，被占用时自动顺延 |
| 数据库 | MySQL 8.0（库名 `order`） | 默认 `127.0.0.1:3306` |
| 缓存 | Redis（db 1） | 默认 `127.0.0.1:6379`，存验证码与登录 token；2026-11-27 起改用 db1，与其它项目隔离 |

## 3. 环境要求

- **JDK 17**（`pom.xml` 中 `java.version=17`；不要用 JDK 21，Spring Boot 2.7 存在兼容风险）
- **Maven 3.6+**
- **Node.js 16**（项目原始开发环境）；用 Node 18/20/22 时见第 7 节
- **MySQL 8.0**、**Redis 5+**

## 4. 首次启动

### 4.1 数据库

确认库和表已存在：

```sql
SHOW TABLES FROM `order`;
```

库为空时才需要导入：

```bash
mysql -uroot -p --default-character-set=utf8mb4 < order220912.sql
```

> ⚠️ `order220912.sql` 前几行是 `DROP DATABASE IF EXISTS order`，执行前先备份现有数据，已有数据时不要重复导入。

### 4.2 Redis

```bash
redis-server            # 或 docker run -d --name redis -p 6379:6379 redis
redis-cli ping          # 期望返回 PONG

docker start redis

```

Redis 没启动时，验证码接口会 500，登录必然失败。

2026-11-27 起本项目使用 **db1**（与其它项目隔离），验证码与 token 的 key 形如 `ZWZ_*`，可用 `redis-cli -n 1 keys 'ZWZ_*'` 查看。

### 4.3 后端（8081）

```bash
cd back
mvn clean package -DskipTests
java -jar target/order-register-1.0.0.jar
```

数据库账号密码在 `back/src/main/resources/application.yml` 第 13-16 行，已与本机一致（`root / 1234567890`），直接启动即可。换环境时用启动参数临时覆盖：

```bash
java -jar target/order-register-1.0.0.jar \
  --spring.datasource.username=root \
  --spring.datasource.password=你的密码
```

Windows 下或需要固定 UTF-8 时，直接用仓库里的启动脚本（会先停 8081 再拉起，并用 `JAVA_TOOL_OPTIONS` 传编码参数）：

```powershell
cd back
pwsh -File .\start-backend.ps1
```

启动成功的标志：

```
Tomcat started on port(s): 8081 (http)
Started ZwzApplication in 11.4 seconds
```

自检：

```bash
curl http://127.0.0.1:8081/zwz/common/captcha/init
# {"result":"...","success":true,"code":200,"message":"OK"}
```

### 4.4 前端（8080）

```bash
cd front
npm install
npm run dev
```

编译成功后控制台会输出实际地址：

```
DONE  Compiled successfully
App running at:
- Local: http://localhost:8080/
```

若 8080 已被其他程序占用，Vue CLI 会自动换到 8081/8082…… 以控制台输出为准。

### 4.5 访问

浏览器打开前端地址（如 `http://localhost:8080`），用以下账号登录：

| 角色 | 账号 | 密码 |
| --- | --- | --- |
| 超级管理员 | `admin` | `123456` |
| 患者 | `user1` | `123456` |

## 5. 日常启动（依赖已装好时）

```bash
docker start redis
# 终端 1：后端（Windows 推荐用脚本，避免编码参数被拆开）
cd back && pwsh -File .\start-backend.ps1

# 终端 2：前端
cd front && npm run dev
```

## 6. 功能入口

- **前台（患者）**：注册登录、科室/医生浏览检索、预约挂号、我的挂号与模拟付款、留言板、医院新闻
- **后台（管理员）**：数据中心、科室管理、医生管理、医生放号、预约订单管理、留言回复、新闻管理、用户/角色/菜单权限

权限为「用户-角色-菜单」三级 RBAC，菜单由后端按角色动态下发。

## 7. 常见问题

**① 前端报 `Error: error:0308010C:digital envelope routines::unsupported`**

Node 17+ 与 webpack 4 的 OpenSSL 冲突，启动前设置：

```powershell
$env:NODE_OPTIONS="--openssl-legacy-provider"   # Windows PowerShell
```

```bash
export NODE_OPTIONS=--openssl-legacy-provider   # macOS / Linux
```

**② `npm install` 卡住或报 `Exit handler never called`**

多为镜像源拉包超时。降低并发并加重试：

```bash
npm install --legacy-peer-deps --maxsockets=4 --fetch-retries=6
```

仍失败时换源：`npm config set registry https://registry.npmjs.org`。

**③ 登录提示「图形验证码输入错误」**

验证码有效期 2 分钟，过期需点图片刷新；同时确认 Redis 正常。同一 IP 每分钟最多取 20 次验证码，超限会提示“验证码请求过于频繁”（等 1 分钟即可）。

**④ 后端启动报数据库连接失败**

核对 `application.yml` 的地址、库名、账号密码，以及 MySQL 是否允许本地连接。

**⑤ 端口冲突**

后端 8081、前端 8080。被占用时改 `application.yml` 的 `server.port` 与 `vue.config.js` 的 `devServer.port`（同时改 `proxy` 的 target），或直接用控制台给出的顺延端口。

**⑥ 中文乱码**

不要在 `java -jar` 后面直接拼 `-Dfile.encoding=UTF-8`——Windows 下它会被 JDK 当成“主类”，报 `ClassNotFoundException: /encoding=UTF-8`。正确做法是用环境变量：

```powershell
$env:JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"
```

或者直接用 `back/start-backend.ps1`（脚本已处理好这一步）。

## 8. 本机已验证的运行记录

| 项目 | 结果 |
| --- | --- |
| `mvn clean package -DskipTests` | BUILD SUCCESS，产出 `target/order-register-1.0.0.jar`（约 97 MB） |
| 后端启动 | `Started ZwzApplication in 11.4s`，8081 监听正常 |
| 验证码接口 | `GET /zwz/common/captcha/init` 返回 200（Redis 读写正常） |
| 前端启动 | `Compiled successfully in 11.4s`，页面 200，`/zwz` 代理转发到 8081 返回 200 |
| 实际访问端口 | 后端 8081，前端 8082（8080 被其他 Java 进程占用） |

## 9. 安全相关默认行为（2026-11 两轮修复后）

| 项 | 现状 |
| --- | --- |
| 管理接口鉴权 | `/zwz/role/**`、`/zwz/myUser/**`、`/zwz/hospitalOrder/getAll` 等 43 条仅 `ROLE_ADMIN` 可访问；普通访问返回 **HTTP 200 + `code=403`**（框架统一出口，前端按 `code` 判断） |
| 密码字段 | `User.password` 为只写字段，用户列表接口不再返回 BCrypt 哈希 |
| 订单付款/取消 | 校验归属与状态，条件更新保证幂等，并写 `a_payment_record` 支付/退款流水 |
| 并发占号 | 号源 `order_flag` 条件更新 + 唯一索引 `uk_doc_date_step_num`，同一号源只会被卖一次 |
| 演示模块 | `/zwz/student`、`/zwz/teacher` 默认不注册；需要时启动加 `--zwz.demo.enabled=true` |
| 限流 | 单 IP 与全局各 100 次/秒；验证码 `init` 另有 60 秒 20 次 IP 频控 |
| 文件地址 | 上传返回相对路径 `/zwz/file/view/{id}`，`a_setting.FILE_VIEW` 为 `127.0.0.1:8082/zwz/file/view`（开发环境，生产换真实域名） |
| 回归脚本 | `back/regression-test.ps1`，31 项断言覆盖 bug 报告全部编号 |
