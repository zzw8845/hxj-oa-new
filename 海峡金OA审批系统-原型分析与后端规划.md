# 海峡金 OA 审批系统 — 原型分析与后端服务规划

> 输入：`docs/design/海峡金OA审批系统原型.html`（Vue 3 + Element Plus 单文件原型，运行期通过 JS 动态注入/覆盖大量模板与逻辑）
> 目标：从原型静态分析出发，梳理业务场景、数据实体、功能模块、接口需求，指出缺失环节，并给出后端开发规划。

---

## 一、原型整体解读

原型是一个**企业内控审批中心**，覆盖海峡金、海峡金供应链两家公司，核心是把"日常付款 / 业务付款 / 员工报销 / 用印申请"四类业务，按可配置的审批流程在线化、留痕化、台账化。

原型代码有几个值得注意的工程特征（直接影响后端设计）：

1. **大量模板在运行期由 JS 注入/覆盖**（第 37–55 行 `insertAdjacentHTML` 与属性改写）。这说明原型在迭代过程中业务规则反复变更（如"盖章"统一改为"用印"、报销拆分为员工报销、单据类型重新分类）。后端必须把这些规则**数据化、可配置**，不能写死在代码里。
2. **状态机不闭合**。原型中 `approve()` / `confirmReject()` 只是 `ElMessage` 提示，并未真实改写 `documents` 的 `status` / `current`，也没有把单据推进到下一节点。这印证了"前端只画了壳，流转逻辑全在后端"的预期。
3. **流程节点是静态数组**（`nodeFlow()` 直接写死人名与意见），没有流程引擎、没有条件分支判定、没有节点指派规则。
4. **权限是前端 mock**（`canApprove` 仅判断 `role` 字符串包含关系），数据范围、字段级权限、操作审计全部缺失。

---

## 二、业务场景梳理

### 2.1 四大业务大类

| 业务大类 | 时效特征 | 单据类型（来自 `dailyQuick`/`businessQuick`/`stampQuick`） | 典型流程特征 |
|---|---|---|---|
| 日常付款 | 无紧急时效 | 闭店、采购申请、低值易耗品领用、费用报销、费用预算、公司发文、固定资产（报废/调拨/入库）、黄金业务开户、借款、客户交易手续费调整、客户结算服务费、提货人变更、业务招待、银行账户管理调整、招待费、差旅费、加班餐费/交通费、检测费、快递费、保险费、平台保证金、电商投流推广费、合作方退款、聚水潭接口费、日常费用（已/未垫付）、租金物业、装修费、技术服务费、广告费、宽带费、税费缴纳、固定资产采购 | 多为"发起人→直属主管→会计/内控→（大额加签）→出纳/办理→抄送" |
| 业务付款 | 有明确付款时效 | 应付款申请、加工费、直销代销结算服务费、供应商货款、员工工资社保公积金个税、备用金、银行贴现利息、低风险贸易业务货款保证金、借款利息 | 节点更短、时效更强、更强调出纳付款回单 |
| 员工报销 | 与对外付款区分 | 由 `reimburseQuick` 承载（原型中被移除区块，但提交逻辑保留） | 员工个人费用，独立于部门付款 |
| 用印申请 | 印章合规 | 非标合同审批及用印、通用审批申请、用印及证照申请 | 涉及公章/合同章/法人章/财务专用章/发票专用章，需归还闭环 |

### 2.2 关键业务动作

- **发起申请**：选择业务大类 → 选具体单据类型 → 填表（含所属公司、对应项目、金额、发票明细、事由）→ 关联前置单据/合同 → 上传附件 → 按配置流程提交。
- **审批**：通过 / 驳回（指定层级）/ 通过但补材料（付款前 / 付款后）/ 加签。
- **用印专项**：用印项目、用印部门、用印时间、用印文件名称、用章类型、用印原因；流程含"内控专员用印→发起人归还/商务归档"。
- **付款后置材料**：勾选"付款后需要补充发票或其他材料，未补齐前流程不可完结"。
- **台账归档**：仅"已通过"单据进入台账，支持多条件查询与导出。

---

## 三、数据实体识别

从原型响应式数据与表单字段反推，后端至少需要以下实体（标注 ★ 的为原型已有字段、☆ 为推断必须补齐）：

### 3.1 单据 Document
- ★ `id` 单据编号（规则：`FK/YY/BX + 年月日 + 4位序号`）
- ★ `title` 申请事项 / 对应项目
- ★ `type` 业务大类（日常付款/业务付款/员工报销/用印申请）
- ★ `applicant` 申请人、★ `company` 所属公司、★ `department` 申请部门
- ★ `amount` 申请金额、★ `invoiceSummary` 简易发票明细、★ `reason` 申请事由
- ★ `current` 当前节点、★ `status` 状态、★ `updated` 更新时间
- ★ `needPostMaterial` 付款后置材料标志
- ★ `nodes` 流程节点列表（→ 拆为 FlowInstanceNode）
- ☆ `docSubType` 单据类型（采购申请/费用报销…，对应 flowConfigs.type）
- ☆ `companyId/deptId` 外键（不能只存字符串）
- ☆ `applicantId` 申请人外键
- ☆ `flowConfigId` 使用的流程配置版本
- ☆ `priority` 紧急度、☆ `deadline` 时效截止、☆ `createdAt/closedAt`

