# 海峡金 OA 审批系统 —— 后端服务

基于 **Spring Boot 3.2.5 + Flowable 7.0.0 + MyBatis-Plus 3.5.9 + MySQL 8.0** 的审批系统后端。

配合前端原型（`docs/design/海峡金OA审批系统原型.html`）改造使用：原型保留作为 UI 验证，把里面的 mock 数据替换为本服务的真实接口。

---

## 1. 已实现范围（首批 3 种单据闭环）

| 单据类型 | 流程链路 | 状态 |
|---|---|---|
| 日常付款申请 | 发起 → 直属部门负责人 → 会计（按部门） → **[金额≥2万]** 公司领导 → 出纳付款 | ✅ 已跑通 |
| 员工报销 | 发起 → 直属部门负责人 → 会计（按部门） → **[金额≥1万]** 财务总监 → 出纳付款 | ✅ 已配置 |
| 用印申请 | 发起 → 直属部门负责人 → 综合管理部 → **[公章/合同章]** 公司领导 → 用印办理 | ✅ 已配置 |

**端到端验证：53 项断言全部通过**（`scripts/api_smoke_test.py`），覆盖登录鉴权、流程部署、BPMN 生成、节点动态指派、条件分支、逐级审批、办结、数据范围、驳回/重提/撤回。

---

## 2. 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **17** | Spring Boot 3.2 与 Flowable 7 的要求。注意：**必须用 JDK 17**，JDK 21+ 会与 Lombok 冲突（见「已知问题」） |
| Maven | 3.8+ | |
| MySQL | **8.0+** | 需要递归 CTE、窗口函数、JSON 类型；5.7 不满足 |

---

## 3. 快速启动

```bash
# ---- 1. 建库 ----
mysql -uroot -e "DROP DATABASE IF EXISTS haixiajin_oa; \
  CREATE DATABASE haixiajin_oa DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

# ---- 2. 业务表（29 张）+ 种子数据 ----
mysql -uroot haixiajin_oa < sql/schema.sql
mysql -uroot haixiajin_oa < sql/seed_data.sql

# ---- 3. Flowable 引擎表（41 张 ACT_*；必须先建，原因见「已知问题」）----
mysql -uroot haixiajin_oa < sql/flowable_schema_mysql.sql

# ---- 4. 编译（务必用 JDK 17）----
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -pl oa-boot -am install -DskipTests

# ---- 5. 启动 ----
java -jar oa-boot/target/oa-boot-1.0.0-SNAPSHOT.jar --server.port=8080

# ---- 6. 冒烟测试（另开终端）----
python3 scripts/api_smoke_test.py http://127.0.0.1:8080
```

或在项目根目录一键执行：

```bash
./scripts/init_and_run.sh
```

### 已有存量库升级

若库是早期版本，执行幂等迁移脚本（可重复执行）：

```bash
mysql -uroot haixiajin_oa < sql/migration_20260918_flowable.sql
```

---

## 4. 测试账号（密码统一 `123456`）

| 账号 | 姓名 | 部门 | 角色 | 数据范围 | 在流程中的角色 |
|---|---|---|---|---|---|
| `admin` | 系统管理员 | 综合管理中心 | ADMIN | 全公司 | 流程/表单配置 |
| `zhangzong` | 张总 | 总经理室 | GM | 全公司 | 大额审批（≥2万） |
| `lifinance` | 李财务 | 财务中心 | FIN_DIRECTOR | 全公司 | 报销终审（≥1万） |
| `wangkj` | 王会计 | 财务核算部 | ACCOUNTANT | 全公司 | 按部门核算 |
| `zhaocs` | 赵出纳 | 资金结算部 | CASHIER | 全公司 | 付款办理 |
| `chennk` | 陈内控 | 内控合规中心 | INTERNAL_CTRL | 全公司 | 内控核验 |
| `linjl` | 林经理 | 业务一部 | DEPT_HEAD | 本部门 | 部门审批 |
| `zhouzh` | 周综合 | 综合管理中心 | DEPT_HEAD | 本部门 | 用印办理 |
| `huangxm` | 黄小明 | 业务一部 | EMPLOYEE | 仅本人 | 申请人 |

> 建议的体验路径：用 `huangxm` 发起一笔 5 万元的日常付款，然后依次用 `linjl` → `wangkj` → `zhangzong` → `zhaocs` 登录审批（金额≥2万才会出现张总这一节点）。

---

## 5. 接口清单

统一响应体：`{ "code": 0, "msg": "success", "data": ..., "timestamp": ... }`，`code != 0` 即失败。
鉴权：请求头 `Authorization: Bearer <token>`。

### 认证 `/api/auth`
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/login` | 登录，返回 token + 用户信息 + 菜单 |
| GET | `/me` | 当前登录用户（含角色/权限/数据范围） |
| GET | `/menus` | 可见菜单 |
| GET | `/permissions` | 权限点全量（按钮级控制用） |
| POST | `/logout` | 登出 |

### 组织与系统 `/api/users` `/api/depts` `/api/roles` `/api/dicts`
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/users` | 用户列表 |
| GET | `/api/users/roles` | 角色列表 |
| GET | `/api/depts/tree` | 部门树（含负责人） |
| GET | `/api/roles` | 角色列表 |
| GET | `/api/dicts` | 全量字典（按类型分组） |
| GET | `/api/dicts/{type}` | 按类型取字典 |

