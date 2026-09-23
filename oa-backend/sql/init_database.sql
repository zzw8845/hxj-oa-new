-- =============================================================================
-- 海峡金 OA 审批系统 — 建库脚本（**只有表结构，不含任何业务种子数据**）
--
--   目标库：MySQL 8.0.16+（5.7 不支持递归 CTE / 窗口函数，禁止使用）
--   库  名：hxj-oa       字符集：utf8mb4 / utf8mb4_0900_ai_ci
--
--   用法：
--     mysql -h <host> -P <port> -u<user> -p --default-character-set=utf8mb4 < init_database.sql
--     （本机：mysql -uroot < init_database.sql）
--
--   内容：① 建库  ② 29 张业务表  ③ 41 张 Flowable 引擎表（ACT_* / FLW_*）
--
--   【幂等】全部为 CREATE DATABASE/TABLE IF NOT EXISTS，重复执行不会报错，也不会动已有数据。
--           但注意：它**不会**升级已存在的表结构。改了 schema 后要更新线上库，
--           仍然需要单独的 ALTER 脚本（例如 sql/migration_*.sql 那种），不要指望重跑本脚本。
-- =============================================================================
--
--  【为什么必须连 Flowable 的 ACT_* 表一起建】
--    Flowable 7.0.0 的 eventregistry 改用 Liquibase 管理 schema，其前置检查会读
--    ACT_GE_PROPERTY 判断 common schema 是否就绪。表不存在时它走错分支，
--    执行的是 insert into ACT_GE_PROPERTY 而不是建表，启动直接失败：
--      FlowableException: couldn't upgrade db schema: insert into ACT_GE_PROPERTY ...
--    也就是说 flowable.database-schema-update=true **不能**替代这个脚本 ——
--    它只在"表已经存在"的前提下才是安全的。先建表是硬前提。
--
--  【关于下面那些 INSERT 不是种子数据】
--    它们是 Flowable 自己的 schema 版本标记（common.schema.version / schema.history 等），
--    少了这些行引擎会认为库没初始化过、直接拒绝启动。业务表（company / sys_user /
--    document / flow_config …）一行数据都不会插。
--
--  【本文件是生成物，不要手改】
--    由 sql/gen_init_database.sh 拼装：schema.sql + flowable_schema_mysql.sql。
--    要改表结构，请改那两个来源文件后重新执行 bash gen_init_database.sh。
-- =============================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `hxj-oa`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE `hxj-oa`;

-- =============================================================================
-- 第 1 部分：业务表（29 张）
--   来源：sql/schema.sql
-- =============================================================================

-- =============================================================================
-- 海峡金 OA 审批系统 — 数据库 Schema
-- 目标数据库 : MySQL 8.0.16+  (5.7 不支持递归 CTE / 窗口函数，禁止使用)
-- 字符集     : utf8mb4 / utf8mb4_0900_ai_ci
-- 存储引擎   : InnoDB
-- -----------------------------------------------------------------------------
-- 设计约定
--   1. 主键统一 `id` BIGINT UNSIGNED AUTO_INCREMENT
--   2. 业务表均含 `company_id`，实现「海峡金 / 海峡金供应链」多公司数据隔离
--   3. 不建物理外键（逻辑关联），便于后续分区与分库分表
--   4. 统一审计字段：created_at / updated_at / created_by / updated_by / deleted
--   5. 金额统一 DECIMAL(18,2)，禁用 FLOAT / DOUBLE（财务精度）
--   6. deleted 为逻辑删除：0=正常 1=已删除
-- -----------------------------------------------------------------------------
-- 域划分
--   组织与用户域 : company department post sys_user user_post
--                  sys_role user_role sys_permission role_permission role_data_scope
--   表单域       : form_template form_field_permission
--   流程配置域   : flow_config flow_config_node flow_node_assignee document_type
--   单据域       : document document_link attachment
--   流程运行时域 : flow_instance flow_instance_node
--   用印域       : seal_apply seal_record
--   支撑域       : sys_dict code_sequence audit_log notification
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;



-- =============================================================================
-- 一、组织与用户域
-- =============================================================================