### 3.2 流程实例节点 FlowInstanceNode
- ★ `name` 节点名、★ `state` done/current/wait
- ★ `person` 审批人、★ `time` 操作时间、★ `comment` 审批意见、★ `files` 节点附件
- ☆ `assigneeId` 实际指派人、☆ `nodeOrder` 节点序号、☆ `nodeType`（审批/抄送/分支/办理）
- ☆ `action` 通过/驳回/加签/补材料、☆ `actionTime`、☆ `delegateFrom` 委托来源

### 3.3 流程配置 FlowConfig
- ★ `type` 流程名称（=单据类型）、★ `category` 分类（日常/业务/用章）
- ★ `nodes` 节点列表（→ 拆为 FlowConfigNode）
- ☆ `version` 版本号、☆ `effectiveFrom/effectiveTo` 生效区间
- ☆ `conditions` 分支条件表达式（如"金额≥2万""涉及资金"）

### 3.4 角色 Role
- ★ `name`、★ `department`、★ `post`、★ `scope` 数据范围、★ `members`、★ `permissions`
- ☆ `roleId`、☆ `dataScopeType`（本人/本部门/中心/全公司/全节点）

### 3.5 员工 Person/User
- ★ `name`、★ `jobNo`、★ `account`、★ `password`、★ `department`、★ `post`、★ `position`（角色名）
- ☆ `userId`、☆ `companyId/deptId`、☆ `status`（在职/离职）、☆ `email/phone`

### 3.6 前置单据/合同 LinkCandidate
- ★ `code` 单据编号、★ `contract` 合同编号、★ `name`、★ `applicant`、★ `department`
- ☆ 关联关系表（单据↔前置单据，多对多，含关联类型）

### 3.7 附件 File
- ★ `name`、★ `node` 所属节点、★ `uploader`、★ `time`
- ☆ `fileId`、☆ `ossKey`、☆ `size`、☆ `mimeType`、☆ `businessType`（申请/审批/用印/回单）

### 3.8 缺失但必须的实体
- ☆ **Company** 公司（海峡金/海峡金供应链）
- ☆ **Department** 部门（需层级，支持中心→二级部门）
- ☆ **Post/Position** 岗位（与角色解耦）
- ☆ **SealRecord** 用印记录（印章类型、用印时间、归还状态）
- ☆ **Notification** 通知（待办、风险、抄送）
- ☆ **AuditLog** 审计日志（谁、何时、对哪条单据做了什么）
- ☆ **BudgetAccount** 预算科目（费用预算类单据需要）

---

## 四、功能模块梳理

| 一级模块 | 页面 | 功能要点 |
|---|---|---|
| 首页 | home | 待办统计（已驳回/待我审批/本月已办结）、近7天流程量、流程状态分布、流程透明度提示 |
| 工作台 | work | 按业务区域发起（日常付款/业务付款/用印申请）、快捷提交、我已发起单据列表（搜索、再次提交） |
| 工作看板 | board | 申请总量/审批中/已办结/平均时长、各节点处理效率、单据状态分布、日期范围筛选 |
| 全部表单 | forms | 超级管理员可查/提交/审批所有表单，多条件筛选 |
| 待我审批 | approve | 审批人视图，业务类型/单据类型/申请人/状态筛选，进入审批详情 |
| 台账档案 | archive | 仅已通过单据，申请人/部门/编号/日期组合查询，导出 |
| 权限管理 | permission | 人员管理（增员工含账号密码）、角色管理（部门角色树、配置权限、数据范围、对应成员） |
| 流程管理 | risk | 新增/修改审批流程，节点可输入新节点，按单据类型配置不同流程链 |

### 审批详情（Drawer）子功能
- 全流程时间线（done/current/wait，可穿透查看前置节点附件）
- 附件资料汇总（按节点归类，预览/下载）
- 申请表信息
- 审批操作区：通过 / 驳回（指定层级+原因+补材料） / 通过但补材料（付款前/后） / 加签 / 审批意见输入

---

## 五、潜在接口需求（REST 视角）

### 5.1 认证与权限
- `POST /auth/login`（账号密码，返回 token）
- `GET /auth/me` 当前用户、角色、数据范围
- `POST /auth/switch-role`（原型支持角色切换，需后端校验合法性）
- `GET /users/:id/permissions` 动态权限点

### 5.2 单据
- `GET /documents`（列表，参数：status/businessType/docType/applicant/keyword/roleScope）
- `GET /documents/mine` 我已发起
- `GET /documents/archive` 台账（仅已通过，组合查询）
- `GET /documents/:id` 详情（含 nodes、files、申请表）
- `POST /documents` 提交（body 含 type、company、project、amount、invoiceSummary、reason、needPostMaterial、attachments、linkedDocIds）
- `POST /documents/draft` 保存草稿
- `POST /documents/:id/copy` 再次提交（复制历史单据）
- `GET /documents/code-seed` 生成单据编码（或后端在 POST 时自动生成）
- `POST /documents/archive/export` 台账导出（异步任务 + 下载链接）

