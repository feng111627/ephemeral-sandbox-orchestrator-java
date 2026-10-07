# 临时环境（沙箱）申请与回收服务

平台侧的临时环境编排服务：支持环境从申请、配置、就绪到回收的完整状态流转，
按归属方（团队/租户）做配额与归属校验，申请过程具备失败补偿与幂等能力；
**到期后由平台自动回收并归还配额**，有效期上限与扫描间隔均可本地配置，
并提供归属方名下环境与配额总览。全部基于内存存储与模拟配置器，本地即可运行验证。

## 状态机

```
                 ┌──────────── 失败 ────────────┐
                 ▼                              │
 REQUESTED ──► PROVISIONING ──► READY ──► RELEASING ──► RELEASED
    │               │                      ▲       （终态：人工回收 / 到期自动回收）
    └───────┬───────┘                      │
            ▼                            到期扫描 / 过期续期惰性触发
          FAILED（终态，申请/配置失败）
```

合法迁移：

| 当前状态 | 允许迁移到 |
|---|---|
| REQUESTED | PROVISIONING, FAILED, RELEASING |
| PROVISIONING | READY, FAILED, RELEASING |
| READY | RELEASING |
| RELEASING | RELEASED |
| RELEASED / FAILED | （终态，无出边） |

非法迁移（跳跃、回退、终态出边）一律被拒绝，抛出 `ILLEGAL_STATE_TRANSITION`（HTTP 409），
错误信息包含沙箱 id 与具体的 from -> to，原因可区分。

## 到期自动回收

- 平台调度器 `ExpiryReaper` 按固定间隔（`sandbox.expiry.scan-interval-ms`，默认 1000ms）扫描，
  把所有 `expiresAt <= now` 且处于 **READY** 的环境自动收为 RELEASED，并归还配额、销毁底层环境。
- 自动回收与人工回收、续期共用**同一套回收逻辑与同一把分段锁**，同一环境上的操作严格串行：
  - 人工回收与自动回收并发：终态必然是 RELEASED，底层销毁恰好一次，**配额恰好归还一次**
    （重复回收是幂等 no-op，不重复还、不漏还）。
  - 续期与自动回收并发：续期先拿到锁则延长到期时间、环境继续 READY 且不回收；
    回收先完成则续期被明确拒绝（`ILLEGAL_STATE_TRANSITION`），二者结果都一致可预期。
  - 终态化之后通过 GET 查询永远返回 RELEASED，收尾状态可查询、可预期。
- **惰性回收**：若环境已过期但尚未被调度扫描到，此时收到的续期请求会在同一把锁内
  顺手完成自动回收（终态化 + 归还配额），再返回 `SANDBOX_EXPIRED`（HTTP 410），
  因此不存在“已过期却仍 READY 占着配额”的悬空窗口。
- 自动回收由平台系统触发，不做归属方校验（任何归属方的到期环境都会被收）。

## 有效期与续期边界

有效期上限通过 `sandbox.ttl.max-seconds` 本地配置，默认 86400（24 小时）。

| 场景 | 规则 | 结果 |
|---|---|---|
| 申请 TTL 为 null / 0 / 负数 | 非法 | `INVALID_TTL`（HTTP 422），**不占配额、不产生半成品环境** |
| 申请 TTL > 最大上限 | 超限 | `TTL_LIMIT_EXCEEDED`（HTTP 422），不占配额、不产生半成品 |
| 申请 TTL = 上限 | 边界 | 允许 |
| 续期时长为 0 / 负数 | 非法 | `INVALID_TTL`（HTTP 422） |
| 续期后到期时间 > now + 上限 | 超限 | `TTL_LIMIT_EXCEEDED`（HTTP 422），**整体拒绝、到期时间不变** |
| 续期后到期时间 = now + 上限 | 边界 | 允许 |
| 对已过期环境续期 | 过期 | 惰性自动回收后返回 `SANDBOX_EXPIRED`（HTTP 410） |
| 对 RELEASED/FAILED 环境续期 | 终态 | `ILLEGAL_STATE_TRANSITION`（HTTP 409） |

> 续期以环境当前到期时间为基准累加（当前到期时间已过则以 now 为基准的路径不会出现：
> 过期会被惰性回收拦截），续期只能把到期时间推到“当前时刻 + 最大上限”以内，无法无限后推。

