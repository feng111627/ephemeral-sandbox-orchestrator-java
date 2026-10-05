# 临时环境（沙箱）申请与回收服务

平台侧的临时环境编排服务：支持环境从申请、配置、就绪到回收的完整状态流转，
按归属方（团队/租户）做配额与归属校验，申请过程具备失败补偿与幂等能力。
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

## 本地验证

### 运行单测

```bash
mvn test
```

测试按业务分组，日志中可看到各业务覆盖的用例与结果：

- `StateMachineTest`（业务: 状态机）：合法迁移全链路、失败补偿路径迁移、终态无出边、
  非法迁移拒绝且原因可区分。
- `QuotaManagerTest`（业务: 配额）：上限边界、归还不为负、归属方隔离、16 线程并发抢占不超发。
- `SandboxServiceTest`（业务: 沙箱服务）：申请进入 READY、幂等重放、并发重复提交只产生一个环境、
  超配额拒绝、配置失败补偿与安全重试、非归属方拒绝、续期延长、回收后续期拒绝、
  回收幂等且配额恰好归还一次、续期与回收并发 20 轮结果一致。

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
```

## 代码结构

```
src/main/java/.../domain         沙箱实体、状态枚举、错误码
src/main/java/.../statemachine   状态机（迁移表 + 校验）
src/main/java/.../quota          按归属方的配额管理（CAS 计数）
src/main/java/.../provision      配置器接口与本地模拟实现（支持失败注入）
src/main/java/.../service        申请/续期/回收核心逻辑（分段锁、幂等、补偿）
src/main/java/.../controller     REST 接口与统一异常映射
```