### 5.3 审批
- `POST /documents/:id/approve`（body: comment, attachments）
- `POST /documents/:id/reject`（body: targetLevel, reason, materials）
- `POST /documents/:id/supplement-pass`（body: mode 付款前/后, targetRole, materials）
- `POST /documents/:id/countersign`（body: personId, reason）
- `POST /documents/:id/supplement` 提交补充材料（付款后置）
- `POST /documents/:id/seal/return` 用印归还确认

### 5.4 关联
- `GET /documents/link-candidates`（参数：mode=daily/business, keyword；返回前置单据/合同）

### 5.5 附件
- `POST /files` 上传（multipart，关联 documentId/nodeId/businessType）
- `GET /files/:id/preview` 预览（或返回临时签名 URL）
- `GET /files/:id/download` 下载

### 5.6 人员与角色
- `GET /users` 人员列表、`POST /users` 增加员工（账号/密码/部门/岗位/角色）
- `PUT /users/:id` 编辑、`PUT /users/:id/role` 调整角色
- `GET /roles`、`POST /roles`、`PUT /roles/:id`（含 permissions、scope）
- `GET /roles/tree` 部门角色树

### 5.7 流程配置
- `GET /flows`、`POST /flows`、`PUT /flows/:id`（含 nodes、category、conditions）
- `GET /flows/by-doctype?type=采购申请` 按单据类型取当前生效流程

### 5.8 看板与报表
- `GET /stats/home` 首页统计、`GET /stats/board` 看板统计
- `GET /stats/node-efficiency` 各节点效率
- `GET /stats/status-distribution` 状态分布
- `GET /stats/weekly-flow` 近7天流程量

### 5.9 风险预警
- `GET /documents/warnings`（高金额/异常关注，原型 listMode='warning'，amount≥80000）

### 5.10 通知
- `GET /notifications`、`POST /notifications/:id/read`、SSE/WebSocket 推送

---

## 六、缺失或不完整的环节

### 6.1 流程引擎层（最关键缺口）
1. **条件分支无定义**：流程里出现"条件分支""大额""涉及资金""按需"等，但分支判定规则（金额阈值、是否涉及资金、业务类型）完全未建模。
2. **节点指派规则缺失**："会计（按部门）""会计（按业务类型）""直属主管"如何动态解析到具体 userId？部门负责人变更、人员离职如何处理？
3. **抄送语义未定义**：抄送节点是只读通知？是否计入流程时长？能否回执？
4. **加签后流转未定义**：加签人审核完是回原审批人、还是直接进下一节点？加签是否阻塞主流程？
5. **驳回后重提路径**：驳回至"提交人"后，修改重提是新建单据还是复活原单据？是否保留驳回痕迹？
6. **流程版本治理**：流程配置修改后，存量在途单据按旧版还是新版？需版本号 + 生效区间。

### 6.2 状态机不闭合
- 原型 `approve/reject` 不改状态、不推进节点。后端需定义完整状态机：草稿→待审批→审批中→（驳回→待修改→重新提交）→已通过/已驳回→已归档；用印还有"已用印→待归还→已归档"。

### 6.3 数据完整性
1. **公司/部门/岗位实体缺失**：原型只有枚举字符串，无增删改、无层级、无与 HR 同步。
2. **发票未结构化**：`invoiceSummary` 是一段文本，无法支撑税务校验、金额勾稽。
3. **合同实体缺失**：关联合同时引用合同编号，但合同本身不在系统内管理。
4. **预算控制缺失**：费用预算单据提到"预算"，但无预算编制、执行占用、超预算拦截。
5. **多公司数据隔离**：海峡金 vs 海峡金供应链的权限/数据边界未定义。

### 6.4 安全与合规
1. **密码明文存储**（`newPerson.password` 直接 push），需 BCrypt + 强度校验 + 首次登录强制改密。
2. **数据范围未实现**：scope（本人/本部门/中心/全公司）需后端按角色动态拼接 SQL 条件。
3. **字段级权限缺失**：附件、金额是否对所有审批人可见？需细化。
4. **审计日志缺失**：原型宣称"100% 留痕"，但无审计日志存储与查询。
5. **操作防重放/并发**：同一单据多人同时审批、重复提交需乐观锁或分布式锁。

### 6.5 业务闭环缺口
1. **付款后置材料闭环**：勾选后如何阻断完结、超期如何提醒/升级、最终如何解除阻断未定义。
2. **用印归还闭环**：归还确认、超期催办、遗失处理未定义。
3. **出纳付款回单**：银行结果如何回传、与系统状态如何对账未定义。
4. **审批委托/代理**：出差休假时的审批委托机制缺失。

### 6.6 体验与集成
1. **通知体系缺失**：待办红点只是写死数字，无站内信/邮件/移动推送。
2. **超时处理缺失**："临近超时"无超时规则、自动提醒、自动升级。
3. **批量审批缺失**。
4. **外部集成缺失**：财务系统（账务登记）、银行（付款回单）、HR（人员同步）、智能印章柜。