### 动态表单 `/api/forms`
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/schema?docTypeId=&nodeKey=` | 取按节点裁剪后的表单 Schema（含字段可见/可编辑） |
| POST | `/validate?docTypeId=` | 服务端二次校验（提交前预检） |

### 单据 `/api/documents`
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/types` | 单据类型清单 |
| POST | `` | 保存草稿 |
| PUT | `/{id}` | 更新草稿 |
| POST | `/{id}/submit` | 提交审批（校验 + 启动流程） |
| POST | `/{id}/withdraw` | 撤回 |
| GET | `/{id}` | 详情（含流转记录、字段权限、可执行动作） |
| GET | `` | 分页列表（受数据范围约束） |

### 待办与审批 `/api/todos`
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `` | 我的待办 |
| GET | `/done` | 我已处理 |
| POST | `/approve` | 审批（通过 / 驳回 / 要求补料） |
| POST | `/countersign` | 加签 |

### 流程配置 `/api/flows`
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/configs` | 流程配置列表 |
| GET | `/configs/{id}` | 详情（配置 + 节点 + 指派规则） |
| GET | `/configs/{id}/bpmn` | 查看生成的 BPMN XML |
| POST | `/configs/{id}/deploy` | 部署到 Flowable 引擎 |
| POST | `/configs/{id}/preview` | **流程预览：算出每个节点实际由谁审** |
| GET | `/configs/{id}/assignees` | 节点指派规则明细 |

> `/preview` 是给业务方的尺子：上线前先看清「会计（按部门）」到底解析到谁，避免上线才发现没人可审。

---

## 6. 模块结构

```
oa-backend/
├── oa-common/      通用层：统一响应、异常、BaseEntity、当前用户上下文、数据范围 SQL 生成器
├── oa-system/      系统域：公司/部门/岗位、用户、角色、权限、字典、登录认证
├── oa-flow/        流程域：流程配置、Flowable 接入、节点动态指派、BPMN 生成、运行时流转
├── oa-document/    单据域：动态表单、单据发起与查询、审批动作、待办、用印
├── oa-boot/        启动层：装配、认证拦截器、全局异常、CORS、配置
├── sql/            建表与种子脚本
│   ├── schema.sql                    业务表 29 张（含 Flowable 绑定列）
│   ├── seed_data.sql                 组织架构 + 权限 + 3 种单据的完整配置
│   ├── flowable_schema_mysql.sql     Flowable 引擎表 41 张（自动生成，勿手改）
│   ├── migration_20260918_flowable.sql  存量库幂等迁移
│   ├── schema_partition.sql          audit_log 按月分区
│   └── verify_assignee.sql           节点指派规则解析验证
└── scripts/
    ├── api_smoke_test.py       端到端冒烟测试（53 项断言）
    ├── gen_flowable_ddl.py     重新生成 Flowable 建表脚本
    └── init_and_run.sh         一键初始化并启动
```

---

## 7. 核心设计

### 7.1 Flowable 与业务表如何共存（引擎状态投影）

```
业务配置层                   生成/部署              引擎运行时            业务查询投影
flow_config          ───────────────▶   BPMN 2.0 XML   ─────▶  ACT_* 表  ─────▶  flow_instance
flow_config_node     (BpmnGenerator)    (Flowable)     (60 张)  (监听器投影)  flow_instance_node
flow_node_assignee                                                          （给前端查待办/台账）
```

- `ACT_*` 表由引擎独占，应用**不直接查**；
- `flow_instance.proc_inst_id` 是业务表与引擎表之间**唯一的桥接键**；
- 待办、台账、流程时间线全部读业务投影表，将来换引擎不影响上层业务代码。

### 7.2 审批人不写死在 BPMN 里

BPMN 的每个 `userTask` 只挂一个监听器：

```xml
<flowable:taskListener event="create" delegateExpression="${assigneeTaskListener}"/>
```

由 `AssigneeTaskListener` 按 `flow_node_assignee` 表里的**规则**在运行时解析出人来。支持 6 种规则：

| rule_type | 含义 | 解决原型里的什么问题 |
|---|---|---|
| `initiator_leader` | 取发起人部门负责人 | 「直属主管」 |
| `dept_role` | 部门 + 角色（可 `fallback: company`） | 「会计（**按部门**）」 |
| `biztype_role` | 业务类型映射角色 | 「会计（按业务类型）」 |
| `role` | 指定角色 | 「公司领导」「出纳」 |
| `user` | 指定人 | 用印办理人 |
| `condition` | 满足表达式才生效 | 「大额」等条件触发 |

**收益：改规则只需改表，不必重新生成、重新部署流程。**

另外内置两条硬规则：
- 解析结果自动剔除申请人本人（**不能自己审自己**）；
- 某节点解析不出任何人时，记录一条「自动跳过」后继续推进，**不让流程卡死**。

### 7.3 状态机闭合

```
草稿(0) ──submit──▶ 待审批(1) ──▶ 审批中(2) ──全部通过──▶ 已通过(3) ──▶ 已归档(6)
                        │                          │
                        │                          └──驳回───▶ 已驳回(4) ──改后重新提交──▶ 审批中(2)
                        └──撤回───▶ 已撤回(5) ──重新提交──▶ 审批中(2)