## 归属方名下总览

`GET /api/sandboxes/overview?owner=<团队/租户>` 返回：

- `active`：当前在用的环境（非终态）
- `ended`：已结束的环境（RELEASED / FAILED），人工回收、到期自动回收、失败补偿都会进入该列表
- `quotaLimit` / `quotaUsed` / `quotaRemaining`：配额上限、已占用、剩余
  （剩余 = 上限 − 已占用，最小为 0），申请前即可据此判断是否还够。

## 错误码

| 错误码 | HTTP | 含义 |
|---|---|---|
| SANDBOX_NOT_FOUND | 404 | 环境不存在 |
| ILLEGAL_STATE_TRANSITION | 409 | 非法状态迁移（如回收后续期） |
| QUOTA_EXCEEDED | 429 | 超出归属方配额 |
| NOT_OWNER | 403 | 非归属方操作（续期/回收） |
| PROVISION_FAILED | 500 | 配置失败（已自动补偿） |
| INVALID_TTL | 422 | 有效期/续期时长非法（空、零、负数） |
| TTL_LIMIT_EXCEEDED | 422 | 申请或续期超过平台最大有效期 |
| SANDBOX_EXPIRED | 410 | 环境已过期（续期时已顺手惰性回收） |

## 配额与回收规则

- 配额按归属方（owner，团队/租户）维度计数，默认上限 3（`sandbox.quota.default-limit` 可配），
  可通过 `QuotaManager.setLimit` 为单个归属方设置独立上限。
- 申请通过 TTL 校验后才占用 1 个配额；超限申请被拒绝（`QUOTA_EXCEEDED`），不产生任何占用。
- 配额归还恰好一次：沙箱实体携带 `quotaHeld` 标记，只有持有配额的环境在
  回收（RELEASED：人工或自动）或失败补偿（FAILED）时才归还；重复回收（人工/自动）
  是幂等 no-op，不会重复归还或漏还。
- 归属校验：人工续期与回收仅归属方可操作，非归属方操作被拒绝（`NOT_OWNER`）且不改变状态。

## 幂等与失败补偿

- **申请幂等**：以 `owner + idempotencyKey` 为幂等键。重复提交或中断后重放返回同一环境，
  不重复占用配额、不重复触发配置；并发提交同一幂等键只产生一个环境。
- **失败补偿**：配置任一步骤失败时，环境置为 FAILED、配额立即归还、幂等索引清除，
  不残留占用；客户端可用同一幂等键安全重试，会创建新环境。
- **并发一致性**：按 key 哈希的分段锁将同一幂等键 / 同一沙箱上的操作串行化。
  续期、人工回收、自动回收并发时结果一致：回收成功后配额必然归还，
  续期要么先于回收生效，要么被明确拒绝，不会出现状态错乱或重复占用。

## REST 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/sandboxes | 申请环境 `{owner, idempotencyKey, ttlSeconds}` |
| POST | /api/sandboxes/{id}/renew | 续期 `{operator, extendSeconds}` |
| POST | /api/sandboxes/{id}/release | 回收 `{operator}`（幂等） |
| GET | /api/sandboxes/{id} | 查询环境 |
| GET | /api/sandboxes/overview?owner=team-a | 归属方名下总览（在用/已结束/配额） |

## 配置项

| 配置 | 默认值 | 说明 |
|---|---|---|
| `sandbox.ttl.max-seconds` | 86400 | 申请时长与续期后到期时间的最大上限（秒） |
| `sandbox.expiry.scan-interval-ms` | 1000 | 到期自动回收扫描间隔（毫秒），本地验证可调小 |
| `sandbox.quota.default-limit` | 3 | 归属方默认配额上限 |

## 本地验证

### 运行单测

```bash
mvn test
```

测试按业务分组，日志以 `[业务: xxx] 执行用例: ...` 输出每个用例名称，并在关键用例输出
覆盖的业务结果（并发轮次统计、自动回收状态与配额、端到端链路等）：

- `StateMachineTest`（业务: 状态机，4 例）：合法迁移全链路、失败补偿路径迁移、终态无出边、
  非法迁移拒绝且原因可区分。