---

## 七、后端服务规划建议

### 7.1 技术选型

| 层 | 选型 | 理由 |
|---|---|---|
| 语言/框架 | **Spring Boot 3 + Java 21**（或 Kotlin） | 企业内控系统，强类型、生态成熟、审计友好 |
| 主库 | **MySQL 8.0+** 或 PostgreSQL | 二者均可支撑；关键差异在 JSON 能力与行级安全，详见 7.4 |
| 缓存/锁 | Redis | 会话、热点配置、分布式锁（防并发审批） |
| 流程引擎 | **Flowable**（BPMN 2.0）或自研轻量引擎 | 条件分支/网关/抄送/加签标准 BPMN 已覆盖；自研则需实现分支+指派+版本 |
| 文件存储 | MinIO / 阿里云 OSS | 私有桶 + 临时签名 URL 预览下载 |
| 消息队列 | RabbitMQ | 审计日志异步落库、通知异步推送、台账导出任务 |
| 搜索 | Elasticsearch | 台账档案全文检索（单号/事项/申请人/附件 OCR） |
| 鉴权 | Spring Security + JWT + 方法级注解 | 权限点细粒度 |

### 7.2 模块划分（与接口对应）

```
用户权限服务   user-service      用户/角色/部门/岗位/数据范围/权限点
流程引擎服务   flow-service      流程定义/实例/节点指派/条件分支/版本/抄送
单据服务       document-service   单据CRUD/编码/关联/状态机/后置材料
审批服务       approval-service   通过/驳回/加签/补材料/委托/超时
附件服务       file-service       上传/预览/下载/版本/权限
用印服务       seal-service       用印申请/印章台账/归还/归档
看板报表服务   stats-service      统计/效率/风险/导出
审计日志服务   audit-service      全操作留痕/查询
通知服务       notify-service     站内信/邮件/推送/待办聚合
集成服务       integration-service 财务系统/银行/HR/印章柜
```

### 7.3 关键设计原则
1. **流程配置数据化**：单据类型→流程映射、节点→指派规则、分支条件表达式全部入库，前端只渲染。
2. **单据状态机集中管理**：在 `document-service` 内聚状态流转，审批服务只发事件不直接改状态。
3. **数据范围拦截器**：基于角色的 `dataScopeType` 自动拼接 SQL 过滤（MyBatis 拦截器或 JPA Specification）。
4. **审计切面**：AOP 切所有写操作，异步写 `audit_log`。
5. **多公司隔离**：所有业务表带 `company_id`，查询层强制注入。
6. **流程版本绑定**：单据提交时快照 `flow_config_id + version`，在途不受配置变更影响。

### 7.4 数据库选型：MySQL 8.0 可行性

**结论：可以用，但必须 MySQL 8.0+（5.7 直接排除），并接受两项妥协。**

针对本系统的关键能力对比：

| 能力 | 本系统用途 | MySQL 8.0 | PostgreSQL |
|---|---|---|---|
| JSON 字段 | 动态表单 Schema、流程节点配置 | 支持 JSON + 生成列索引 | JSONB + GIN，查询更强 |
| 行级安全 RLS | 数据范围兜底 | **无原生支持** | `CREATE POLICY` |
| 递归 CTE | 部门 / 中心层级树 | 8.0 起支持 | 支持 |
| 窗口函数 | 看板统计、效率排名 | 8.0 起支持 | 支持 |
| 全文检索 | 台账检索（本系统走 ES，不依赖 DB） | 可用 ngram | 可用 |
| 运维生态 | 国内 DBA 与云托管 | **更普及**（阿里云 RDS MySQL） | 相对少 |

**MySQL 5.7 直接排除**：无递归 CTE、无窗口函数、JSON 能力弱，组织架构树查询与看板统计会写得很痛苦。

**选择 MySQL 必须做的 5 项适配：**

1. **版本 ≥ 8.0.16**，字符集统一 `utf8mb4` + 排序规则 `utf8mb4_0900_ai_ci`。
2. **数据范围只能靠应用层**：MySQL 无 RLS，务必用 MyBatis 拦截器统一处理，并加一道 SQL 审计插件——扫描未带 `dept_id` / `applicant_id` / `company_id` 条件的单据查询，在 CI 阶段报警拦截。
3. **JSON 查询必须走生成列 + 索引**，禁止裸 `JSON_EXTRACT` 全表扫描：

   ```sql
   ALTER TABLE form_template
     ADD COLUMN doc_type_v VARCHAR(64)
       GENERATED ALWAYS AS (schema->>'$.docType') STORED,
     ADD INDEX idx_doc_type (doc_type_v);
   ```

   更稳的做法：把**高频查询字段（`doc_type`、`company_id`、`department_id`、`status`、`amount`）从 JSON 中提出来做普通列**，JSON 只存低频、展示型配置。