```

由 `FlowLifecycleListener` 监听流程事件回写单据状态，**杜绝原型里「approve 只弹消息不改状态」导致台账永远停在审批中**的问题。

### 7.4 权限四层

| 层 | 实现 |
|---|---|
| 功能权限 | `sys_permission.code`（英文编码）+ `role_permission`，中文仅做展示 |
| 数据权限（行级） | `DataScopeHelper` 生成 SQL 片段 + `role_data_scope`；**MySQL 无 RLS，拦截器是唯一防线** |
| 字段权限（列级） | `form_field_permission`，按「模板 + 节点 + 字段」裁剪 Schema |
| 节点权限 | `flow_node_assignee` 运行时动态指派 |

---

## 8. 已知问题与踩坑记录

### 8.1 Flowable 7.0.0 首次启动会失败（**必读**）

**现象**：

```
FlowableException: couldn't upgrade db schema:
  insert into ACT_GE_PROPERTY values ('common.schema.version', '6.2.0.0', 1)
Table 'haixiajin_oa.act_ge_property' doesn't exist
```

**原因**：Flowable 7.0.0 把 `eventregistry` 改为用 Liquibase 管理 schema，而它的前置检查会先读 `ACT_GE_PROPERTY` 判断 common schema 是否就绪；当表不存在时判定逻辑走错分支，执行 `insert` 而不是建表。

**解决**（本项目已采用）：先用 `sql/flowable_schema_mysql.sql` 预建 41 张引擎表，再启动应用。该脚本由 `scripts/gen_flowable_ddl.py` 从 Maven 依赖的官方 DDL 自动汇总，并按「建表 → 索引 → 数据」三段式重排（官方脚本中索引与建表混排，存在跨文件依赖，例如 `engine.sql` 会给 `variable.sql` 创建的表建索引）。

启动成功后 Liquibase 会自行补齐 `FLW_EVENT_*` 等表，属正常现象。

### 8.2 必须用 JDK 17

Maven 若跑在 JDK 25 上，Lombok 1.18.30 会报：

```
Fatal error compiling: java.lang.ExceptionInInitializerError: com.sun.tools.javac.code.TypeTag :: UNKNOWN
```

解决：`export JAVA_HOME=$(/usr/libexec/java_home -v 17)`。

### 8.3 数据范围 SQL 片段不能自带 `AND`

MyBatis-Plus 的 `QueryWrapper.apply()` 会**自动用 AND 连接**片段。若 `DataScopeHelper` 返回值再带 `AND` 前缀，会拼出 `AND AND`，导致 JSqlParser 解析失败、分页 count SQL 被改写成畸形语句：

```
... AND applicant_id = 9) ORDER BY created_at DESC) TOTAL
```

约定：`buildClause()` **返回不含前导 AND 的片段，无限制时返回 `null`**，调用方判空后再 `apply`。

### 8.4 本机 Python 测试脚本要绕过代理

环境里的 `HTTP_PROXY` 会劫持对 `127.0.0.1` 的请求，报 `upstream connect failed`。`api_smoke_test.py` 已内置 `ProxyHandler({})`。

---

## 9. 配置项

| 配置 | 默认值 | 说明 |
|---|---|---|
| `server.port` | 8080 | |
| `spring.datasource.*` | `127.0.0.1:3306/haixiajin_oa` | 账号密码可用 `MYSQL_USER` / `MYSQL_PASSWORD` 覆盖 |
| `oa.jwt.secret` | 内置默认值 | **生产必须用 `OA_JWT_SECRET` 环境变量注入，长度 ≥ 32 字节** |
| `oa.jwt.expire-minutes` | 720 | token 有效期 |
| `flowable.database-schema-update` | `true` | 生产建议改 `false`，改用 SQL 脚本手工建表 |

---

## 10. 下一步待办

- [ ] **业务方确认**：3 种单据的审批链、节点指派规则、金额阈值、权限矩阵需逐项确认（可基于 `/api/flows/configs/{id}/preview` 核对）
- [ ] 文件上传（附件走 MinIO/OSS，`attachment` 表已就绪）
- [ ] 通知渠道：当前只有站内信，邮件/短信待接
- [ ] 超时预警与升级（`sla_hours` / `deadline` 字段已就绪，缺定时任务）
- [ ] 用印归还闭环（`seal_apply.return_status` + `seal_record` 已就绪）
- [ ] 剩余 47 种单据类型的表单模板与流程配置
- [ ] 权限变更即时生效（当前授权信息编进 JWT，变更需重新登录；生产建议改为「token 只带 userId + Redis 存授权快照」）
- [ ] 前端原型改造：把 mock 数据替换为本服务接口