- `QuotaManagerTest`（业务: 配额，4 例）：上限边界、归还不为负、归属方隔离、16 线程并发抢占不超发。
- `SandboxServiceTest`（业务: 沙箱服务，11 例）：申请进入 READY、幂等重放、并发重复提交只产生一个
  环境、超配额拒绝、配置失败补偿与安全重试、非归属方拒绝、续期延长、回收后续期拒绝、
  回收幂等且配额恰好归还一次、续期与回收并发 20 轮结果一致。
- `TtlPolicyTest`（业务: 有效期策略，7 例）：申请 TTL 等于/超过/非法边界、非法与超限不占配额不留
  半成品、续期恰好到上限放行、续期超限整体拒绝、非法续期时长、过期续期返回 SANDBOX_EXPIRED 并被
  惰性回收。
- `ExpiryReaperTest`（业务: 到期自动回收，6 例）：秒级 TTL 到期自动回收终态可查且归还配额、
  未到期不回收、重复扫描/重复自动回收幂等、自动与人工回收并发 20 轮销毁/配额恰好一次、
  到期边界续期与自动回收并发 30 轮两侧结果都发生且一致、跨归属方批量回收互不影响。
- `OwnerOverviewTest`（业务: 名下总览，5 例）：在用列表与剩余配额、人工回收/自动回收后进入已结束、
  失败补偿计入 FAILED 不占配额、归属方视图隔离。
- `SandboxLifecycleE2ETest`（业务: 端到端，2 例）：通过真实 HTTP 接口
  申请(1s TTL) → 使用中查询 → 平台自动回收为 RELEASED → 总览与配额同步；
  非法/超限 TTL 经真实接口返回 422 且不占配额。

> 到期链路全部使用秒级 TTL（端到端用例把扫描间隔调到 200ms），单测内几秒即可走完整条链路，
> 无需长时间等待。

### 启动服务并手工验证

```bash
# 本地验证可把扫描间隔调小，便于快速看到自动回收
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=18080 --sandbox.expiry.scan-interval-ms=1000"

# 申请（用 3 秒 TTL，很快就能观察到自动回收；重复执行返回同一 id，验证幂等）
curl -X POST localhost:18080/api/sandboxes -H 'Content-Type: application/json' \
  -d '{"owner":"team-a","idempotencyKey":"demo-1","ttlSeconds":3}'

# 几秒后查询，状态应已自动变为 RELEASED
curl localhost:18080/api/sandboxes/<id>

# 续期 / 手工回收（把 <id> 换成上一步返回的 id）
curl -X POST localhost:18080/api/sandboxes/<id>/renew -H 'Content-Type: application/json' \
  -d '{"operator":"team-a","extendSeconds":600}'
curl -X POST localhost:18080/api/sandboxes/<id>/release -H 'Content-Type: application/json' \
  -d '{"operator":"team-a"}'

# 归属方名下总览
curl 'localhost:18080/api/sandboxes/overview?owner=team-a'
```

## 兼容范围

- 原有申请 / 续期 / 回收 / 查询接口的路径、请求体与成功响应保持不变；
  `SandboxService` 的原有三参构造器保留，既有调用方式不受影响。
- 新增能力均为增量：自动回收调度器、TTL 校验（`INVALID_TTL` / `TTL_LIMIT_EXCEEDED` /
  `SANDBOX_EXPIRED`）、总览接口与配置项；未开启任何新依赖，存储与配置器仍为内存实现。
- 行为明确化的一点：对已过期环境的续期请求现在会惰性触发自动回收并返回 410
  （此前过期但未扫描的窗口期续期会从过期时刻重新计时，属于未定义行为，现收敛为明确结果）。

## 代码结构

```
src/main/java/.../domain         沙箱实体、状态枚举、错误码
src/main/java/.../statemachine   状态机（迁移表 + 校验）
src/main/java/.../quota          按归属方的配额管理（CAS 计数）
src/main/java/.../provision      配置器接口与本地模拟实现（支持失败注入）
src/main/java/.../service        申请/续期/回收/到期扫描/总览核心逻辑（分段锁、幂等、补偿）
src/main/java/.../reaper         到期自动回收调度器（间隔可配）
src/main/java/.../controller     REST 接口与统一异常映射
```