4. **金额用 `DECIMAL(18,2)`**，禁用 FLOAT / DOUBLE（财务精度会出问题）。
5. **大表按时间分区 + 归档**：单据表、审计日志表用 `PARTITION BY RANGE (TO_DAYS(created_at))`，或引入分库分表中间件（ShardingSphere）。

**什么时候必须选 PostgreSQL：**

- 合规硬要求"数据权限必须在数据库层不可绕过"（RLS 成为刚需）
- 需要复杂 JSON 内嵌查询、部分索引、GIN / GiST
- 团队已有 PG 运维能力

**否则，团队 MySQL 栈成熟就选 MySQL 8.0**——认知成本与运维风险更低，本系统的功能需求它完全能覆盖。

---

## 八、开发阶段建议

| 阶段 | 范围 | 交付价值 |
|---|---|---|
| P0 基座 | 用户权限（登录/角色/部门/数据范围）、流程引擎最小可用（线性流程+节点指派）、单据提交与基础审批、附件上传下载 | 跑通"发起→审批→办结"主链路 |
| P1 闭环 | 状态机完整化、驳回重提、台账档案与导出、看板统计、通知（站内信）、审计日志 | 可用、可审、可查 |
| P2 高级审批 | 条件分支、加签、补材料（付款前/后）、超时与升级、批量审批、风险预警 | 覆盖原型全部审批动作 |
| P3 用印与多公司 | 用印申请专项、印章台账、归还闭环、多公司数据隔离、流程版本治理 | 业务全场景 |
| P4 集成与移动 | 财务系统对接（账务登记）、银行回单、HR 同步、移动端审批、推送 | 与企业 IT 生态融合 |

### P0 风险点提示
- 流程引擎选型是最大决策：Flowable 学习曲线高但功能全；自研需先实现分支+指派+版本，否则 P2 会卡。建议 P0 用 Flowable 跑线性流程，P2 再启用网关。
- 数据范围（scope）若不在 P0 做进拦截器，后期改造成本极高，必须前置。
- 单据编码规则（`FK/YY/BX+日期+序号`）需后端集中生成 + 分布式锁防重号。

---

## 九、动态表单设计（关键架构决策）

### 9.1 结论

**必须动态，但不是"全动态"**——采用「**表单模板驱动 + 特殊表单插槽**」的混合模式。

理由：原型有约 50 种单据类型（日常付款、业务付款、员工报销、用印申请四类下各有若干），且业务规则在迭代中反复变更（原型中大量运行期 JS 覆盖模板即为证据）。硬编码表单意味着每加一种单据、每改一个字段都要改前端代码并重新发版，不可持续。

### 9.2 动态的三个层次

| 层次 | 内容 | 原型证据 |
|---|---|---|
| 结构动态 | 字段增删改：付款类有金额/发票明细，用印类有用印项目/用章类型/用印时间 | `submitForm` 与 `.stamp-form` 两套字段 |
| 行为动态 | 字段显隐、校验规则、联动：勾选"付款后置材料"才要求补充时限；非用印类型才显示发票明细 | 原型用 `v-if="!submitForm.type.includes('用印')"` 硬判 |
| 关系动态 | 同一模板在不同流程节点，字段可见性/可编辑性不同；单据类型→模板→流程→权限四元绑定 | 原型无（缺失） |

### 9.3 数据模型

**`form_template` 表单模板表**

| 字段 | 说明 |
|---|---|
| `id` | 主键 |
| `doc_type` | 单据类型（与 `flow_config.type` 一一对应） |
| `name` | 模板名称 |
| `version` | 版本号（单据提交时快照，在途不受变更影响） |
| `schema` | JSONB，字段定义 + 显隐规则 + 校验规则 |
| `status` | 草稿 / 生效 / 废弃 |
| `effective_from` / `effective_to` | 生效区间 |

**渲染器需支持的字段类型枚举**：
`text` / `textarea` / `number` / `money` / `date` / `datetime` / `select` / `multiselect` / `radio` / `checkbox` / `switch` / `attachmentGroup`（附件组）/ `linkDocument`（关联前置单据）/ `company` / `department` / `user` / `invoice` / `slot:xxx`（自定义组件插槽）

### 9.4 Schema 示例

```json
{
  "version": 3,
  "fields": [
    { "key": "company",   "label": "所属公司",     "type": "select",          "source": "dict:company", "required": true },
    { "key": "project",   "label": "对应项目",     "type": "select",          "source": "dict:project", "allowCreate": true, "required": true },
    { "key": "amount",    "label": "申请金额(元)", "type": "money",           "min": 0, "required": true },
    { "key": "invoiceSummary", "label": "简易发票明细", "type": "textarea",   "rows": 2 },
    { "key": "needPostMaterial", "label": "付款后置材料", "type": "switch" },
    { "key": "attachments", "label": "附件与关联资料", "type": "attachmentGroup",
      "rules": ["关联前置单据", "业务证明资料", "发票", "收款信息"] }
  ],
  "rules": [
    { "when": "$.type !== '用印申请'", "require": ["invoiceSummary"] },
    { "when": "$.needPostMaterial === true", "require": ["postMaterialDeadline"] }
  ]
}
```

### 9.5 不应纯动态的部分（用自定义组件插槽）