-- 1. 公司 ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `company` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `code`        VARCHAR(32)  NOT NULL                COMMENT '公司编码，如 HXJ / HXJ-GYL',
  `name`        VARCHAR(64)  NOT NULL                COMMENT '公司名称，如 海峡金',
  `short_name`  VARCHAR(32)  NULL                    COMMENT '公司简称',
  `status`      TINYINT      NOT NULL DEFAULT 1      COMMENT '状态 1启用 0停用',
  `sort_no`     INT          NOT NULL DEFAULT 0      COMMENT '排序号',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by`  BIGINT UNSIGNED NULL                 COMMENT '创建人ID',
  `updated_by`  BIGINT UNSIGNED NULL                 COMMENT '更新人ID',
  `deleted`     TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_company_code` (`code`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='公司';

-- 2. 部门（支持「中心 → 二级部门」层级）----------------------------------------
CREATE TABLE IF NOT EXISTS `department` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `company_id`  BIGINT UNSIGNED NOT NULL                COMMENT '所属公司ID',
  `parent_id`   BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '上级部门ID，0=顶级',
  `code`        VARCHAR(64)  NOT NULL                   COMMENT '部门编码',
  `name`        VARCHAR(64)  NOT NULL                   COMMENT '部门名称',
  `dept_type`   TINYINT      NOT NULL DEFAULT 2         COMMENT '类型 1一级中心 2二级部门',
  `level`       TINYINT      NOT NULL DEFAULT 1         COMMENT '层级深度，从1开始',
  `path`        VARCHAR(512) NOT NULL DEFAULT ''        COMMENT '物化路径，如 /1/5/12/，便于树查询',
  `leader_id`   BIGINT UNSIGNED NULL                    COMMENT '部门负责人用户ID（「取发起人主管」依赖此字段）',
  `status`      TINYINT      NOT NULL DEFAULT 1         COMMENT '状态 1启用 0停用',
  `sort_no`     INT          NOT NULL DEFAULT 0         COMMENT '排序号',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by`  BIGINT UNSIGNED NULL                    COMMENT '创建人ID',
  `updated_by`  BIGINT UNSIGNED NULL                    COMMENT '更新人ID',
  `deleted`     TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dept_company_code` (`company_id`, `code`, `deleted`),
  KEY `idx_dept_parent` (`parent_id`),
  KEY `idx_dept_path` (`path`(191)),
  KEY `idx_dept_company` (`company_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='部门';

-- 3. 岗位 ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `post` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `company_id`  BIGINT UNSIGNED NOT NULL                COMMENT '所属公司ID',
  `code`        VARCHAR(64)  NOT NULL                   COMMENT '岗位编码',
  `name`        VARCHAR(64)  NOT NULL                   COMMENT '岗位名称，如 部门负责人岗/财务核算岗',
  `status`      TINYINT      NOT NULL DEFAULT 1         COMMENT '状态 1启用 0停用',
  `sort_no`     INT          NOT NULL DEFAULT 0         COMMENT '排序号',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by`  BIGINT UNSIGNED NULL                    COMMENT '创建人ID',
  `updated_by`  BIGINT UNSIGNED NULL                    COMMENT '更新人ID',
  `deleted`     TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_post_company_code` (`company_id`, `code`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='岗位';

-- 4. 用户 ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_user` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `company_id`    BIGINT UNSIGNED NOT NULL                COMMENT '所属公司ID',
  `job_no`        VARCHAR(32)  NOT NULL                   COMMENT '工号，如 HXJ001',
  `account`       VARCHAR(64)  NOT NULL                   COMMENT '登录账号',
  `password`      VARCHAR(100) NOT NULL                   COMMENT '密码哈希（BCrypt，禁止明文/可逆加密）',
  `real_name`     VARCHAR(32)  NOT NULL                   COMMENT '姓名',
  `phone`         VARCHAR(20)  NULL                       COMMENT '手机号',
  `email`         VARCHAR(64)  NULL                       COMMENT '邮箱',
  `dept_id`       BIGINT UNSIGNED NULL                    COMMENT '所属部门ID',
  `post_id`       BIGINT UNSIGNED NULL                    COMMENT '主岗位ID',
  `status`        TINYINT      NOT NULL DEFAULT 1         COMMENT '状态 1在职 0离职',
  `pwd_reset_flag` TINYINT     NOT NULL DEFAULT 1         COMMENT '是否需要强制改密 1是 0否',
  `last_login_at` DATETIME     NULL                       COMMENT '最后登录时间',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by`    BIGINT UNSIGNED NULL                    COMMENT '创建人ID',
  `updated_by`    BIGINT UNSIGNED NULL                    COMMENT '更新人ID',
  `deleted`       TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_account` (`account`, `deleted`),
  UNIQUE KEY `uk_user_company_jobno` (`company_id`, `job_no`, `deleted`),
  KEY `idx_user_dept` (`dept_id`),
  KEY `idx_user_company_status` (`company_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户';

-- 5. 用户兼岗（一人多岗时使用）--------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_post` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '用户ID',
  `post_id`     BIGINT UNSIGNED NOT NULL                COMMENT '岗位ID',
  `dept_id`     BIGINT UNSIGNED NOT NULL                COMMENT '该岗位所属部门ID',
  `is_primary`  TINYINT      NOT NULL DEFAULT 0         COMMENT '是否主岗 1是 0否',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by`  BIGINT UNSIGNED NULL                    COMMENT '创建人ID',
  `updated_by`  BIGINT UNSIGNED NULL                    COMMENT '更新人ID',
  `deleted`     TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_post` (`user_id`, `post_id`, `dept_id`, `deleted`),
  KEY `idx_user_post_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户兼岗';

-- 6. 角色 ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_role` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `company_id`  BIGINT UNSIGNED NOT NULL                COMMENT '所属公司ID',
  `code`        VARCHAR(64)  NOT NULL                   COMMENT '角色编码，如 ACCOUNTANT / DEPT_LEADER',
  `name`        VARCHAR(64)  NOT NULL                   COMMENT '角色名称，如 核算会计',
  `dept_id`     BIGINT UNSIGNED NULL                    COMMENT '归属部门ID（可空，如「各二级部门」）',
  `post_name`   VARCHAR(64)  NULL                       COMMENT '对应岗位名称（展示用）',
  `is_builtin`  TINYINT      NOT NULL DEFAULT 0         COMMENT '是否内置角色 1是 0否（内置不可删）',
  `status`      TINYINT      NOT NULL DEFAULT 1         COMMENT '状态 1启用 0停用',
  `remark`      VARCHAR(255) NULL                       COMMENT '备注',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by`  BIGINT UNSIGNED NULL                    COMMENT '创建人ID',
  `updated_by`  BIGINT UNSIGNED NULL                    COMMENT '更新人ID',
  `deleted`     TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_company_code` (`company_id`, `code`, `deleted`),
  KEY `idx_role_company` (`company_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色';

-- 7. 用户-角色关联 --------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_role` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     BIGINT UNSIGNED NOT NULL                COMMENT '用户ID',
  `role_id`     BIGINT UNSIGNED NOT NULL                COMMENT '角色ID',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by`  BIGINT UNSIGNED NULL                    COMMENT '创建人ID',
  `deleted`     TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`, `deleted`),
  KEY `idx_user_role_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色关联';

-- 8. 权限点（功能权限，code 为稳定英文标识）------------------------------------
CREATE TABLE IF NOT EXISTS `sys_permission` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `code`        VARCHAR(128) NOT NULL                   COMMENT '权限点编码，如 document:view:all',
  `name`        VARCHAR(64)  NOT NULL                   COMMENT '权限点名称，如 查看全部表单',
  `perm_type`   TINYINT      NOT NULL DEFAULT 3         COMMENT '类型 1菜单 2按钮 3接口',
  `parent_code` VARCHAR(128) NULL                       COMMENT '父权限点编码（菜单树用）',
  `sort_no`     INT          NOT NULL DEFAULT 0         COMMENT '排序号',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_perm_code` (`code`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='权限点';

-- 9. 角色-权限关联 --------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `role_permission` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `role_id`     BIGINT UNSIGNED NOT NULL                COMMENT '角色ID',
  `perm_code`   VARCHAR(128) NOT NULL                   COMMENT '权限点编码',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by`  BIGINT UNSIGNED NULL                    COMMENT '创建人ID',
  `deleted`     TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_perm` (`role_id`, `perm_code`, `deleted`),
  KEY `idx_role_perm_code` (`perm_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色权限关联';

-- 10. 角色数据范围（行级权限，应用层拦截器依据此表拼 where）---------------------
CREATE TABLE IF NOT EXISTS `role_data_scope` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `role_id`       BIGINT UNSIGNED NOT NULL                COMMENT '角色ID',
  `scope_type`    VARCHAR(32)  NOT NULL                   COMMENT '范围类型 self/dept/center/company/custom_dept',
  `company_ids`   JSON         NULL                       COMMENT '限定公司ID集合，null=不限制',
  `dept_ids`      JSON         NULL                       COMMENT '限定部门ID集合（custom_dept 时使用）',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`       TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_scope` (`role_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色数据范围';


-- =============================================================================
-- 二、表单域（动态表单）
-- =============================================================================

-- 11. 表单模板 -----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `form_template` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `company_id`     BIGINT UNSIGNED NULL                    COMMENT '所属公司ID，null=全局通用',
  `doc_type_id`    BIGINT UNSIGNED NOT NULL                COMMENT '单据类型ID',
  `name`           VARCHAR(64)  NOT NULL                   COMMENT '模板名称',
  `version`        INT          NOT NULL DEFAULT 1         COMMENT '版本号（单据提交时快照）',
  `schema_json`    JSON         NOT NULL                   COMMENT '表单 Schema：字段定义 + 显隐规则 + 校验规则',
  `status`         TINYINT      NOT NULL DEFAULT 0         COMMENT '状态 0草稿 1生效 2废弃',
  `effective_from` DATETIME     NULL                       COMMENT '生效开始时间',
  `effective_to`   DATETIME     NULL                       COMMENT '生效结束时间',
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by`     BIGINT UNSIGNED NULL                    COMMENT '创建人ID',
  `updated_by`     BIGINT UNSIGNED NULL                    COMMENT '更新人ID',
  `deleted`        TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_form_tpl` (`doc_type_id`, `version`, `deleted`),
  KEY `idx_form_tpl_status` (`doc_type_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='表单模板';

-- 12. 字段级权限（列级，同模板不同节点字段可见/可编辑不同）----------------------
CREATE TABLE IF NOT EXISTS `form_field_permission` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `template_id` BIGINT UNSIGNED NOT NULL                COMMENT '表单模板ID',
  `node_key`    VARCHAR(64)  NOT NULL                   COMMENT '节点标识，* 表示所有节点',
  `field_key`   VARCHAR(64)  NOT NULL                   COMMENT '字段标识',
  `visible`     TINYINT      NOT NULL DEFAULT 1         COMMENT '是否可见 1是 0否',
  `editable`    TINYINT      NOT NULL DEFAULT 0         COMMENT '是否可编辑 1是 0否',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_field_perm` (`template_id`, `node_key`, `field_key`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='表单字段级权限';


-- =============================================================================
-- 三、流程配置域
-- =============================================================================

-- 13. 单据类型 -----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `document_type` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `company_id`       BIGINT UNSIGNED NULL                    COMMENT '所属公司ID，null=全局',
  `code`             VARCHAR(64)  NOT NULL                   COMMENT '类型编码，如 DAILY_PAYMENT',
  `name`             VARCHAR(64)  NOT NULL                   COMMENT '类型名称，如 采购申请',
  `category`         VARCHAR(32)  NOT NULL                   COMMENT '业务大类 DAILY日常付款/BIZ业务付款/REIMBURSE员工报销/SEAL用印申请',
  `form_template_id` BIGINT UNSIGNED NULL                    COMMENT '默认表单模板ID',
  `flow_config_id`   BIGINT UNSIGNED NULL                    COMMENT '默认流程配置ID',
  `must_link_prev`   TINYINT      NOT NULL DEFAULT 0         COMMENT '是否必须关联前置单据 1是 0否',
  `status`           TINYINT      NOT NULL DEFAULT 1         COMMENT '状态 1启用 0停用',
  `sort_no`          INT          NOT NULL DEFAULT 0         COMMENT '排序号',
  `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by`       BIGINT UNSIGNED NULL                    COMMENT '创建人ID',
  `updated_by`       BIGINT UNSIGNED NULL                    COMMENT '更新人ID',
  `deleted`          TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doctype_code` (`code`, `deleted`),
  KEY `idx_doctype_category` (`company_id`, `category`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单据类型';

-- 14. 流程定义 -----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `flow_config` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `company_id`     BIGINT UNSIGNED NULL                    COMMENT '所属公司ID，null=全局',
  `doc_type_id`    BIGINT UNSIGNED NOT NULL                COMMENT '单据类型ID',
  `name`           VARCHAR(64)  NOT NULL                   COMMENT '流程名称',
  `category`       VARCHAR(32)  NOT NULL                   COMMENT '分类 DAILY/BIZ/SEAL',
  `version`        INT          NOT NULL DEFAULT 1         COMMENT '版本号（单据提交时快照，在途不受变更影响）',
  `status`         TINYINT      NOT NULL DEFAULT 0         COMMENT '状态 0草稿 1生效 2废弃',
  `effective_from` DATETIME     NULL                       COMMENT '生效开始时间',
  `effective_to`   DATETIME     NULL                       COMMENT '生效结束时间',
  -- ↓ Flowable 引擎绑定（引擎无关设计：engine_type=native 时以下字段为空，便于将来换引擎）
  `engine_type`    VARCHAR(16)  NOT NULL DEFAULT 'flowable' COMMENT '流程引擎类型 flowable/native',
  `proc_def_key`   VARCHAR(128) NULL                       COMMENT 'Flowable 流程定义KEY，如 DAILY_PAYMENT_V1',
  `proc_def_id`    VARCHAR(128) NULL                       COMMENT 'Flowable 流程定义ID（形如 key:version:id），部署后回填',
  `deployment_id`  VARCHAR(128) NULL                       COMMENT 'Flowable 部署ID',
  `bpmn_xml`       LONGTEXT     NULL                       COMMENT 'BPMN 2.0 XML 快照（由 flow_config_node 生成，可重部署/可审计）',
  `deploy_status`  TINYINT      NOT NULL DEFAULT 0         COMMENT '部署状态 0未部署 1已部署 2部署失败',
  `deploy_message` VARCHAR(500) NULL                       COMMENT '部署失败原因',
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by`     BIGINT UNSIGNED NULL                    COMMENT '创建人ID',
  `updated_by`     BIGINT UNSIGNED NULL                    COMMENT '更新人ID',
  `deleted`        TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_flow_cfg` (`doc_type_id`, `version`, `deleted`),
  UNIQUE KEY `uk_flow_cfg_procdef` (`proc_def_key`, `deleted`),
  KEY `idx_flow_cfg_status` (`doc_type_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='流程定义（业务配置层，Flowable 的可读投影）';

-- 15. 流程节点定义 -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `flow_config_node` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `flow_config_id`   BIGINT UNSIGNED NOT NULL                COMMENT '流程定义ID',
  `node_key`         VARCHAR(64)  NOT NULL                   COMMENT '节点标识（流程内唯一），如 n1/n2',
  `node_name`        VARCHAR(64)  NOT NULL                   COMMENT '节点名称，如 直属部门负责人',
  `node_type`        TINYINT      NOT NULL DEFAULT 1         COMMENT '节点类型 1审批 2抄送 3条件网关 4办理 5发起',
  `seq_no`           INT          NOT NULL                   COMMENT '节点顺序号',
  `condition_expr`   VARCHAR(1024) NULL                      COMMENT '分支条件表达式（条件网关用）：JSON 数组，如 [{"expr":"doc.amount >= 20000","target":"n4"},{"default":true,"target":"n5"}]',
  `allow_countersign` TINYINT     NOT NULL DEFAULT 0         COMMENT '是否允许加签 1是 0否',
  `allow_reject`     TINYINT      NOT NULL DEFAULT 1         COMMENT '是否允许驳回 1是 0否',
  `require_attachment` TINYINT    NOT NULL DEFAULT 0         COMMENT '该节点办理是否必须上传凭证 1是 0否',
  `sla_hours`        DECIMAL(6,2) NULL                       COMMENT '处理时限（小时），用于超时预警',
  `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`          TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_flow_node` (`flow_config_id`, `node_key`, `deleted`),
  KEY `idx_flow_node_seq` (`flow_config_id`, `seq_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='流程节点定义';

-- 16. 节点指派规则（运行时按单据上下文解析「谁来审」）--------------------------
CREATE TABLE IF NOT EXISTS `flow_node_assignee` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `node_id`       BIGINT UNSIGNED NOT NULL                COMMENT '流程节点定义ID',
  `rule_type`     VARCHAR(32)  NOT NULL                   COMMENT '规则类型 initiator_leader/dept_role/biztype_role/role/user/condition',
  `rule_value`    JSON         NULL                       COMMENT '规则参数，如 {"roleCode":"ACCOUNTANT","byDept":true}',
  `sign_mode`     TINYINT      NOT NULL DEFAULT 1         COMMENT '会签方式 1或签 2会签 3依次审批',
  `sort_no`       INT          NOT NULL DEFAULT 0         COMMENT '同级规则排序',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`       TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  KEY `idx_node_assignee` (`node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='流程节点指派规则';


-- =============================================================================
-- 四、单据域
-- =============================================================================

-- 17. 单据主表 -----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `document` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `doc_no`              VARCHAR(32)  NOT NULL                COMMENT '单据编号（唯一）',
  `company_id`          BIGINT UNSIGNED NOT NULL             COMMENT '所属公司ID',
  `doc_type_id`         BIGINT UNSIGNED NOT NULL             COMMENT '单据类型ID',
  `business_category`   VARCHAR(32)  NOT NULL                COMMENT '业务大类 DAILY/BIZ/REIMBURSE/SEAL',
  `title`               VARCHAR(128) NOT NULL                COMMENT '申请事项 / 对应项目',
  `applicant_id`        BIGINT UNSIGNED NOT NULL             COMMENT '申请人ID',
  `applicant_name`      VARCHAR(32)  NOT NULL                COMMENT '申请人姓名（冗余，便于列表展示）',
  `dept_id`             BIGINT UNSIGNED NOT NULL             COMMENT '申请部门ID',
  `dept_name`           VARCHAR(64)  NOT NULL                COMMENT '申请部门名称（冗余）',
  `amount`              DECIMAL(18,2) NOT NULL DEFAULT 0.00  COMMENT '申请金额（元）',
  `reason`              TEXT         NULL                    COMMENT '申请事由',
  `invoice_summary`     VARCHAR(500) NULL                    COMMENT '简易发票明细',
  `form_data`           JSON         NULL                    COMMENT '动态表单字段值（按 form_template.schema_json 渲染）',
  `form_template_id`    BIGINT UNSIGNED NULL                 COMMENT '表单模板ID',
  `form_template_ver`   INT          NULL                    COMMENT '表单模板版本快照',
  `need_post_material`  TINYINT      NOT NULL DEFAULT 0      COMMENT '是否需要付款后置材料 1是 0否',
  `post_material_status` TINYINT     NOT NULL DEFAULT 0      COMMENT '后置材料状态 0无需 1待补 2已补',
  `status`              TINYINT      NOT NULL DEFAULT 0      COMMENT '状态 0草稿 1待审批 2审批中 3已通过 4已驳回 5已撤回 6已归档',
  `current_node_key`    VARCHAR(64)  NULL                    COMMENT '当前节点标识',
  `current_node_name`   VARCHAR(64)  NULL                    COMMENT '当前节点名称',
  `flow_instance_id`    BIGINT UNSIGNED NULL                 COMMENT '当前流程实例ID',
  `priority`            TINYINT      NOT NULL DEFAULT 0      COMMENT '紧急度 0普通 1紧急',
  `deadline`            DATETIME     NULL                    COMMENT '时效截止时间（用于超时预警）',
  `submitted_at`        DATETIME     NULL                    COMMENT '提交时间',
  `closed_at`           DATETIME     NULL                    COMMENT '办结时间',
  `created_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by`          BIGINT UNSIGNED NULL                 COMMENT '创建人ID',
  `updated_by`          BIGINT UNSIGNED NULL                 COMMENT '更新人ID',
  `deleted`             TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_document_no` (`doc_no`, `deleted`),
  KEY `idx_doc_company_status` (`company_id`, `status`),
  KEY `idx_doc_applicant` (`applicant_id`, `status`),
  KEY `idx_doc_dept` (`dept_id`, `status`),
  KEY `idx_doc_created` (`created_at`),
  KEY `idx_doc_amount` (`company_id`, `amount`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单据主表';

-- 18. 单据关联（关联前置单据 / 合同）-------------------------------------------
CREATE TABLE IF NOT EXISTS `document_link` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `document_id`  BIGINT UNSIGNED NOT NULL                COMMENT '当前单据ID',
  `linked_id`    BIGINT UNSIGNED NOT NULL                COMMENT '被关联单据ID',
  `link_type`    VARCHAR(32)  NOT NULL                   COMMENT '关联类型 prev_doc前置单据/contract合同',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by`   BIGINT UNSIGNED NULL                    COMMENT '创建人ID',
  `deleted`      TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_link` (`document_id`, `linked_id`, `link_type`, `deleted`),
  KEY `idx_doc_link_linked` (`linked_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单据关联';

-- 19. 附件 ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `attachment` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `company_id`    BIGINT UNSIGNED NOT NULL                COMMENT '所属公司ID',
  `document_id`   BIGINT UNSIGNED NULL                    COMMENT '所属单据ID（草稿未提交时可为空）',
  `node_key`      VARCHAR(64)  NULL                       COMMENT '所属流程节点标识',
  `biz_type`      VARCHAR(32)  NOT NULL DEFAULT 'apply'   COMMENT '业务类型 apply申请/approve审批/seal用印/receipt付款回单',
  `file_name`     VARCHAR(255) NOT NULL                   COMMENT '原始文件名',
  `file_key`      VARCHAR(512) NOT NULL                   COMMENT '对象存储 Key（MinIO/OSS）',
  `file_size`     BIGINT       NOT NULL DEFAULT 0         COMMENT '文件大小（字节）',
  `mime_type`     VARCHAR(128) NULL                       COMMENT 'MIME 类型',
  `uploader_id`   BIGINT UNSIGNED NOT NULL                COMMENT '上传人ID',
  `uploader_name` VARCHAR(32)  NULL                       COMMENT '上传人姓名',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by`    BIGINT UNSIGNED NULL                    COMMENT '创建人ID',
  `updated_by`    BIGINT UNSIGNED NULL                    COMMENT '更新人ID',
  `deleted`       TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  KEY `idx_attach_doc` (`document_id`, `biz_type`),
  KEY `idx_attach_node` (`document_id`, `node_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='附件';


-- =============================================================================
-- 五、流程运行时域
-- =============================================================================

-- 20. 流程实例 -----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `flow_instance` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `document_id`         BIGINT UNSIGNED NOT NULL             COMMENT '单据ID',
  `flow_config_id`      BIGINT UNSIGNED NOT NULL             COMMENT '流程定义ID',
  `flow_config_version` INT          NOT NULL                COMMENT '流程定义版本快照',
  `status`              TINYINT      NOT NULL DEFAULT 1      COMMENT '状态 1运行中 2已完成 3已终止',
  `current_node_key`    VARCHAR(64)  NULL                    COMMENT '当前节点标识',
  -- ↓ Flowable 运行实例绑定（业务表 ↔ 引擎 ACT_RU_* 表的唯一桥接键）
  `proc_inst_id`        VARCHAR(64)  NULL                    COMMENT 'Flowable 流程实例ID',
  `business_key`        VARCHAR(64)  NULL                    COMMENT 'Flowable 业务键（= document.doc_no，引擎侧反查单据）',
  -- ↓ 类别快照：审批委托按类别匹配授权时要读它。不冗余存一份的话，授权侧（DelegationService）
  --   就得反查 document → document_type 才能拿到类别，等于在第 5 条硬约束（行级权限只准一份实现）之外
  --   又开一条"我能看见吗"的旁路。快照列是刻意冗余，不是可省的字段。
  `biz_category`        VARCHAR(32)  NULL                    COMMENT '业务类别快照（审批委托的类别匹配要用）',
  `started_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',
  `ended_at`            DATETIME     NULL                    COMMENT '结束时间',
  `created_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`             TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inst_proc` (`proc_inst_id`),
  KEY `idx_inst_doc` (`document_id`),
  KEY `idx_inst_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='流程实例（引擎状态投影）';

-- 21. 节点实例（含待办、审批记录）----------------------------------------------
CREATE TABLE IF NOT EXISTS `flow_instance_node` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `instance_id`   BIGINT UNSIGNED NOT NULL                COMMENT '流程实例ID',
  `document_id`   BIGINT UNSIGNED NOT NULL                COMMENT '单据ID（冗余，便于查询）',
  `node_key`      VARCHAR(64)  NOT NULL                   COMMENT '节点标识',
  `node_name`     VARCHAR(64)  NOT NULL                   COMMENT '节点名称',
  `node_type`     TINYINT      NOT NULL DEFAULT 1         COMMENT '节点类型 1审批 2抄送 3条件网关 4办理 5发起',
  `seq_no`        INT          NOT NULL                   COMMENT '节点顺序号',
  `status`        TINYINT      NOT NULL DEFAULT 0         COMMENT '状态 0待处理 1处理中 2已通过 3已驳回 4已跳过 5已抄送 6已撤回',
  `assignee_id`   BIGINT UNSIGNED NULL                    COMMENT '实际处理人ID',
  `assignee_name` VARCHAR(32)  NULL                       COMMENT '实际处理人姓名',
  `candidate_ids` JSON         NULL                       COMMENT '候选处理人ID集合（会签/或签场景）',
  -- ↓ Flowable 任务绑定
  `task_id`       VARCHAR(64)  NULL                       COMMENT 'Flowable 任务ID（用于认领/完成任务）',
  `node_source`   TINYINT      NOT NULL DEFAULT 1         COMMENT '节点来源 1引擎投影 2手工补录',
  `action`        VARCHAR(32)  NULL                       COMMENT '处理动作 approve/reject/countersign/supplement/cc',
  `comment_text`  VARCHAR(1000) NULL                      COMMENT '审批意见（comment 为保留字，故命名 comment_text）',
  `reject_level`  VARCHAR(32)  NULL                       COMMENT '驳回目标层级（驳回动作）',
  `materials`     VARCHAR(500) NULL                       COMMENT '要求补充的材料说明',
  `deadline`      DATETIME     NULL                       COMMENT '本节点处理时限',
  `action_at`     DATETIME     NULL                       COMMENT '处理时间',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`       TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  KEY `idx_node_inst` (`instance_id`, `seq_no`),
  KEY `idx_node_todo` (`assignee_id`, `status`),
  KEY `idx_node_doc` (`document_id`, `seq_no`),
  KEY `idx_node_task` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='流程节点实例（审批记录/待办投影）';


-- =============================================================================
-- 六、用印域
-- =============================================================================

-- 22. 用印申请 -----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `seal_apply` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `document_id`   BIGINT UNSIGNED NOT NULL                COMMENT '关联单据ID',
  `seal_project`  VARCHAR(128) NOT NULL                   COMMENT '用印项目',
  `seal_dept_id`  BIGINT UNSIGNED NOT NULL                COMMENT '用印部门ID',
  `seal_time`     DATETIME     NULL                       COMMENT '用印时间',
  `file_name`     VARCHAR(255) NULL                       COMMENT '用印文件名称',
  `seal_type`     VARCHAR(32)  NOT NULL                   COMMENT '用章类型 公章/合同章/法人章/财务专用章/发票专用章',
  `seal_reason`   VARCHAR(1000) NULL                      COMMENT '用印原因',
  `return_status` TINYINT      NOT NULL DEFAULT 0         COMMENT '归还状态 0待用印 1已用印 2已归还',
  `return_at`     DATETIME     NULL                       COMMENT '归还时间',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by`    BIGINT UNSIGNED NULL                    COMMENT '创建人ID',
  `updated_by`    BIGINT UNSIGNED NULL                    COMMENT '更新人ID',
  `deleted`       TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_seal_doc` (`document_id`, `deleted`),
  KEY `idx_seal_return` (`return_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用印申请';

-- 23. 用印台账（记录每次用印 / 归还动作）---------------------------------------
CREATE TABLE IF NOT EXISTS `seal_record` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `seal_apply_id` BIGINT UNSIGNED NOT NULL                COMMENT '用印申请ID',
  `document_id`   BIGINT UNSIGNED NOT NULL                COMMENT '单据ID（冗余）',
  `action`        VARCHAR(32)  NOT NULL                   COMMENT '动作 use用印 / return归还',
  `operator_id`   BIGINT UNSIGNED NOT NULL                COMMENT '操作人ID',
  `operator_name` VARCHAR(32)  NULL                       COMMENT '操作人姓名',
  `action_at`     DATETIME     NOT NULL                   COMMENT '操作时间',
  `remark`        VARCHAR(500) NULL                       COMMENT '备注',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by`    BIGINT UNSIGNED NULL                    COMMENT '创建人ID',
  `deleted`       TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  KEY `idx_seal_rec_apply` (`seal_apply_id`, `action`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用印台账';


-- =============================================================================
-- 七、支撑域
-- =============================================================================

-- 24. 数据字典（公司/项目/用章类型等枚举）--------------------------------------
CREATE TABLE IF NOT EXISTS `sys_dict` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `company_id`  BIGINT UNSIGNED NULL                    COMMENT '所属公司ID，null=全局',
  `dict_type`   VARCHAR(64)  NOT NULL                   COMMENT '字典类型 project项目/seal_type用章类型/...',
  `dict_code`   VARCHAR(64)  NOT NULL                   COMMENT '字典项编码',
  `dict_label`  VARCHAR(128) NOT NULL                   COMMENT '字典项名称',
  `sort_no`     INT          NOT NULL DEFAULT 0         COMMENT '排序号',
  `status`      TINYINT      NOT NULL DEFAULT 1         COMMENT '状态 1启用 0停用',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dict` (`dict_type`, `dict_code`, `company_id`, `deleted`),
  KEY `idx_dict_type` (`dict_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据字典';

-- 25. 单据编码序列（防重号，配合分布式锁使用）----------------------------------
CREATE TABLE IF NOT EXISTS `code_sequence` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `company_id`   BIGINT UNSIGNED NOT NULL                COMMENT '公司ID',
  `biz_prefix`   VARCHAR(16)  NOT NULL                   COMMENT '业务前缀 FK/YY/BX',
  `period`       VARCHAR(16)  NOT NULL                   COMMENT '周期，如 20260826（日）或 202608（月）',
  `current_val`  BIGINT       NOT NULL DEFAULT 0         COMMENT '当前序号',
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code_seq` (`company_id`, `biz_prefix`, `period`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单据编码序列';

-- 26. 审计日志（全操作留痕）----------------------------------------------------
CREATE TABLE IF NOT EXISTS `audit_log` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `company_id`  BIGINT UNSIGNED NULL                    COMMENT '公司ID',
  `user_id`     BIGINT UNSIGNED NULL                    COMMENT '操作人ID',
  `user_name`   VARCHAR(32)  NULL                       COMMENT '操作人姓名',
  `module`      VARCHAR(64)  NOT NULL                   COMMENT '模块 document/flow/permission/seal',
  `action`      VARCHAR(64)  NOT NULL                   COMMENT '动作 create/submit/approve/reject/config',
  `biz_id`      BIGINT UNSIGNED NULL                    COMMENT '业务对象ID（如单据ID）',
  `detail`      JSON         NULL                       COMMENT '变更明细',
  `ip`          VARCHAR(64)  NULL                       COMMENT '客户端IP',
  `user_agent`  VARCHAR(255) NULL                       COMMENT 'UA',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_audit_biz` (`biz_id`),
  KEY `idx_audit_user` (`user_id`, `created_at`),
  KEY `idx_audit_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审计日志';

-- 27. 通知 ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `notification` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `company_id`   BIGINT UNSIGNED NULL                    COMMENT '公司ID',
  `receiver_id`  BIGINT UNSIGNED NOT NULL                COMMENT '接收人ID',
  `title`        VARCHAR(128) NOT NULL                   COMMENT '标题',
  `content`      VARCHAR(1000) NULL                      COMMENT '内容',
  `notify_type`  VARCHAR(32)  NOT NULL                   COMMENT '类型 todo待办/cc抄送/risk风险/result结果',
  `biz_type`     VARCHAR(32)  NULL                       COMMENT '业务类型 document/seal',
  `biz_id`       BIGINT UNSIGNED NULL                    COMMENT '业务对象ID',
  `channel`      VARCHAR(16)  NOT NULL DEFAULT 'inner'   COMMENT '渠道 inner站内/mail邮件/sms短信',
  `is_read`      TINYINT      NOT NULL DEFAULT 0         COMMENT '是否已读 1是 0否',
  `read_at`      DATETIME     NULL                       COMMENT '阅读时间',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`      TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  KEY `idx_notify_receiver` (`receiver_id`, `is_read`, `created_at`),
  KEY `idx_notify_biz` (`biz_type`, `biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知';

-- 28. 审批委托（我不在时由谁代办）----------------------------------------------
-- 生效方式是「待办可见 + 审批放行」，**不改写流程的指派结果**：
-- 节点的 assignee 仍是原承办人，流程历史里"谁审的"不会被换人；
-- 受托人凭这张表获得"看见并处理该待办"的资格。
-- 这样做的代价要清楚：委托**不追溯**已有待办之外的历史记录，
-- 但它避免了"指派被改写后，历史记录里承办人含义变味"这类更难解释的问题。
CREATE TABLE IF NOT EXISTS `flow_delegation` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `company_id`    BIGINT UNSIGNED NOT NULL                COMMENT '公司ID',
  `delegator_id`  BIGINT UNSIGNED NOT NULL                COMMENT '委托人（原承办人）',
  `delegate_id`   BIGINT UNSIGNED NOT NULL                COMMENT '受托人（代办人）',
  `biz_category`  VARCHAR(32)  NULL                       COMMENT '限定单据业务类别；NULL=全部',
  `start_at`      DATETIME     NOT NULL                   COMMENT '生效开始',
  `end_at`        DATETIME     NOT NULL                   COMMENT '生效结束',
  `status`        TINYINT      NOT NULL DEFAULT 1         COMMENT '1生效 0已撤销',
  `remark`        VARCHAR(255) NULL                       COMMENT '说明（如"出差两周"）',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by`    BIGINT UNSIGNED NULL                    COMMENT '创建人ID',
  `updated_by`    BIGINT UNSIGNED NULL                    COMMENT '更新人ID',
  `deleted`       TINYINT      NOT NULL DEFAULT 0         COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  KEY `idx_deleg_delegate`  (`delegate_id`, `status`, `start_at`, `end_at`),
  KEY `idx_deleg_delegator` (`delegator_id`, `status`, `start_at`, `end_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审批委托';

-- 29. 超时升级台账（节点超时后把上级加签进来）----------------------------------
-- `uk_escalation_node(node_id, deleted)` 是**幂等的结构性保证**：
-- 一个节点最多一条升级记录，重复执行只会撞唯一键（代码把它当成"已升级过"处理），
-- 比"先查再插"可靠 —— 后者在定时任务与手动触发并发时会重复升级。
CREATE TABLE IF NOT EXISTS `flow_escalation` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `company_id`       BIGINT UNSIGNED NULL                COMMENT '公司ID',
  `document_id`      BIGINT UNSIGNED NOT NULL            COMMENT '单据ID',
  `instance_id`      BIGINT UNSIGNED NULL                COMMENT '流程实例ID',
  `node_id`          BIGINT UNSIGNED NOT NULL            COMMENT 'flow_instance_node.id（唯一键之一）',
  `node_key`         VARCHAR(64)  NULL                   COMMENT '节点标识',
  `node_name`        VARCHAR(64)  NULL                   COMMENT '节点名称',
  `task_id`          VARCHAR(64)  NULL                   COMMENT '引擎任务ID',
  `from_assignee_id` BIGINT UNSIGNED NULL                COMMENT '原承办人',
  `to_assignee_id`   BIGINT UNSIGNED NULL                COMMENT '升级给谁（未找到上级时为空）',
  `status`           TINYINT      NOT NULL DEFAULT 1     COMMENT '1已升级 0未找到上级负责人',
  `reason`           VARCHAR(255) NULL                   COMMENT '说明/未升级原因',
  `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by`       BIGINT UNSIGNED NULL                COMMENT '创建人ID',
  `updated_by`       BIGINT UNSIGNED NULL                COMMENT '更新人ID',
  `deleted`          TINYINT      NOT NULL DEFAULT 0     COMMENT '逻辑删除 0正常 1删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_escalation_node` (`node_id`, `deleted`),
  KEY `idx_escalation_doc` (`document_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='超时升级台账';

SET FOREIGN_KEY_CHECKS = 1;

-- =============================================================================
-- 第 2 部分：Flowable 7.0.0 引擎表（41 张）
--   来源：sql/flowable_schema_mysql.sql（由 scripts/gen_flowable_ddl.py 从官方 DDL 汇总）
-- =============================================================================

-- =============================================================================
-- Flowable 7.0.0 官方 MySQL 建表脚本（从 Maven 依赖的 DDL 自动汇总并重排）
--
-- 为什么需要预建表：
--   Flowable 7.0.0 的 eventregistry 改用 Liquibase 管理 schema，其前置检查会读取
--   ACT_GE_PROPERTY 判断 common schema 是否就绪；表不存在时判定逻辑走错分支，
--   执行 'insert into ACT_GE_PROPERTY' 而非建表，导致启动失败：
--     FlowableException: couldn't upgrade db schema: insert into ACT_GE_PROPERTY ...
--   规避方式：先用本脚本预建全部 ACT_* 表，应用即可正常启动。
--
-- 生成方式：scripts/gen_flowable_ddl.py  （按 建表 → 索引 → 数据 三段式重排，
--           因为官方脚本中索引与建表混排，存在跨文件的前后依赖）
-- 用法：mysql -uroot <库名> < flowable_schema_mysql.sql
-- =============================================================================

-- ========== 第 1 段：建表 ==========
create table ACT_GE_PROPERTY (
    NAME_ varchar(64),
    VALUE_ varchar(300),
    REV_ integer,
    primary key (NAME_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_GE_BYTEARRAY (
    ID_ varchar(64),
    REV_ integer,
    NAME_ varchar(255),
    DEPLOYMENT_ID_ varchar(64),
    BYTES_ LONGBLOB,
    GENERATED_ TINYINT,
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_RE_DEPLOYMENT (
    ID_ varchar(64),
    NAME_ varchar(255),
    CATEGORY_ varchar(255),
    KEY_ varchar(255),
    TENANT_ID_ varchar(255) default '',
    DEPLOY_TIME_ timestamp(3) NULL,
    DERIVED_FROM_ varchar(64),
    DERIVED_FROM_ROOT_ varchar(64),
    PARENT_DEPLOYMENT_ID_ varchar(255),
    ENGINE_VERSION_ varchar(255),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_RE_MODEL (
    ID_ varchar(64) not null,
    REV_ integer,
    NAME_ varchar(255),
    KEY_ varchar(255),
    CATEGORY_ varchar(255),
    CREATE_TIME_ timestamp(3) null,
    LAST_UPDATE_TIME_ timestamp(3) null,
    VERSION_ integer,
    META_INFO_ varchar(4000),
    DEPLOYMENT_ID_ varchar(64),
    EDITOR_SOURCE_VALUE_ID_ varchar(64),
    EDITOR_SOURCE_EXTRA_VALUE_ID_ varchar(64),
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_RU_EXECUTION (
    ID_ varchar(64),
    REV_ integer,
    PROC_INST_ID_ varchar(64),
    BUSINESS_KEY_ varchar(255),
    PARENT_ID_ varchar(64),
    PROC_DEF_ID_ varchar(64),
    SUPER_EXEC_ varchar(64),
    ROOT_PROC_INST_ID_ varchar(64),
    ACT_ID_ varchar(255),
    IS_ACTIVE_ TINYINT,
    IS_CONCURRENT_ TINYINT,
    IS_SCOPE_ TINYINT,
    IS_EVENT_SCOPE_ TINYINT,
    IS_MI_ROOT_ TINYINT,
    SUSPENSION_STATE_ integer,
    CACHED_ENT_STATE_ integer,
    TENANT_ID_ varchar(255) default '',
    NAME_ varchar(255),
    START_ACT_ID_ varchar(255),
    START_TIME_ datetime(3),
    START_USER_ID_ varchar(255),
    LOCK_TIME_ timestamp(3) NULL,
    LOCK_OWNER_ varchar(255),
    IS_COUNT_ENABLED_ TINYINT,
    EVT_SUBSCR_COUNT_ integer, 
    TASK_COUNT_ integer, 
    JOB_COUNT_ integer, 
    TIMER_JOB_COUNT_ integer,
    SUSP_JOB_COUNT_ integer,
    DEADLETTER_JOB_COUNT_ integer,
    EXTERNAL_WORKER_JOB_COUNT_ integer,
    VAR_COUNT_ integer, 
    ID_LINK_COUNT_ integer,
    CALLBACK_ID_ varchar(255),
    CALLBACK_TYPE_ varchar(255),
    REFERENCE_ID_ varchar(255),
    REFERENCE_TYPE_ varchar(255),
    PROPAGATED_STAGE_INST_ID_ varchar(255),
    BUSINESS_STATUS_ varchar(255),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_RE_PROCDEF (
    ID_ varchar(64) not null,
    REV_ integer,
    CATEGORY_ varchar(255),
    NAME_ varchar(255),
    KEY_ varchar(255) not null,
    VERSION_ integer not null,
    DEPLOYMENT_ID_ varchar(64),
    RESOURCE_NAME_ varchar(4000),
    DGRM_RESOURCE_NAME_ varchar(4000),
    DESCRIPTION_ varchar(4000),
    HAS_START_FORM_KEY_ TINYINT,
    HAS_GRAPHICAL_NOTATION_ TINYINT,
    SUSPENSION_STATE_ integer,
    TENANT_ID_ varchar(255) default '',
    ENGINE_VERSION_ varchar(255),
    DERIVED_FROM_ varchar(64),
    DERIVED_FROM_ROOT_ varchar(64),
    DERIVED_VERSION_ integer not null default 0,
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_EVT_LOG (
    LOG_NR_ bigint auto_increment,
    TYPE_ varchar(64),
    PROC_DEF_ID_ varchar(64),
    PROC_INST_ID_ varchar(64),
    EXECUTION_ID_ varchar(64),
    TASK_ID_ varchar(64),
    TIME_STAMP_ timestamp(3) not null DEFAULT CURRENT_TIMESTAMP(3),
    USER_ID_ varchar(255),
    DATA_ LONGBLOB,
    LOCK_OWNER_ varchar(255),
    LOCK_TIME_ timestamp(3) null,
    IS_PROCESSED_ tinyint default 0,
    primary key (LOG_NR_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_PROCDEF_INFO (
	ID_ varchar(64) not null,
    PROC_DEF_ID_ varchar(64) not null,
    REV_ integer,
    INFO_JSON_ID_ varchar(64),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_RU_ACTINST (
    ID_ varchar(64) not null,
    REV_ integer default 1,
    PROC_DEF_ID_ varchar(64) not null,
    PROC_INST_ID_ varchar(64) not null,
    EXECUTION_ID_ varchar(64) not null,
    ACT_ID_ varchar(255) not null,
    TASK_ID_ varchar(64),
    CALL_PROC_INST_ID_ varchar(64),
    ACT_NAME_ varchar(255),
    ACT_TYPE_ varchar(255) not null,
    ASSIGNEE_ varchar(255),
    START_TIME_ datetime(3) not null,
    END_TIME_ datetime(3),
    DURATION_ bigint,
    TRANSACTION_ORDER_ integer,
    DELETE_REASON_ varchar(4000),
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_HI_PROCINST (
    ID_ varchar(64) not null,
    REV_ integer default 1,
    PROC_INST_ID_ varchar(64) not null,
    BUSINESS_KEY_ varchar(255),
    PROC_DEF_ID_ varchar(64) not null,
    START_TIME_ datetime(3) not null,
    END_TIME_ datetime(3),
    DURATION_ bigint,
    START_USER_ID_ varchar(255),
    START_ACT_ID_ varchar(255),
    END_ACT_ID_ varchar(255),
    SUPER_PROCESS_INSTANCE_ID_ varchar(64),
    DELETE_REASON_ varchar(4000),
    TENANT_ID_ varchar(255) default '',
    NAME_ varchar(255),
    CALLBACK_ID_ varchar(255),
    CALLBACK_TYPE_ varchar(255),
    REFERENCE_ID_ varchar(255),
    REFERENCE_TYPE_ varchar(255),
    PROPAGATED_STAGE_INST_ID_ varchar(255),
    BUSINESS_STATUS_ varchar(255),
    primary key (ID_),
    unique (PROC_INST_ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_HI_ACTINST (
    ID_ varchar(64) not null,
    REV_ integer default 1,
    PROC_DEF_ID_ varchar(64) not null,
    PROC_INST_ID_ varchar(64) not null,
    EXECUTION_ID_ varchar(64) not null,
    ACT_ID_ varchar(255) not null,
    TASK_ID_ varchar(64),
    CALL_PROC_INST_ID_ varchar(64),
    ACT_NAME_ varchar(255),
    ACT_TYPE_ varchar(255) not null,
    ASSIGNEE_ varchar(255),
    START_TIME_ datetime(3) not null,
    END_TIME_ datetime(3),
    TRANSACTION_ORDER_ integer,
    DURATION_ bigint,
    DELETE_REASON_ varchar(4000),
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_HI_DETAIL (
    ID_ varchar(64) not null,
    TYPE_ varchar(255) not null,
    PROC_INST_ID_ varchar(64),
    EXECUTION_ID_ varchar(64),
    TASK_ID_ varchar(64),
    ACT_INST_ID_ varchar(64),
    NAME_ varchar(255) not null,
    VAR_TYPE_ varchar(255),
    REV_ integer,
    TIME_ datetime(3) not null,
    BYTEARRAY_ID_ varchar(64),
    DOUBLE_ double,
    LONG_ bigint,
    TEXT_ varchar(4000),
    TEXT2_ varchar(4000),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_HI_COMMENT (
    ID_ varchar(64) not null,
    TYPE_ varchar(255),
    TIME_ datetime(3) not null,
    USER_ID_ varchar(255),
    TASK_ID_ varchar(64),
    PROC_INST_ID_ varchar(64),
    ACTION_ varchar(255),
    MESSAGE_ varchar(4000),
    FULL_MSG_ LONGBLOB,
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_HI_ATTACHMENT (
    ID_ varchar(64) not null,
    REV_ integer,
    USER_ID_ varchar(255),
    NAME_ varchar(255),
    DESCRIPTION_ varchar(4000),
    TYPE_ varchar(255),
    TASK_ID_ varchar(64),
    PROC_INST_ID_ varchar(64),
    URL_ varchar(4000),
    CONTENT_ID_ varchar(64),
    TIME_ datetime(3),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_ID_PROPERTY (
    NAME_ varchar(64),
    VALUE_ varchar(300),
    REV_ integer,
    primary key (NAME_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_ID_BYTEARRAY (
    ID_ varchar(64),
    REV_ integer,
    NAME_ varchar(255),
    BYTES_ LONGBLOB,
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_ID_GROUP (
    ID_ varchar(64),
    REV_ integer,
    NAME_ varchar(255),
    TYPE_ varchar(255),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_ID_MEMBERSHIP (
    USER_ID_ varchar(64),
    GROUP_ID_ varchar(64),
    primary key (USER_ID_, GROUP_ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_ID_USER (
    ID_ varchar(64),
    REV_ integer,
    FIRST_ varchar(255),
    LAST_ varchar(255),
    DISPLAY_NAME_ varchar(255),
    EMAIL_ varchar(255),
    PWD_ varchar(255),
    PICTURE_ID_ varchar(64),
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_ID_INFO (
    ID_ varchar(64),
    REV_ integer,
    USER_ID_ varchar(64),
    TYPE_ varchar(64),
    KEY_ varchar(255),
    VALUE_ varchar(255),
    PASSWORD_ LONGBLOB,
    PARENT_ID_ varchar(255),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_ID_TOKEN (
    ID_ varchar(64) not null,
    REV_ integer,
    TOKEN_VALUE_ varchar(255),
    TOKEN_DATE_ timestamp(3),
    IP_ADDRESS_ varchar(255),
    USER_AGENT_ varchar(255),
    USER_ID_ varchar(255),
    TOKEN_DATA_ varchar(2000),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_ID_PRIV (
    ID_ varchar(64) not null,
    NAME_ varchar(255) not null,
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_ID_PRIV_MAPPING (
    ID_ varchar(64) not null,
    PRIV_ID_ varchar(64) not null,
    USER_ID_ varchar(255),
    GROUP_ID_ varchar(255),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_RU_TASK (
    ID_ varchar(64),
    REV_ integer,
    EXECUTION_ID_ varchar(64),
    PROC_INST_ID_ varchar(64),
    PROC_DEF_ID_ varchar(64),
    TASK_DEF_ID_ varchar(64),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    PROPAGATED_STAGE_INST_ID_ varchar(255),
    NAME_ varchar(255),
    PARENT_TASK_ID_ varchar(64),
    DESCRIPTION_ varchar(4000),
    TASK_DEF_KEY_ varchar(255),
    OWNER_ varchar(255),
    ASSIGNEE_ varchar(255),
    DELEGATION_ varchar(64),
    PRIORITY_ integer,
    CREATE_TIME_ timestamp(3) NULL,
    DUE_DATE_ datetime(3),
    CATEGORY_ varchar(255),
    SUSPENSION_STATE_ integer,
    TENANT_ID_ varchar(255) default '',
    FORM_KEY_ varchar(255),
    CLAIM_TIME_ datetime(3),
    IS_COUNT_ENABLED_ TINYINT,
    VAR_COUNT_ integer,
    ID_LINK_COUNT_ integer,
    SUB_TASK_COUNT_ integer,
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_RU_VARIABLE (
    ID_ varchar(64) not null,
    REV_ integer,
    TYPE_ varchar(255) not null,
    NAME_ varchar(255) not null,
    EXECUTION_ID_ varchar(64),
    PROC_INST_ID_ varchar(64),
    TASK_ID_ varchar(64),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    BYTEARRAY_ID_ varchar(64),
    DOUBLE_ double,
    LONG_ bigint,
    TEXT_ varchar(4000),
    TEXT2_ varchar(4000),
    META_INFO_ varchar(4000),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_RU_IDENTITYLINK (
    ID_ varchar(64),
    REV_ integer,
    GROUP_ID_ varchar(255),
    TYPE_ varchar(255),
    USER_ID_ varchar(255),
    TASK_ID_ varchar(64),
    PROC_INST_ID_ varchar(64),
    PROC_DEF_ID_ varchar(64),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_RU_ENTITYLINK (
    ID_ varchar(64),
    REV_ integer,
    CREATE_TIME_ datetime(3),
    LINK_TYPE_ varchar(255),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    PARENT_ELEMENT_ID_ varchar(255),
    REF_SCOPE_ID_ varchar(255),
    REF_SCOPE_TYPE_ varchar(255),
    REF_SCOPE_DEFINITION_ID_ varchar(255),
    ROOT_SCOPE_ID_ varchar(255),
    ROOT_SCOPE_TYPE_ varchar(255),
    HIERARCHY_TYPE_ varchar(255),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_RU_EVENT_SUBSCR (
    ID_ varchar(64) not null,
    REV_ integer,
    EVENT_TYPE_ varchar(255) not null,
    EVENT_NAME_ varchar(255),
    EXECUTION_ID_ varchar(64),
    PROC_INST_ID_ varchar(64),
    ACTIVITY_ID_ varchar(64),
    CONFIGURATION_ varchar(255),
    CREATED_ timestamp(3) not null DEFAULT CURRENT_TIMESTAMP(3),
    PROC_DEF_ID_ varchar(64),
    SUB_SCOPE_ID_ varchar(64),
    SCOPE_ID_ varchar(64),
    SCOPE_DEFINITION_ID_ varchar(64),
    SCOPE_TYPE_ varchar(64),
    LOCK_TIME_ timestamp(3) NULL,
    LOCK_OWNER_ varchar(255),
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table FLW_RU_BATCH (
    ID_ varchar(64) not null,
    REV_ integer,
    TYPE_ varchar(64) not null,
    SEARCH_KEY_ varchar(255),
    SEARCH_KEY2_ varchar(255),
    CREATE_TIME_ datetime(3) not null,
    COMPLETE_TIME_ datetime(3),
    STATUS_ varchar(255),
    BATCH_DOC_ID_ varchar(64),
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table FLW_RU_BATCH_PART (
    ID_ varchar(64) not null,
    REV_ integer,
    BATCH_ID_ varchar(64),
    TYPE_ varchar(64) not null,
    SCOPE_ID_ varchar(64),
    SUB_SCOPE_ID_ varchar(64),
    SCOPE_TYPE_ varchar(64),
    SEARCH_KEY_ varchar(255),
    SEARCH_KEY2_ varchar(255),
    CREATE_TIME_ datetime(3) not null,
    COMPLETE_TIME_ datetime(3),
    STATUS_ varchar(255),
    RESULT_DOC_ID_ varchar(64),
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_RU_JOB (
    ID_ varchar(64) NOT NULL,
    REV_ integer,
    CATEGORY_ varchar(255),
    TYPE_ varchar(255) NOT NULL,
    LOCK_EXP_TIME_ timestamp(3) NULL,
    LOCK_OWNER_ varchar(255),
    EXCLUSIVE_ boolean,
    EXECUTION_ID_ varchar(64),
    PROCESS_INSTANCE_ID_ varchar(64),
    PROC_DEF_ID_ varchar(64),
    ELEMENT_ID_ varchar(255),
    ELEMENT_NAME_ varchar(255),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    CORRELATION_ID_ varchar(255),
    RETRIES_ integer,
    EXCEPTION_STACK_ID_ varchar(64),
    EXCEPTION_MSG_ varchar(4000),
    DUEDATE_ timestamp(3) NULL,
    REPEAT_ varchar(255),
    HANDLER_TYPE_ varchar(255),
    HANDLER_CFG_ varchar(4000),
    CUSTOM_VALUES_ID_ varchar(64),
    CREATE_TIME_ timestamp(3) NULL,
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_RU_TIMER_JOB (
    ID_ varchar(64) NOT NULL,
    REV_ integer,
    CATEGORY_ varchar(255),
    TYPE_ varchar(255) NOT NULL,
    LOCK_EXP_TIME_ timestamp(3) NULL,
    LOCK_OWNER_ varchar(255),
    EXCLUSIVE_ boolean,
    EXECUTION_ID_ varchar(64),
    PROCESS_INSTANCE_ID_ varchar(64),
    PROC_DEF_ID_ varchar(64),
    ELEMENT_ID_ varchar(255),
    ELEMENT_NAME_ varchar(255),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    CORRELATION_ID_ varchar(255),
    RETRIES_ integer,
    EXCEPTION_STACK_ID_ varchar(64),
    EXCEPTION_MSG_ varchar(4000),
    DUEDATE_ timestamp(3) NULL,
    REPEAT_ varchar(255),
    HANDLER_TYPE_ varchar(255),
    HANDLER_CFG_ varchar(4000),
    CUSTOM_VALUES_ID_ varchar(64),
    CREATE_TIME_ timestamp(3) NULL,
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_RU_SUSPENDED_JOB (
    ID_ varchar(64) NOT NULL,
    REV_ integer,
    CATEGORY_ varchar(255),
    TYPE_ varchar(255) NOT NULL,
    EXCLUSIVE_ boolean,
    EXECUTION_ID_ varchar(64),
    PROCESS_INSTANCE_ID_ varchar(64),
    PROC_DEF_ID_ varchar(64),
    ELEMENT_ID_ varchar(255),
    ELEMENT_NAME_ varchar(255),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    CORRELATION_ID_ varchar(255),
    RETRIES_ integer,
    EXCEPTION_STACK_ID_ varchar(64),
    EXCEPTION_MSG_ varchar(4000),
    DUEDATE_ timestamp(3) NULL,
    REPEAT_ varchar(255),
    HANDLER_TYPE_ varchar(255),
    HANDLER_CFG_ varchar(4000),
    CUSTOM_VALUES_ID_ varchar(64),
    CREATE_TIME_ timestamp(3) NULL,
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_RU_DEADLETTER_JOB (
    ID_ varchar(64) NOT NULL,
    REV_ integer,
    CATEGORY_ varchar(255),
    TYPE_ varchar(255) NOT NULL,
    EXCLUSIVE_ boolean,
    EXECUTION_ID_ varchar(64),
    PROCESS_INSTANCE_ID_ varchar(64),
    PROC_DEF_ID_ varchar(64),
    ELEMENT_ID_ varchar(255),
    ELEMENT_NAME_ varchar(255),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    CORRELATION_ID_ varchar(255),
    EXCEPTION_STACK_ID_ varchar(64),
    EXCEPTION_MSG_ varchar(4000),
    DUEDATE_ timestamp(3) NULL,
    REPEAT_ varchar(255),
    HANDLER_TYPE_ varchar(255),
    HANDLER_CFG_ varchar(4000),
    CUSTOM_VALUES_ID_ varchar(64),
    CREATE_TIME_ timestamp(3) NULL,
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_RU_HISTORY_JOB (
    ID_ varchar(64) NOT NULL,
    REV_ integer,
    LOCK_EXP_TIME_ timestamp(3) NULL,
    LOCK_OWNER_ varchar(255),
    RETRIES_ integer,
    EXCEPTION_STACK_ID_ varchar(64),
    EXCEPTION_MSG_ varchar(4000),
    HANDLER_TYPE_ varchar(255),
    HANDLER_CFG_ varchar(4000),
    CUSTOM_VALUES_ID_ varchar(64),
    ADV_HANDLER_CFG_ID_ varchar(64),
    CREATE_TIME_ timestamp(3) NULL,
    SCOPE_TYPE_ varchar(255),
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_RU_EXTERNAL_JOB (
    ID_ varchar(64) NOT NULL,
    REV_ integer,
    CATEGORY_ varchar(255),
    TYPE_ varchar(255) NOT NULL,
    LOCK_EXP_TIME_ timestamp(3) NULL,
    LOCK_OWNER_ varchar(255),
    EXCLUSIVE_ boolean,
    EXECUTION_ID_ varchar(64),
    PROCESS_INSTANCE_ID_ varchar(64),
    PROC_DEF_ID_ varchar(64),
    ELEMENT_ID_ varchar(255),
    ELEMENT_NAME_ varchar(255),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    CORRELATION_ID_ varchar(255),
    RETRIES_ integer,
    EXCEPTION_STACK_ID_ varchar(64),
    EXCEPTION_MSG_ varchar(4000),
    DUEDATE_ timestamp(3) NULL,
    REPEAT_ varchar(255),
    HANDLER_TYPE_ varchar(255),
    HANDLER_CFG_ varchar(4000),
    CUSTOM_VALUES_ID_ varchar(64),
    CREATE_TIME_ timestamp(3) NULL,
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_HI_ENTITYLINK (
    ID_ varchar(64),
    LINK_TYPE_ varchar(255),
    CREATE_TIME_ datetime(3),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    PARENT_ELEMENT_ID_ varchar(255),
    REF_SCOPE_ID_ varchar(255),
    REF_SCOPE_TYPE_ varchar(255),
    REF_SCOPE_DEFINITION_ID_ varchar(255),
    ROOT_SCOPE_ID_ varchar(255),
    ROOT_SCOPE_TYPE_ varchar(255),
    HIERARCHY_TYPE_ varchar(255),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_HI_IDENTITYLINK (
    ID_ varchar(64),
    GROUP_ID_ varchar(255),
    TYPE_ varchar(255),
    USER_ID_ varchar(255),
    TASK_ID_ varchar(64),
    CREATE_TIME_ datetime(3),
    PROC_INST_ID_ varchar(64),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_HI_TASKINST (
    ID_ varchar(64) not null,
    REV_ integer default 1,
    PROC_DEF_ID_ varchar(64),
    TASK_DEF_ID_ varchar(64),
    TASK_DEF_KEY_ varchar(255),
    PROC_INST_ID_ varchar(64),
    EXECUTION_ID_ varchar(64),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    PROPAGATED_STAGE_INST_ID_ varchar(255),
    NAME_ varchar(255),
    PARENT_TASK_ID_ varchar(64),
    DESCRIPTION_ varchar(4000),
    OWNER_ varchar(255),
    ASSIGNEE_ varchar(255),
    START_TIME_ datetime(3) not null,
    CLAIM_TIME_ datetime(3),
    END_TIME_ datetime(3),
    DURATION_ bigint,
    DELETE_REASON_ varchar(4000),
    PRIORITY_ integer,
    DUE_DATE_ datetime(3),
    FORM_KEY_ varchar(255),
    CATEGORY_ varchar(255),
    TENANT_ID_ varchar(255) default '',
    LAST_UPDATED_TIME_ datetime(3),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_HI_TSK_LOG (
    ID_ bigint auto_increment,
    TYPE_ varchar(64),
    TASK_ID_ varchar(64) not null,
    TIME_STAMP_ timestamp(3) not null,
    USER_ID_ varchar(255),
    DATA_ varchar(4000),
    EXECUTION_ID_ varchar(64),
    PROC_INST_ID_ varchar(64),
    PROC_DEF_ID_ varchar(64),
    SCOPE_ID_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create table ACT_HI_VARINST (
    ID_ varchar(64) not null,
    REV_ integer default 1,
    PROC_INST_ID_ varchar(64),
    EXECUTION_ID_ varchar(64),
    TASK_ID_ varchar(64),
    NAME_ varchar(255) not null,
    VAR_TYPE_ varchar(100),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    BYTEARRAY_ID_ varchar(64),
    DOUBLE_ double,
    LONG_ bigint,
    TEXT_ varchar(4000),
    TEXT2_ varchar(4000),
    META_INFO_ varchar(4000),
    CREATE_TIME_ datetime(3),
    LAST_UPDATED_TIME_ datetime(3),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

-- ========== 第 2 段：索引 ==========
create index ACT_IDX_EXEC_BUSKEY on ACT_RU_EXECUTION(BUSINESS_KEY_);
create index ACT_IDC_EXEC_ROOT on ACT_RU_EXECUTION(ROOT_PROC_INST_ID_);
create index ACT_IDX_EXEC_REF_ID_ on ACT_RU_EXECUTION(REFERENCE_ID_);
create index ACT_IDX_VARIABLE_TASK_ID on ACT_RU_VARIABLE(TASK_ID_);
create index ACT_IDX_ATHRZ_PROCEDEF on ACT_RU_IDENTITYLINK(PROC_DEF_ID_);
create index ACT_IDX_INFO_PROCDEF on ACT_PROCDEF_INFO(PROC_DEF_ID_);
create index ACT_IDX_RU_ACTI_START on ACT_RU_ACTINST(START_TIME_);
create index ACT_IDX_RU_ACTI_END on ACT_RU_ACTINST(END_TIME_);
create index ACT_IDX_RU_ACTI_PROC on ACT_RU_ACTINST(PROC_INST_ID_);
create index ACT_IDX_RU_ACTI_PROC_ACT on ACT_RU_ACTINST(PROC_INST_ID_, ACT_ID_);
create index ACT_IDX_RU_ACTI_EXEC on ACT_RU_ACTINST(EXECUTION_ID_);
create index ACT_IDX_RU_ACTI_EXEC_ACT on ACT_RU_ACTINST(EXECUTION_ID_, ACT_ID_);
create index ACT_IDX_RU_ACTI_TASK on ACT_RU_ACTINST(TASK_ID_);
create index ACT_IDX_HI_PRO_INST_END on ACT_HI_PROCINST(END_TIME_);
create index ACT_IDX_HI_PRO_I_BUSKEY on ACT_HI_PROCINST(BUSINESS_KEY_);
create index ACT_IDX_HI_PRO_SUPER_PROCINST on ACT_HI_PROCINST(SUPER_PROCESS_INSTANCE_ID_);
create index ACT_IDX_HI_ACT_INST_START on ACT_HI_ACTINST(START_TIME_);
create index ACT_IDX_HI_ACT_INST_END on ACT_HI_ACTINST(END_TIME_);
create index ACT_IDX_HI_DETAIL_PROC_INST on ACT_HI_DETAIL(PROC_INST_ID_);
create index ACT_IDX_HI_DETAIL_ACT_INST on ACT_HI_DETAIL(ACT_INST_ID_);
create index ACT_IDX_HI_DETAIL_TIME on ACT_HI_DETAIL(TIME_);
create index ACT_IDX_HI_DETAIL_NAME on ACT_HI_DETAIL(NAME_);
create index ACT_IDX_HI_DETAIL_TASK_ID on ACT_HI_DETAIL(TASK_ID_);
create index ACT_IDX_HI_PROCVAR_PROC_INST on ACT_HI_VARINST(PROC_INST_ID_);
create index ACT_IDX_HI_PROCVAR_TASK_ID on ACT_HI_VARINST(TASK_ID_);
create index ACT_IDX_HI_PROCVAR_EXE on ACT_HI_VARINST(EXECUTION_ID_);
create index ACT_IDX_HI_ACT_INST_PROCINST on ACT_HI_ACTINST(PROC_INST_ID_, ACT_ID_);
create index ACT_IDX_HI_ACT_INST_EXEC on ACT_HI_ACTINST(EXECUTION_ID_, ACT_ID_);
create index ACT_IDX_HI_IDENT_LNK_TASK on ACT_HI_IDENTITYLINK(TASK_ID_);
create index ACT_IDX_HI_IDENT_LNK_PROCINST on ACT_HI_IDENTITYLINK(PROC_INST_ID_);
create index ACT_IDX_HI_TASK_INST_PROCINST on ACT_HI_TASKINST(PROC_INST_ID_);
create index ACT_IDX_PRIV_USER on ACT_ID_PRIV_MAPPING(USER_ID_);
create index ACT_IDX_PRIV_GROUP on ACT_ID_PRIV_MAPPING(GROUP_ID_);
create index ACT_IDX_TASK_CREATE on ACT_RU_TASK(CREATE_TIME_);
create index ACT_IDX_TASK_SCOPE on ACT_RU_TASK(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_TASK_SUB_SCOPE on ACT_RU_TASK(SUB_SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_TASK_SCOPE_DEF on ACT_RU_TASK(SCOPE_DEFINITION_ID_, SCOPE_TYPE_);
create index ACT_IDX_RU_VAR_SCOPE_ID_TYPE on ACT_RU_VARIABLE(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_RU_VAR_SUB_ID_TYPE on ACT_RU_VARIABLE(SUB_SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_IDENT_LNK_USER on ACT_RU_IDENTITYLINK(USER_ID_);
create index ACT_IDX_IDENT_LNK_GROUP on ACT_RU_IDENTITYLINK(GROUP_ID_);
create index ACT_IDX_IDENT_LNK_SCOPE on ACT_RU_IDENTITYLINK(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_IDENT_LNK_SUB_SCOPE on ACT_RU_IDENTITYLINK(SUB_SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_IDENT_LNK_SCOPE_DEF on ACT_RU_IDENTITYLINK(SCOPE_DEFINITION_ID_, SCOPE_TYPE_);
create index ACT_IDX_ENT_LNK_SCOPE on ACT_RU_ENTITYLINK(SCOPE_ID_, SCOPE_TYPE_, LINK_TYPE_);
create index ACT_IDX_ENT_LNK_REF_SCOPE on ACT_RU_ENTITYLINK(REF_SCOPE_ID_, REF_SCOPE_TYPE_, LINK_TYPE_);
create index ACT_IDX_ENT_LNK_ROOT_SCOPE on ACT_RU_ENTITYLINK(ROOT_SCOPE_ID_, ROOT_SCOPE_TYPE_, LINK_TYPE_);
create index ACT_IDX_ENT_LNK_SCOPE_DEF on ACT_RU_ENTITYLINK(SCOPE_DEFINITION_ID_, SCOPE_TYPE_, LINK_TYPE_);
create index ACT_IDX_EVENT_SUBSCR_CONFIG_ on ACT_RU_EVENT_SUBSCR(CONFIGURATION_);
create index ACT_IDX_EVENT_SUBSCR_SCOPEREF_ on ACT_RU_EVENT_SUBSCR(SCOPE_ID_, SCOPE_TYPE_);
create index FLW_IDX_BATCH_PART on FLW_RU_BATCH_PART(BATCH_ID_);
create index ACT_IDX_JOB_EXCEPTION_STACK_ID on ACT_RU_JOB(EXCEPTION_STACK_ID_);
create index ACT_IDX_JOB_CUSTOM_VALUES_ID on ACT_RU_JOB(CUSTOM_VALUES_ID_);
create index ACT_IDX_JOB_CORRELATION_ID on ACT_RU_JOB(CORRELATION_ID_);
create index ACT_IDX_TIMER_JOB_EXCEPTION_STACK_ID on ACT_RU_TIMER_JOB(EXCEPTION_STACK_ID_);
create index ACT_IDX_TIMER_JOB_CUSTOM_VALUES_ID on ACT_RU_TIMER_JOB(CUSTOM_VALUES_ID_);
create index ACT_IDX_TIMER_JOB_CORRELATION_ID on ACT_RU_TIMER_JOB(CORRELATION_ID_);
create index ACT_IDX_TIMER_JOB_DUEDATE on ACT_RU_TIMER_JOB(DUEDATE_);
create index ACT_IDX_SUSPENDED_JOB_EXCEPTION_STACK_ID on ACT_RU_SUSPENDED_JOB(EXCEPTION_STACK_ID_);
create index ACT_IDX_SUSPENDED_JOB_CUSTOM_VALUES_ID on ACT_RU_SUSPENDED_JOB(CUSTOM_VALUES_ID_);
create index ACT_IDX_SUSPENDED_JOB_CORRELATION_ID on ACT_RU_SUSPENDED_JOB(CORRELATION_ID_);
create index ACT_IDX_DEADLETTER_JOB_EXCEPTION_STACK_ID on ACT_RU_DEADLETTER_JOB(EXCEPTION_STACK_ID_);
create index ACT_IDX_DEADLETTER_JOB_CUSTOM_VALUES_ID on ACT_RU_DEADLETTER_JOB(CUSTOM_VALUES_ID_);
create index ACT_IDX_DEADLETTER_JOB_CORRELATION_ID on ACT_RU_DEADLETTER_JOB(CORRELATION_ID_);
create index ACT_IDX_EXTERNAL_JOB_EXCEPTION_STACK_ID on ACT_RU_EXTERNAL_JOB(EXCEPTION_STACK_ID_);
create index ACT_IDX_EXTERNAL_JOB_CUSTOM_VALUES_ID on ACT_RU_EXTERNAL_JOB(CUSTOM_VALUES_ID_);
create index ACT_IDX_EXTERNAL_JOB_CORRELATION_ID on ACT_RU_EXTERNAL_JOB(CORRELATION_ID_);
create index ACT_IDX_JOB_SCOPE on ACT_RU_JOB(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_JOB_SUB_SCOPE on ACT_RU_JOB(SUB_SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_JOB_SCOPE_DEF on ACT_RU_JOB(SCOPE_DEFINITION_ID_, SCOPE_TYPE_);
create index ACT_IDX_TJOB_SCOPE on ACT_RU_TIMER_JOB(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_TJOB_SUB_SCOPE on ACT_RU_TIMER_JOB(SUB_SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_TJOB_SCOPE_DEF on ACT_RU_TIMER_JOB(SCOPE_DEFINITION_ID_, SCOPE_TYPE_);
create index ACT_IDX_SJOB_SCOPE on ACT_RU_SUSPENDED_JOB(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_SJOB_SUB_SCOPE on ACT_RU_SUSPENDED_JOB(SUB_SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_SJOB_SCOPE_DEF on ACT_RU_SUSPENDED_JOB(SCOPE_DEFINITION_ID_, SCOPE_TYPE_);
create index ACT_IDX_DJOB_SCOPE on ACT_RU_DEADLETTER_JOB(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_DJOB_SUB_SCOPE on ACT_RU_DEADLETTER_JOB(SUB_SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_DJOB_SCOPE_DEF on ACT_RU_DEADLETTER_JOB(SCOPE_DEFINITION_ID_, SCOPE_TYPE_);
create index ACT_IDX_EJOB_SCOPE on ACT_RU_EXTERNAL_JOB(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_EJOB_SUB_SCOPE on ACT_RU_EXTERNAL_JOB(SUB_SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_EJOB_SCOPE_DEF on ACT_RU_EXTERNAL_JOB(SCOPE_DEFINITION_ID_, SCOPE_TYPE_);
create index ACT_IDX_HI_ENT_LNK_SCOPE on ACT_HI_ENTITYLINK(SCOPE_ID_, SCOPE_TYPE_, LINK_TYPE_);
create index ACT_IDX_HI_ENT_LNK_REF_SCOPE on ACT_HI_ENTITYLINK(REF_SCOPE_ID_, REF_SCOPE_TYPE_, LINK_TYPE_);
create index ACT_IDX_HI_ENT_LNK_ROOT_SCOPE on ACT_HI_ENTITYLINK(ROOT_SCOPE_ID_, ROOT_SCOPE_TYPE_, LINK_TYPE_);
create index ACT_IDX_HI_ENT_LNK_SCOPE_DEF on ACT_HI_ENTITYLINK(SCOPE_DEFINITION_ID_, SCOPE_TYPE_, LINK_TYPE_);
create index ACT_IDX_HI_IDENT_LNK_USER on ACT_HI_IDENTITYLINK(USER_ID_);
create index ACT_IDX_HI_IDENT_LNK_SCOPE on ACT_HI_IDENTITYLINK(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_HI_IDENT_LNK_SUB_SCOPE on ACT_HI_IDENTITYLINK(SUB_SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_HI_IDENT_LNK_SCOPE_DEF on ACT_HI_IDENTITYLINK(SCOPE_DEFINITION_ID_, SCOPE_TYPE_);
create index ACT_IDX_HI_TASK_SCOPE on ACT_HI_TASKINST(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_HI_TASK_SUB_SCOPE on ACT_HI_TASKINST(SUB_SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_HI_TASK_SCOPE_DEF on ACT_HI_TASKINST(SCOPE_DEFINITION_ID_, SCOPE_TYPE_);
create index ACT_IDX_HI_PROCVAR_NAME_TYPE on ACT_HI_VARINST(NAME_, VAR_TYPE_);
create index ACT_IDX_HI_VAR_SCOPE_ID_TYPE on ACT_HI_VARINST(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_HI_VAR_SUB_ID_TYPE on ACT_HI_VARINST(SUB_SCOPE_ID_, SCOPE_TYPE_);

-- ========== 第 3 段：版本与初始化数据 ==========
insert into ACT_GE_PROPERTY
values ('common.schema.version', '7.0.0.0', 1);
insert into ACT_GE_PROPERTY
values ('next.dbid', '1', 1);
insert into ACT_GE_PROPERTY
values ('schema.version', '7.0.0.0', 1);
insert into ACT_GE_PROPERTY
values ('schema.history', 'create(7.0.0.0)', 1);
insert into ACT_ID_PROPERTY
values ('schema.version', '7.0.0.0', 1);
insert into ACT_GE_PROPERTY values ('task.schema.version', '7.0.0.0', 1);
insert into ACT_GE_PROPERTY values ('variable.schema.version', '7.0.0.0', 1);
insert into ACT_GE_PROPERTY values ('identitylink.schema.version', '7.0.0.0', 1);
insert into ACT_GE_PROPERTY values ('entitylink.schema.version', '7.0.0.0', 1);
insert into ACT_GE_PROPERTY values ('eventsubscription.schema.version', '7.0.0.0', 1);
insert into ACT_GE_PROPERTY values ('batch.schema.version', '7.0.0.0', 1);
insert into ACT_GE_PROPERTY values ('job.schema.version', '7.0.0.0', 1);

-- ========== 其他语句 ==========
alter table ACT_GE_BYTEARRAY
    add constraint ACT_FK_BYTEARR_DEPL 
    foreign key (DEPLOYMENT_ID_) 
    references ACT_RE_DEPLOYMENT (ID_);
alter table ACT_RE_PROCDEF
    add constraint ACT_UNIQ_PROCDEF
    unique (KEY_,VERSION_, DERIVED_VERSION_, TENANT_ID_);
alter table ACT_RU_EXECUTION
    add constraint ACT_FK_EXE_PROCINST 
    foreign key (PROC_INST_ID_) 
    references ACT_RU_EXECUTION (ID_) on delete cascade on update cascade;
alter table ACT_RU_EXECUTION
    add constraint ACT_FK_EXE_PARENT 
    foreign key (PARENT_ID_) 
    references ACT_RU_EXECUTION (ID_) on delete cascade;
alter table ACT_RU_EXECUTION
    add constraint ACT_FK_EXE_SUPER 
    foreign key (SUPER_EXEC_) 
    references ACT_RU_EXECUTION (ID_) on delete cascade;
alter table ACT_RU_EXECUTION
    add constraint ACT_FK_EXE_PROCDEF 
    foreign key (PROC_DEF_ID_) 
    references ACT_RE_PROCDEF (ID_);
alter table ACT_RU_IDENTITYLINK
    add constraint ACT_FK_TSKASS_TASK 
    foreign key (TASK_ID_) 
    references ACT_RU_TASK (ID_);
alter table ACT_RU_IDENTITYLINK
    add constraint ACT_FK_ATHRZ_PROCEDEF 
    foreign key (PROC_DEF_ID_) 
    references ACT_RE_PROCDEF(ID_);
alter table ACT_RU_IDENTITYLINK
    add constraint ACT_FK_IDL_PROCINST
    foreign key (PROC_INST_ID_) 
    references ACT_RU_EXECUTION (ID_);
alter table ACT_RU_TASK
    add constraint ACT_FK_TASK_EXE
    foreign key (EXECUTION_ID_)
    references ACT_RU_EXECUTION (ID_);
alter table ACT_RU_TASK
    add constraint ACT_FK_TASK_PROCINST
    foreign key (PROC_INST_ID_)
    references ACT_RU_EXECUTION (ID_);
alter table ACT_RU_TASK
  	add constraint ACT_FK_TASK_PROCDEF
  	foreign key (PROC_DEF_ID_)
  	references ACT_RE_PROCDEF (ID_);
alter table ACT_RU_VARIABLE 
    add constraint ACT_FK_VAR_EXE 
    foreign key (EXECUTION_ID_) 
    references ACT_RU_EXECUTION (ID_);
alter table ACT_RU_VARIABLE
    add constraint ACT_FK_VAR_PROCINST
    foreign key (PROC_INST_ID_)
    references ACT_RU_EXECUTION(ID_);
alter table ACT_RU_JOB 
    add constraint ACT_FK_JOB_EXECUTION 
    foreign key (EXECUTION_ID_) 
    references ACT_RU_EXECUTION (ID_);
alter table ACT_RU_JOB 
    add constraint ACT_FK_JOB_PROCESS_INSTANCE 
    foreign key (PROCESS_INSTANCE_ID_) 
    references ACT_RU_EXECUTION (ID_);
alter table ACT_RU_JOB 
    add constraint ACT_FK_JOB_PROC_DEF
    foreign key (PROC_DEF_ID_) 
    references ACT_RE_PROCDEF (ID_);
alter table ACT_RU_TIMER_JOB 
    add constraint ACT_FK_TIMER_JOB_EXECUTION 
    foreign key (EXECUTION_ID_) 
    references ACT_RU_EXECUTION (ID_);
alter table ACT_RU_TIMER_JOB 
    add constraint ACT_FK_TIMER_JOB_PROCESS_INSTANCE 
    foreign key (PROCESS_INSTANCE_ID_) 
    references ACT_RU_EXECUTION (ID_);
alter table ACT_RU_TIMER_JOB 
    add constraint ACT_FK_TIMER_JOB_PROC_DEF
    foreign key (PROC_DEF_ID_) 
    references ACT_RE_PROCDEF (ID_);
alter table ACT_RU_SUSPENDED_JOB 
    add constraint ACT_FK_SUSPENDED_JOB_EXECUTION 
    foreign key (EXECUTION_ID_) 
    references ACT_RU_EXECUTION (ID_);
alter table ACT_RU_SUSPENDED_JOB 
    add constraint ACT_FK_SUSPENDED_JOB_PROCESS_INSTANCE 
    foreign key (PROCESS_INSTANCE_ID_) 
    references ACT_RU_EXECUTION (ID_);
alter table ACT_RU_SUSPENDED_JOB 
    add constraint ACT_FK_SUSPENDED_JOB_PROC_DEF
    foreign key (PROC_DEF_ID_) 
    references ACT_RE_PROCDEF (ID_);
alter table ACT_RU_DEADLETTER_JOB 
    add constraint ACT_FK_DEADLETTER_JOB_EXECUTION 
    foreign key (EXECUTION_ID_) 
    references ACT_RU_EXECUTION (ID_);
alter table ACT_RU_DEADLETTER_JOB 
    add constraint ACT_FK_DEADLETTER_JOB_PROCESS_INSTANCE 
    foreign key (PROCESS_INSTANCE_ID_) 
    references ACT_RU_EXECUTION (ID_);
alter table ACT_RU_DEADLETTER_JOB 
    add constraint ACT_FK_DEADLETTER_JOB_PROC_DEF
    foreign key (PROC_DEF_ID_) 
    references ACT_RE_PROCDEF (ID_);
alter table ACT_RU_EVENT_SUBSCR
    add constraint ACT_FK_EVENT_EXEC
    foreign key (EXECUTION_ID_)
    references ACT_RU_EXECUTION(ID_);
alter table ACT_RE_MODEL 
    add constraint ACT_FK_MODEL_SOURCE 
    foreign key (EDITOR_SOURCE_VALUE_ID_) 
    references ACT_GE_BYTEARRAY (ID_);
alter table ACT_RE_MODEL 
    add constraint ACT_FK_MODEL_SOURCE_EXTRA 
    foreign key (EDITOR_SOURCE_EXTRA_VALUE_ID_) 
    references ACT_GE_BYTEARRAY (ID_);
alter table ACT_RE_MODEL 
    add constraint ACT_FK_MODEL_DEPLOYMENT 
    foreign key (DEPLOYMENT_ID_) 
    references ACT_RE_DEPLOYMENT (ID_);
alter table ACT_PROCDEF_INFO 
    add constraint ACT_FK_INFO_JSON_BA 
    foreign key (INFO_JSON_ID_) 
    references ACT_GE_BYTEARRAY (ID_);
alter table ACT_PROCDEF_INFO 
    add constraint ACT_FK_INFO_PROCDEF 
    foreign key (PROC_DEF_ID_) 
    references ACT_RE_PROCDEF (ID_);
alter table ACT_PROCDEF_INFO
    add constraint ACT_UNIQ_INFO_PROCDEF
    unique (PROC_DEF_ID_);
alter table ACT_ID_MEMBERSHIP
    add constraint ACT_FK_MEMB_GROUP
    foreign key (GROUP_ID_)
    references ACT_ID_GROUP (ID_);
alter table ACT_ID_MEMBERSHIP
    add constraint ACT_FK_MEMB_USER
    foreign key (USER_ID_)
    references ACT_ID_USER (ID_);
alter table ACT_ID_PRIV_MAPPING
    add constraint ACT_FK_PRIV_MAPPING
    foreign key (PRIV_ID_)
    references ACT_ID_PRIV (ID_);
alter table ACT_ID_PRIV
    add constraint ACT_UNIQ_PRIV_NAME
    unique (NAME_);
alter table ACT_RU_VARIABLE 
    add constraint ACT_FK_VAR_BYTEARRAY 
    foreign key (BYTEARRAY_ID_) 
    references ACT_GE_BYTEARRAY (ID_);
alter table FLW_RU_BATCH_PART
    add constraint FLW_FK_BATCH_PART_PARENT
    foreign key (BATCH_ID_)
    references FLW_RU_BATCH (ID_);
alter table ACT_RU_JOB
    add constraint ACT_FK_JOB_EXCEPTION
    foreign key (EXCEPTION_STACK_ID_)
    references ACT_GE_BYTEARRAY (ID_);
alter table ACT_RU_JOB
    add constraint ACT_FK_JOB_CUSTOM_VALUES
    foreign key (CUSTOM_VALUES_ID_)
    references ACT_GE_BYTEARRAY (ID_);
alter table ACT_RU_TIMER_JOB
    add constraint ACT_FK_TIMER_JOB_EXCEPTION
    foreign key (EXCEPTION_STACK_ID_)
    references ACT_GE_BYTEARRAY (ID_);
alter table ACT_RU_TIMER_JOB
    add constraint ACT_FK_TIMER_JOB_CUSTOM_VALUES
    foreign key (CUSTOM_VALUES_ID_)
    references ACT_GE_BYTEARRAY (ID_);
alter table ACT_RU_SUSPENDED_JOB
    add constraint ACT_FK_SUSPENDED_JOB_EXCEPTION
    foreign key (EXCEPTION_STACK_ID_)
    references ACT_GE_BYTEARRAY (ID_);
alter table ACT_RU_SUSPENDED_JOB
    add constraint ACT_FK_SUSPENDED_JOB_CUSTOM_VALUES
    foreign key (CUSTOM_VALUES_ID_)
    references ACT_GE_BYTEARRAY (ID_);
alter table ACT_RU_DEADLETTER_JOB
    add constraint ACT_FK_DEADLETTER_JOB_EXCEPTION
    foreign key (EXCEPTION_STACK_ID_)
    references ACT_GE_BYTEARRAY (ID_);
alter table ACT_RU_DEADLETTER_JOB
    add constraint ACT_FK_DEADLETTER_JOB_CUSTOM_VALUES
    foreign key (CUSTOM_VALUES_ID_)
    references ACT_GE_BYTEARRAY (ID_);
alter table ACT_RU_EXTERNAL_JOB
    add constraint ACT_FK_EXTERNAL_JOB_EXCEPTION
    foreign key (EXCEPTION_STACK_ID_)
    references ACT_GE_BYTEARRAY (ID_);
alter table ACT_RU_EXTERNAL_JOB
    add constraint ACT_FK_EXTERNAL_JOB_CUSTOM_VALUES
    foreign key (CUSTOM_VALUES_ID_)
    references ACT_GE_BYTEARRAY (ID_);

SET FOREIGN_KEY_CHECKS = 1;