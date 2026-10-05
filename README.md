# 临时环境（沙箱）申请与回收服务

平台侧的临时环境编排服务：环境从**申请 → 配置 → 就绪 → 回收**全流程状态可预期、结论可复现，
归属方（团队/租户）维度有配额与归属校验，申请支持失败补偿与幂等，全部基于内存实现，**本地即可跑通验证**。

- 技术栈：Java 21 + Spring Boot 3.4 + JUnit 5，无外部中间件依赖
- 包根：`com.github.feng111627.ephemeralsandboxorchestratorjava.sandbox`

---

## 1. 状态机

```
                 provision success
        PENDING ───────────────────► READY
           │                           │
           │ provision failure         │ recycle（归属方 / 过期扫描）
           ▼                           ▼
        FAILED ─────────────────────► RECYCLED
                                   （终态，回收幂等）
```

| 迁移 | 触发 | 说明 |
| --- | --- | --- |
| (new) → `PENDING` | 申请落库 | 创建即占一个配额 |
| `PENDING` → `READY` | 底层资源配置成功 | 写入到期时间 `expiresAt` |
| `PENDING` → `FAILED` | 配置抛错或返回失败 | 记录 `failReason`，**立即补偿释放配额** |
| `READY` → `RECYCLED` | 主动回收 / 过期回收 | 清空到期时间，**释放配额** |
| `FAILED` → `RECYCLED` | 对失败单做终结归档 | 配额此前已释放，不重复归还 |
| `RECYCLED` → `RECYCLED` | 重复回收 | **幂等成功**，状态不变、配额不再动 |

被拒绝的非法迁移（均返回错误码 `ILLEGAL_STATE_TRANSITION`，HTTP 409）：

- 对非 `PENDING` 沙箱执行 `markReady` / `markFailed`；
- 对 `RECYCLED`（或 `FAILED`）沙箱**续期**——只有 `READY` 可续；
- 对 `PENDING` 沙箱**回收**（配置尚未结束）。

> 注：`PENDING` 是申请调用内部的瞬时态（同步配置），正常情况下对外只会看到
> `READY`（HTTP 200）或 `FAILED`（HTTP 422，已补偿）。

### 续期规则

- 仅归属方可对 `READY` 沙箱续期；在现有到期时间（或当前时间）基础上延长；
- 到期时间钳制在 `now + sandbox.ttl.max-seconds`（默认 7200s）以内。

---

## 2. 配额与归属规则

- 配额按归属方独立计数（`sandbox.quota.default-limit`，默认每方 3 个）；
- 申请流程：**先占配额 → 落库 PENDING → 配置**。配额不足直接拒绝
  （`QUOTA_EXCEEDED`，HTTP 409），此时尚未创建任何环境，可安全重试；
- 配置失败：沙箱置 `FAILED`，并通过沙箱上的 `holdsQuota` CAS 标记
  **恰好释放一次配额**，不残留占用；
- 回收 `READY` 沙箱：同样由 `holdsQuota` 把关，配额**归还一次**；
  重复回收 / 重复过期扫描不会重复归还；`QuotaService` 自身也保证计数不跌破 0；
- 续期、回收都校验归属方，不匹配返回 `NOT_OWNER`（HTTP 403），状态与配额不变；
  系统过期回收以无操作人身份执行。

---

## 3. 申请幂等与失败补偿

- 每个申请必须带 `requestId`；服务端保存其与 `sandboxId` 的绑定和请求指纹
  （`owner|spec|ttl`）；
- 同一 `requestId` 的**并发/重复提交**由 requestId 串行锁 + 原子绑定收敛，
  **只创建一个沙箱、只占一个配额**，重复调用返回同一个沙箱；
- 同 `requestId` 但 `owner/spec/ttl` 不一致 → `IDEMPOTENCY_CONFLICT`（HTTP 409）；
- 中断后重放：直接返回已有沙箱——`READY` / `FAILED` / `RECYCLED` 都不会被复活或重建；
  失败单重放仍是同一个 `FAILED` 沙箱，不重新占配额；换新 `requestId` 可正常重试。

并发一致性保障：

- 申请：`requestId` 维度互斥锁；
- 续期 / 回收 / 过期回收：`sandboxId` 维度分段锁，"读—状态迁移—存"整体原子；
- 状态迁移方法与配额释放标记在 `Sandbox` 实体内均为 `synchronized`。

---

## 4. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/sandboxes` | 申请沙箱 |
| GET  | `/api/sandboxes/{id}` | 查询状态 |
| POST | `/api/sandboxes/{id}/renew` | 续期（归属方） |
| POST | `/api/sandboxes/{id}/recycle` | 回收（归属方；空体表示系统回收） |

错误码 → HTTP 状态：

| 错误码 | HTTP | 场景 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | 缺字段、TTL 非法（≤0 或超过上限） |
| `SANDBOX_NOT_FOUND` | 404 | 沙箱不存在 |
| `NOT_OWNER` | 403 | 非归属方操作 |
| `QUOTA_EXCEEDED` | 409 | 超出归属方配额 |
| `ILLEGAL_STATE_TRANSITION` | 409 | 非法状态迁移 |
| `IDEMPOTENCY_CONFLICT` | 409 | 同 requestId 参数冲突 |