以下场景字段间强联动、跨表联查或业务语义强，纯 JSON Schema 难以表达，用 `slot:xxx` 挂自定义组件：

- **关联前置单据/合同选择器**（跨表联查 + 数据范围过滤）
- **用印申请**（用印项目/部门/时间/文件名称/用章类型/原因，强业务语义）
- **预算余额实时校验**（需查预算科目占用）
- **发票明细结构化录入**（未来对接税务校验）

### 9.6 与流程/权限的联动（必须一起设计）

- 新增 **`form_field_permission`** 字段级权限表：`(template_id, node_type, field_key, visible, editable)`。
- 审批节点可"补填字段"（如出纳填付款回单号、内控填核验意见）。
- 这是把 OA 做成**低代码审批平台**的核心——表单、流程、权限三者必须统一建模，否则后期每加一种单据都是三处改代码。

### 9.7 服务端必须二次校验

前端按 Schema 渲染的校验**不可信**（可被绕过）。提交时后端必须依据 `form_template.schema` 重新校验字段类型、必填、范围与规则，校验失败返回结构化错误（字段级错误信息回传前端展示）。

---

## 十、权限体系设计（核心）

### 10.1 结论

权限必须动态，且是**四层模型**（功能 / 数据 / 字段 / 节点）。但**最大的坑不是"动态"，而是原型把审批权做成了"角色名匹配"**——这在真实多部门、多人的组织里必然出错。

### 10.2 原型的三个致命问题

**问题一：审批权用角色名 `includes` 节点名**

```js
// 原型第 93 行
canApprove = selected.status 属于 [待审批,审批中]
          && (role === '超级管理员' || selected.current.includes(role))
```

`selected.current` 是节点名（如"核算会计"），`role` 是角色名。用字符串包含判断"这个人该不该审这张单"，在真实场景必然失效：
- "核算会计"是**一个角色、多人**（原型 `roleConfigs` 中核算会计有 10 人），不是一个人；
- 流程里写的是"会计（**按部门**）""会计（**按业务类型**）"，需按单据上下文二次解析；
- 角色名与节点名一旦有一字之差（如"内控合规" vs 角色"内控主管/内控专员"），判断即崩。

**问题二：用中文名当权限码**

```js
permissions: ['查看本人表单','提交全部表单','部门负责人审批','出纳审批','上传审批凭证', ...]
```

中文权限名无法细粒度引用、改名即失效、无法国际化、无法做权限点版本管理。**权限点必须是稳定的英文 code，中文只做展示**。

**问题三：权限全在前端 mock，无数据范围拦截**

`scope`（本人/本部门/全公司）只用于展示，没有落到查询上；`newPerson.permissions` 仅存在前端内存。**改前端即可越权**。

### 10.3 四层模型与实现

| 层 | 解决什么 | 实现点 | 数据模型 |
|---|---|---|---|
| 功能权限 | 能否进页面 / 点按钮 / 调 API | 权限点 code + 方法级注解 | `permission(code,name,type)`、`role_permission(role_id,perm_code)` |
| 数据权限 | 能看到哪些单据（**行级**） | MyBatis 拦截器 / JPA Specification 自动拼 where | `role_data_scope(role_id, scope_type, dept_ids, center_ids)` |
| 字段权限 | 字段可见 / 可编辑（**列级**） | 返回前按 `form_field_permission` 裁剪、脱敏 | `form_field_permission(template_id, node_type, field_key, visible, editable)` |
| 节点权限 | 能审批哪个节点（**流程**） | 流程引擎在节点上做 assignment 解析 | `flow_node_assignee(node_id, rule_type, rule_value)` |

### 10.4 核心：审批权的动态指派（与流程引擎的结合点）

节点权限**不是**"用户角色 ∈ 节点允许角色"，而是**运行时按单据上下文解析**：

| 指派规则 `rule_type` | `rule_value` 示例 | 解析结果 |
|---|---|---|
| 发起人主管 | — | 查组织架构，取发起人所在部门负责人 |
| 按部门分派 | 核算会计 + 部门参数 | 从"会计-部门"映射取对应会计（解决原型"会计（按部门）"） |
| 按业务类型分派 | 会计 + 业务类型参数 | 解决原型"会计（按业务类型）" |
| 指定角色 | 内控专员 | 取该角色下的**具体人**（可能多人会签/或签） |
| 指定人 | userId | 直接指派 |
| 条件触发 | 金额 ≥ 20000 | 达阈值才生成该节点（条件网关，解决"公司领导（大额）"） |
| 抄送 | dept:总经办 | 只读通知，不计流程时长与审批时效 |

这解释了原型为何处理不了"会计（按部门）""公司领导（大额）""会计主管&内控（涉及资金）"——**这些都必须由 `flow_node_assignee` 存规则、运行时解析**，而不是硬编码角色名。

### 10.5 数据范围必须是"声明式拦截器"，不能靠手写 where

若每个查询接口手写 where，一定会漏（漏一个就是数据泄露）。应做成声明式：

```java
@DataScope(deptAlias = "d", userAlias = "u")
public Page<Document> listDocuments(DocumentQuery q) { ... }
```

