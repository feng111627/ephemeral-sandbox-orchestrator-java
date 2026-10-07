# 临时环境（沙箱）申请与回收服务

平台侧的临时环境编排服务：支持环境从申请、配置、就绪到回收的完整状态流转，
按归属方（团队/租户）做配额与归属校验，申请过程具备失败补偿与幂等能力。
到期环境由平台自动回收；有效期有可配置的上下限；并提供归属方名下总览查询。
全部基于内存存储与模拟配置器，本地即可运行验证。

## 状态机

```
                 ┌──────────── 失败 ────────────┐
                 ▼                              │
 REQUESTED ──► PROVISIONING ──► READY ──► RELEASING ──► RELEASED
    │               │                                        （终态）
    └───────┬───────┘
            ▼
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

## 错误码

| 错误码 | HTTP | 含义 |
|---|---|---|
| SANDBOX_NOT_FOUND | 404 | 环境不存在 |
| ILLEGAL_STATE_TRANSITION | 409 | 非法状态迁移（如回收后续期） |
| QUOTA_EXCEEDED | 429 | 超出归属方配额 |
| NOT_OWNER | 403 | 非归属方操作（续期/回收） |
| PROVISION_FAILED | 500 | 配置失败（已自动补偿） |
| TTL_EXCEEDED | 400 | 有效期/续期超过平台允许的最大时限 |
| BAD_REQUEST | 400 | 入参非法（如 ttlSeconds 为 0 或负数） |

## 有效期与续期边界

- **有效期上限本地可配**：`sandbox.ttl.max-seconds`（默认 86400 秒 = 24 小时）。
- **申请校验（先校验、后占配额）**：`ttlSeconds` 必须是正数（> 0），且不超过上限。
  - 0 / 负数 / 空：`400 BAD_REQUEST`；
  - 超过上限：`400 TTL_EXCEEDED`；
  - 拒绝发生在占用配额**之前**：不占用任何配额、不创建半成品环境、不残留幂等索引，
    用同一幂等键换成合法 TTL 可立即重新申请。
- **绝对上限**：环境创建时即固定 `maxExpiresAt = createdAt + 最大时限`，随实体可查。
- **续期边界**：续期后的到期时间按 `max(当前到期时间, 现在) + 续期时长` 计算
  （已过期的环境续期从当前时刻起算）。
  - 续期时长必须为正数，否则 `400 BAD_REQUEST`；
  - 只要续期后的到期时间越过 `maxExpiresAt`，**整笔续期被拒绝**（`400 TTL_EXCEEDED`），
    到期时间保持不变——不做静默截断，不允许通过续期把到期时间无限往后推；
  - 恰好续到 `maxExpiresAt`（贴边）允许；续到顶后再续任意正数时长都会被拒。

## 到期自动回收

- 平台周期性扫描（`ExpiryReaper`，间隔 `sandbox.reaper.fixed-delay-ms`，默认 1000ms；
  可用 `sandbox.reaper.enabled=false` 关闭），自动回收**已过到期时间且仍为 READY** 的环境。
- 自动回收与人工回收走**同一段收尾逻辑**（同一把沙箱分段锁）：
  RELEASED 是可查询、可预期的终态；`quotaHeld` 标记保证配额**恰好归还一次**，
  自动回收与人工回收撞车也不会重复还、不会漏还；销毁动作只执行一次。
- 自动回收与续期并发时结果只有两种一致状态：
  - 续期先持锁：到期时间延后到未来，本轮扫描不再回收，环境继续可用；
  - 回收先持锁：环境置 RELEASED、配额归还，随后的续期被明确拒绝
    （`409 ILLEGAL_STATE_TRANSITION`）。
- 到期前（精确到毫秒边界）不会误收；扫描本身幂等，重复扫描无副作用。

## 归属方总览

`GET /api/sandboxes?owner=<团队/租户>` 返回名下快照：

| 字段 | 含义 |
|---|---|
| `active` | 当前仍在使用：REQUESTED / PROVISIONING / READY / RELEASING |
| `ended` | 已结束：RELEASED（含到期自动回收、人工回收）/ FAILED |
| `usedQuota` | 当前已占用配额 |
| `quotaLimit` | 该归属方配额上限 |
| `remainingQuota` | 剩余可用配额（申请前可据此判断够不够用） |

列表按创建时间排序；不同归属方严格隔离；无记录的归属方返回空列表与满额可用。

## 配额与回收规则

- 配额按归属方（owner，团队/租户）维度计数，默认上限 3（`sandbox.quota.default-limit` 可配），
  可通过 `QuotaManager.setLimit` 为单个归属方设置独立上限。
- 申请成功即占用 1 个配额；超限申请被拒绝（`QUOTA_EXCEEDED`），不产生任何占用。
- 配额归还恰好一次：沙箱实体携带 `quotaHeld` 标记，只有持有配额的环境在
  回收（RELEASED）或失败补偿（FAILED）时才归还，重复回收是幂等 no-op，
  不会重复归还或漏还。
- 归属校验：续期与回收仅归属方可操作，非归属方操作被拒绝（`NOT_OWNER`）且不改变状态。

## 幂等与失败补偿

- **申请幂等**：以 `owner + idempotencyKey` 为幂等键。重复提交或中断后重放返回同一环境，
  不重复占用配额、不重复触发配置；并发提交同一幂等键只产生一个环境。
- **失败补偿**：配置任一步骤失败时，环境置为 FAILED、配额立即归还、幂等索引清除，
  不残留占用；客户端可用同一幂等键安全重试，会创建新环境。
- **并发一致性**：按 key 哈希的分段锁将同一幂等键 / 同一沙箱上的操作串行化。
  续期与回收并发时结果一致：回收成功后配额必然归还，续期要么先于回收生效，
  要么被明确拒绝（`ILLEGAL_STATE_TRANSITION`），不会出现状态错乱或重复占用。

## REST 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/sandboxes | 申请环境 `{owner, idempotencyKey, ttlSeconds}` |
| POST | /api/sandboxes/{id}/renew | 续期 `{operator, extendSeconds}` |
| POST | /api/sandboxes/{id}/release | 回收 `{operator}`（幂等） |
| GET | /api/sandboxes/{id} | 查询环境 |
| GET | /api/sandboxes?owner={owner} | 归属方名下总览（使用中/已结束/配额） |

## 配置项（本地可配）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `sandbox.quota.default-limit` | 3 | 归属方默认配额上限（也可编程调用 `QuotaManager.setLimit`） |
| `sandbox.ttl.max-seconds` | 86400 | 有效期绝对上限（秒），申请与续期都受其约束 |
| `sandbox.reaper.enabled` | true | 是否启用到期自动回收 |
| `sandbox.reaper.fixed-delay-ms` | 1000 | 自动回收扫描间隔（毫秒） |

## 兼容范围

- 原有接口路径、请求/响应字段、错误码与状态机迁移规则保持不变；仅**新增**
  `TTL_EXCEEDED` 错误码与 `GET /api/sandboxes?owner=` 总览接口，以及实体上新增的
  `maxExpiresAt` 只读字段。
- 有效期校验为新增前置约束：默认上限 24h，历史用法（如 ttlSeconds=1800）完全兼容；
  若确需更长有效期，调大 `sandbox.ttl.max-seconds` 即可。
- `SandboxService` 保留原有构造器，旧调用方与旧单测无需改动。

## 本地验证

### 运行单测

```bash
mvn test
```

测试按业务分组，日志中可看到各业务覆盖的用例与结果（形如 `[业务: xxx] 执行用例: ...`）：

- `StateMachineTest`（业务: 状态机）：合法迁移全链路、失败补偿路径迁移、终态无出边、
  非法迁移拒绝且原因可区分。
- `QuotaManagerTest`（业务: 配额）：上限边界、归还不为负、归属方隔离、16 线程并发抢占不超发。
- `SandboxServiceTest`（业务: 沙箱服务）：申请进入 READY、幂等重放、并发重复提交只产生一个环境、
  超配额拒绝、配置失败补偿与安全重试、非归属方拒绝、续期延长、回收后续期拒绝、
  回收幂等且配额恰好归还一次、续期与回收并发 20 轮结果一致。
- `TtlPolicyTest`（业务: 有效期）：申请 TTL 的正数/等于上限/超限边界、
  续期在绝对上限内延长、贴边允许、越界整笔拒绝不截断、已过期从当前时刻起算、非法配置拒绝。
- `AutoReapServiceTest`（业务: 到期自动回收）：用可控时钟 + 2 秒短 TTL 在单测内走完整链路——
  到期自动 RELEASED 并归还配额、毫秒级到期边界不误收、重复扫描与人工再回收幂等、
  自动/人工回收并发 20 轮配额与销毁恰好一次、自动回收/续期并发 30 轮结果只有两种一致状态、
  非法 TTL 拒绝不占配额不留残留、续期越过绝对上限拒绝且到期时间不变。
- `OwnerOverviewTest`（业务: 归属方总览）：active/ended 分类与排序、配额占用/上限/剩余、
  自动回收与 FAILED 计入 ended、归属方隔离、独立上限、空归属方。
- `EndToEndApiTest`（业务: 端到端，真实 HTTP 接口）：申请 → 查询 → 续期 →
  等待平台自动回收 → 终态查询/回收后续期 409/人工再回收幂等 → 总览与配额恢复；
  另含非法 TTL 的 400 拒绝、续期越界 400 且到期时间不变。短 TTL（1~2 秒）即可在秒级走完。

### 启动服务并手工验证

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=18080

# 申请（重复执行返回同一 id，验证幂等）
curl -X POST localhost:18080/api/sandboxes -H 'Content-Type: application/json' \
  -d '{"owner":"team-a","idempotencyKey":"demo-1","ttlSeconds":1800}'

# 续期 / 回收（把 <id> 换成上一步返回的 id）
curl -X POST localhost:18080/api/sandboxes/<id>/renew -H 'Content-Type: application/json' \
  -d '{"operator":"team-a","extendSeconds":600}'
curl -X POST localhost:18080/api/sandboxes/<id>/release -H 'Content-Type: application/json' \
  -d '{"operator":"team-a"}'

# 到期自动回收：申请一个 2 秒的环境，不做人工回收，约 2~3 秒后再查应为 RELEASED
curl -X POST localhost:18080/api/sandboxes -H 'Content-Type: application/json' \
  -d '{"owner":"team-a","idempotencyKey":"ttl-demo","ttlSeconds":2}'
curl localhost:18080/api/sandboxes/<id>

# 归属方名下总览（使用中 / 已结束 / 配额）
curl 'localhost:18080/api/sandboxes?owner=team-a'

# 有效期边界：超上限（默认 86400 秒）返回 400 TTL_EXCEEDED
curl -i -X POST localhost:18080/api/sandboxes -H 'Content-Type: application/json' \
  -d '{"owner":"team-a","idempotencyKey":"bad-ttl","ttlSeconds":999999}'
```

把自动回收调得更激进便于观察：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=18080 --sandbox.ttl.max-seconds=10 --sandbox.reaper.fixed-delay-ms=200"
```

## 代码结构

```
src/main/java/.../domain         沙箱实体（含 maxExpiresAt）、状态枚举、错误码、归属方总览 DTO
src/main/java/.../statemachine   状态机（迁移表 + 校验）
src/main/java/.../quota          按归属方的配额管理（CAS 计数）
src/main/java/.../ttl            有效期策略（TTL 校验、绝对上限、续期边界）
src/main/java/.../expiry         到期自动回收调度器（扫描间隔可配）
src/main/java/.../provision      配置器接口与本地模拟实现（支持失败注入）
src/main/java/.../service        申请/续期/回收/自动回收/总览核心逻辑（分段锁、幂等、补偿）
src/main/java/.../controller     REST 接口与统一异常映射
```