申请失败（资源配置失败、已完成补偿）返回 **422**，响应体仍给出 `state=FAILED`
与 `failReason`，结论可见、可复现。

### spec 约定（模拟配置器）

`SimulatedProvisioner` 用确定性约定模拟底层资源，失败与耗时都可复现：

- `image=nginx`：镜像名（默认 `demo:latest`）；
- `fail=true`：确定性配置失败；
- `delayMillis=200`：配置耗时 200ms，用于制造并发交错 / 申请中断。

---

## 5. 本地运行

要求 JDK 21+、Maven 3.6+。

```bash
mvn spring-boot:run
# 若 8080 被占用：
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=18080"
```

### curl 示例

```bash
# 申请（成功 -> READY）
curl -s -X POST http://localhost:8080/api/sandboxes \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"demo-1","owner":"team-a","spec":"image=nginx","ttlSeconds":600}'

# 同 requestId 重放：返回同一个沙箱
curl -s -X POST http://localhost:8080/api/sandboxes \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"demo-1","owner":"team-a","spec":"image=nginx","ttlSeconds":600}'

# 非归属方回收：403 NOT_OWNER
curl -i -X POST http://localhost:8080/api/sandboxes/sb-demo-1/recycle \
  -H 'Content-Type: application/json' -d '{"requester":"team-b"}'

# 配置失败：422 FAILED（配额已归还）
curl -s -X POST http://localhost:8080/api/sandboxes \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"demo-fail","owner":"team-a","spec":"fail=true","ttlSeconds":600}'

# 归属方回收 / 查询
curl -s -X POST http://localhost:8080/api/sandboxes/sb-demo-1/recycle \
  -H 'Content-Type: application/json' -d '{"requester":"team-a"}'
curl -s http://localhost:8080/api/sandboxes/sb-demo-1
```

过期回收：定时任务按 `sandbox.reaper.interval-ms`（默认 15s）扫描，
也可在单测里直接调用 `sandboxService.recycleExpired()` 验证。

---

## 6. 测试与验证方法

```bash
mvn test                       # 全量
mvn test -Dtest=StateMachineTest          # 仅状态机
mvn test -Dtest=QuotaOwnershipTest        # 仅配额/归属
mvn test -Dtest=IdempotencyTest           # 仅幂等/补偿
mvn test -Dtest=ConcurrencyTest           # 仅并发/过期
mvn test -Dtest=SandboxApiIntegrationTest # 仅 HTTP 端到端
```

测试日志会先打印**业务模块**（取自测试类 `@DisplayName`），再逐用例打印
"用例开始 / 用例结束"以及关键业务结论（如配额计数、拒绝错误码），
可直接看出覆盖了哪些业务、每条用例在验证什么。

当前覆盖（共 26 个业务用例 + 1 个 Spring 上下文用例）：

| 测试类 | 业务 | 关键边界 |
| --- | --- | --- |
| `StateMachineTest`（6） | 状态流转 | READY/RECYCLED 正常迁移、重复 markReady 拒绝、RECYCLED 续期拒绝、回收幂等、续期延长与上限封顶 |
| `QuotaOwnershipTest`（7） | 配额与归属 | 第 4 个申请拒绝、回收后配额可复用、重复回收只归还一次、配置失败不残留、租户隔离、非归属方拒绝、404 |
| `IdempotencyTest`（6） | 幂等与补偿 | 重复提交收敛、参数指纹冲突、失败重放不重建不占配额、换单重试、回收后重放不复活、参数校验 |
| `ConcurrencyTest`（4） | 并发一致性 | 20 线程同 requestId 只建一个沙箱、续期/回收 30 轮竞争结论唯一、4 线程抢 3 配额恰好 1 拒、TTL 过期回收与重复扫描 |
| `SandboxApiIntegrationTest`（3） | HTTP 端到端 | 全生命周期、422 失败补偿、错误码到 HTTP 状态映射 |

---

## 7. 代码结构

```
sandbox/
├── domain/          Sandbox（状态机 + holdsQuota CAS）、SandboxRepository
├── exception/       ErrorCode、SandboxException（可区分拒绝原因）
├── quota/           QuotaService（归属方原子配额计数）
├── provision/       SandboxProvisioner / SimulatedProvisioner（确定性失败/延迟注入）
├── repo/            InMemorySandboxRepository（双索引 + 按 id 分段锁）
├── service/         SandboxService（编排核心）、IdempotencyStore、ExpiredSandboxReaper
└── web/             SandboxController、GlobalExceptionHandler、dto
```

> 说明：仓储、配额、幂等记录均为进程内内存实现，面向本地验证；
> 多实例部署时需替换为支持原子 CAS / 唯一约束的持久化实现，状态机与补偿语义保持不变。