拦截器读取当前用户角色的 `scope_type`，自动拼接：

| scope_type | 自动追加条件 |
|---|---|
| 本人单据 | `applicant_id = :uid` |
| 本部门单据 | `department_id IN (:deptIds)` |
| 所属中心单据 | `center_id IN (:centerIds)`（依赖部门层级） |
| 全公司单据 | 不追加条件 |

若用 PostgreSQL，可配合**行级安全（RLS）**做兜底，形成"应用层拦截器 + 数据库层 RLS"双保险。**若用 MySQL（无原生 RLS），拦截器是唯一防线**，必须辅以强制代码规范、单元测试与 SQL 审计插件（扫描未带数据范围条件的查询并在 CI 报警），详见 7.4。

### 10.6 权限点编码规范

```text
document:view:self      查看本人表单
document:view:dept      查看本部门表单
document:view:all       查看全部表单
document:create         提交单据
document:approve        审批（节点内）
document:export         导出台账
flow:config             配置流程与权限
seal:apply              用印申请
```

前端按后端返回的 code 集合控制菜单/按钮显隐（展示性隐藏），后端注解强制校验（真实拦截）。二者缺一不可。

### 10.7 四条落地纪律

1. **前端只做展示性隐藏，后端必须强制校验**——所有写操作与查询都要在服务端判权，不能依赖前端。
2. **三者解耦**：角色管功能权限，流程管节点权限，组织架构管数据范围。不要混在一张表里。
3. **组织架构是权限的地基**：部门层级、中心归属、人员-部门-岗位关系必须先建好，否则"数据范围"和"取发起人主管"都无从谈起——这也印证了 6.3 节"部门实体缺失"是最优先要补的。
4. **权限变更即时生效并留痕**：改角色权限后需刷新在线会话的权限缓存（Redis 失效或短 TTL），同时写审计日志。


---

## 十一、实现落地（P0 已跑通）

前面十章是规划。本章记录**实际实现**与规划的对应关系、落地时的关键调整，以及真实跑通的验证结果。

### 11.1 用户已拍板的三个决策

| 决策项 | 结论 | 影响 |
|---|---|---|
| 流程引擎 | **Flowable 7.0.0** | 第七章的「引擎无关」设计成立：`flow_config_*` 保留为业务配置层，Flowable 负责执行 |
| 首批范围 | **3 种单据**：日常付款 / 员工报销 / 用印申请 | 覆盖资金类（含金额分支）、报销类、非资金类（含用印办理）三种形态 |
| 前端 | **沿用现有 Vue3 原型改造**，用于验证后端 | 后端接口按原型的信息结构设计，前端只需替换 mock |

### 11.2 规划 → 实现的对应

| 规划章节 | 实现位置 | 状态 |
|---|---|---|
| 三、数据实体 | `sql/schema.sql` 27 张业务表 | ✅ 已在 MySQL 8.0.46 执行通过 |
| 六、缺失环节 → 流程引擎 | `oa-flow` + Flowable 7 | ✅ 条件分支、节点指派、版本快照全部落地 |
| 六、缺失环节 → 状态机闭合 | `FlowLifecycleListener` | ✅ 驳回/重提/撤回闭环已端到端验证 |
| 九、动态表单 | `form_template` + `form_field_permission` + `FormTemplateService` | ✅ Schema 驱动渲染 + 字段级权限 + 服务端二次校验 |
| 十、权限四层 | `sys_permission`/`role_data_scope`/`form_field_permission`/`flow_node_assignee` | ✅ 四层全部实现 |
| 七、后端规划（微服务 8 个） | **单体多模块 5 个**（common/system/flow/document/boot） | ⚠️ 已调整，见 11.4 |

### 11.3 三个关键架构决策（实现后回填）

**① 引擎状态投影 —— 不直接查 ACT_\* 表**

```
业务配置层                编译             引擎运行时          业务查询投影
flow_config        ──────────────▶  BPMN 2.0 XML   ──▶  ACT_*  ──▶  flow_instance
flow_config_node   (BpmnGenerator)   (Flowable)     (39张)   (监听器)  flow_instance_node
flow_node_assignee                                                   ← 前端只查这两个
```

`flow_instance.proc_inst_id` 是业务表与引擎表之间**唯一桥接键**。待办、台账、流程时间线全部读业务投影表 —— 将来换引擎不影响上层业务代码，这是坚持「引擎无关」设计的回报。

**② 审批人不写进 BPMN，改挂监听器**

BPMN 的 userTask 不写 `flowable:assignee`，只挂：

```xml
<flowable:taskListener event="create" delegateExpression="${assigneeTaskListener}"/>
```

由 `AssigneeTaskListener` 按 `flow_node_assignee` 规则运行时解析。**收益：业务方改「会计按部门」这类规则只需改表，不必重新生成、重新部署流程** —— 这直接回应了第六章「节点指派无解析规则」的缺口。

两条必须内置的硬规则（否则生产必踩）：
1. 解析结果**剔除申请人本人** —— 不能自己审自己；
2. 解析为空时记录「自动跳过」并继续推进（带循环保护）—— 否则那位「自己就是部门负责人」的申请人会把流程卡死。

**③ 状态机靠 Spring 事件闭环**

流程域发布 `FlowLifecycleEvent(STARTED/APPROVED/REJECTED/FINISHED/AUTO_SKIPPED)`，单据域监听并回写状态。这样 `oa-flow` 不必反向依赖 `oa-document`，模块边界干净。

### 11.4 与规划的偏差（务实调整）

| 规划 | 实际 | 原因 |
|---|---|---|
| 微服务 8 个服务 | 单体多模块 5 个 | 首批 3 种单据、单团队规模下，单体多模块的**部署与调试成本远低于微服务**，且模块边界已清晰，将来拆服务只需按模块拆包 |
| PostgreSQL | MySQL 8.0 | 团队栈成熟度优先；代价是失去 RLS 兜底，见第四章「MySQL 无 RLS，拦截器是唯一防线」 |
| Redis 分布式锁防重号 | 先落 `code_sequence` 行锁 + 唯一索引兜底 | 单库场景下行锁已足够；多实例部署前再补 Redis |
| RabbitMQ / ES | 暂未引入 | P0 无异步与全文检索刚需，避免过早引入运维复杂度 |

### 11.5 落地时发现的三个坑（已写入 `oa-backend/README.md` 第 8 节）

1. **Flowable 7.0.0 首次启动必失败**：`eventregistry` 改用 Liquibase 管 schema，前置检查读不到 `ACT_GE_PROPERTY` 时判定逻辑走错分支，执行 `insert` 而非建表。解法是预建 39 张 ACT_* 表；生成 DDL 时必须按「建表 → 索引 → 数据」三段式重排，因为官方脚本索引与建表混排且存在跨文件依赖。
2. **必须 JDK 17**：跑在 JDK 21+ 上 Lombok 会报 `TypeTag :: UNKNOWN`。
3. **MyBatis-Plus 的 `apply()` 会自动加 AND**：数据范围片段若自带 `AND` 前缀，会拼出 `AND AND`，导致 JSqlParser 解析失败、分页 count SQL 畸形 —— 这是**行级数据权限最容易踩的坑**，且症状（分页查询报 SQL 语法错误）与根因（数据范围片段格式）相距很远。

### 11.6 真实验证结果

**端到端冒烟测试 53 项断言全部通过，0 失败**（`oa-backend/scripts/api_smoke_test.py`，真实起服务、真实走流程）：

| 验证维度 | 结果 |
|---|---|
| 认证与鉴权 | 6 个账号登录、BCrypt 校验、错误密码拒绝、未登录拦截 |
| 流程部署与 BPMN | 3 条流程部署成功；生成 XML 含排他网关与 `${amount >= 20000}` 条件、监听器挂载正确 |
| 节点动态指派 | 日常付款 4 个节点分别解析出：林经理(initiator_leader)、王会计(dept_role)、张总(role)、赵出纳(role) |
| 条件分支 | 5 万元单据**跳过/命中**正确的网关分支（流转轨迹含 n2→n3→n5 节点，中间 seq 4 为网关） |
| 全链路审批 | 4 级用**真实待办**驱动，每级分别登录不同账号完成 |
| 状态机 | 办结后单据状态=已通过；驳回 → 已驳回 → 重提 → 审批中 → 撤回 → 已撤回，全程闭合 |
| 数据范围 | 同一时刻：员工可见 12 条、出纳可见 13 条，且员工**看不到他人单据** |
| 越权防护 | 非本人不能提交他人单据、非流程参与者不可见 |

### 11.7 交付物清单

```
oa-backend/
├── README.md                              启动步骤 / 接口清单 / 账号 / 设计说明 / 踩坑记录
├── sql/
│   ├── schema.sql                         27 张业务表（含 Flowable 绑定列）
│   ├── seed_data.sql                      组织架构 + 权限 + 3 种单据完整配置
│   ├── flowable_schema_mysql.sql          39 张 ACT_* 表（自动生成）
│   ├── migration_20260918_flowable.sql    存量库幂等迁移
│   └── verify_assignee.sql                节点指派规则解析验证
├── scripts/
│   ├── api_smoke_test.py                  端到端测试（53 项断言）
│   ├── gen_flowable_ddl.py                重新生成 Flowable 建表脚本
│   └── init_and_run.sh                    一键初始化并启动
├── oa-common/ oa-system/ oa-flow/ oa-document/ oa-boot/
```

### 11.8 下一步

1. **业务方确认**（最大不确定性）：3 种单据的审批链、节点指派规则、金额阈值、权限矩阵需逐项确认 —— 可用 `POST /api/flows/configs/{id}/preview` 输入一笔模拟单据，直接看每个节点解析到谁，比看配置表直观。
2. **前端原型改造**：把 mock 替换为真实接口（接口清单见 README 第 5 节）。
3. 剩余 P1-P4 事项：附件上传、通知渠道、超时预警、用印归还闭环、其余 47 种单据、权限即时生效（JWT → Redis 快照）。
