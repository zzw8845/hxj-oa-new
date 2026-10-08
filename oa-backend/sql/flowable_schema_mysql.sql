-- =============================================================================
-- Flowable 7.0.0 官方 MySQL 建表脚本（自动汇总并重排，请勿手工编辑）
--
-- 由 scripts/gen_flowable_ddl.py 生成。
--
-- 背景：Flowable 7.0.0 的 eventregistry 用 Liquibase 管理 schema，
--       其前置检查读取 ACT_GE_PROPERTY 判断 common schema 是否就绪；表不存在时
--       判定逻辑走错分支，执行 insert 而非建表，导致启动失败：
--         FlowableException: couldn't upgrade db schema: insert into ACT_GE_PROPERTY ...
--       因此需先用本脚本预建全部 ACT_* 表。
--
-- 幂等（2026-09-29，P1 收口）：建表带 IF NOT EXISTS；官方后置的索引与外键约束
--       已内联进对应建表语句（表已存在时随之整体跳过）；版本标记为 INSERT IGNORE。
--       重复执行不会报错、不会动已有数据。MySQL 8 没有 CREATE INDEX IF NOT EXISTS，
--       内联是纯语句级幂等的唯一干净路径。
--
-- 用法：mysql -uroot <库名> < flowable_schema_mysql.sql
-- =============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ========== 建表（41 张，索引/约束已内联 145 条）==========
CREATE TABLE IF NOT EXISTS ACT_GE_PROPERTY (
    NAME_ varchar(64),
    VALUE_ varchar(300),
    REV_ integer,
    primary key (NAME_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_GE_BYTEARRAY (
    ID_ varchar(64),
    REV_ integer,
    NAME_ varchar(255),
    DEPLOYMENT_ID_ varchar(64),
    BYTES_ LONGBLOB,
    GENERATED_ TINYINT,
    primary key (ID_)
,
  constraint ACT_FK_BYTEARR_DEPL 
    foreign key (DEPLOYMENT_ID_) 
    references ACT_RE_DEPLOYMENT (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_RE_DEPLOYMENT (
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
CREATE TABLE IF NOT EXISTS ACT_RE_MODEL (
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
,
  constraint ACT_FK_MODEL_SOURCE 
    foreign key (EDITOR_SOURCE_VALUE_ID_) 
    references ACT_GE_BYTEARRAY (ID_)
,
  constraint ACT_FK_MODEL_SOURCE_EXTRA 
    foreign key (EDITOR_SOURCE_EXTRA_VALUE_ID_) 
    references ACT_GE_BYTEARRAY (ID_)
,
  constraint ACT_FK_MODEL_DEPLOYMENT 
    foreign key (DEPLOYMENT_ID_) 
    references ACT_RE_DEPLOYMENT (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_RU_EXECUTION (
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
,
  INDEX ACT_IDX_EXEC_BUSKEY (BUSINESS_KEY_)
,
  INDEX ACT_IDC_EXEC_ROOT (ROOT_PROC_INST_ID_)
,
  INDEX ACT_IDX_EXEC_REF_ID_ (REFERENCE_ID_)
,
  constraint ACT_FK_EXE_PROCINST 
    foreign key (PROC_INST_ID_) 
    references ACT_RU_EXECUTION (ID_) on delete cascade on update cascade
,
  constraint ACT_FK_EXE_PARENT 
    foreign key (PARENT_ID_) 
    references ACT_RU_EXECUTION (ID_) on delete cascade
,
  constraint ACT_FK_EXE_SUPER 
    foreign key (SUPER_EXEC_) 
    references ACT_RU_EXECUTION (ID_) on delete cascade
,
  constraint ACT_FK_EXE_PROCDEF 
    foreign key (PROC_DEF_ID_) 
    references ACT_RE_PROCDEF (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_RE_PROCDEF (
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
,
  constraint ACT_UNIQ_PROCDEF
    unique (KEY_,VERSION_, DERIVED_VERSION_, TENANT_ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_EVT_LOG (
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
CREATE TABLE IF NOT EXISTS ACT_PROCDEF_INFO (
	ID_ varchar(64) not null,
    PROC_DEF_ID_ varchar(64) not null,
    REV_ integer,
    INFO_JSON_ID_ varchar(64),
    primary key (ID_)
,
  INDEX ACT_IDX_INFO_PROCDEF (PROC_DEF_ID_)
,
  constraint ACT_FK_INFO_JSON_BA 
    foreign key (INFO_JSON_ID_) 
    references ACT_GE_BYTEARRAY (ID_)
,
  constraint ACT_FK_INFO_PROCDEF 
    foreign key (PROC_DEF_ID_) 
    references ACT_RE_PROCDEF (ID_)
,
  constraint ACT_UNIQ_INFO_PROCDEF
    unique (PROC_DEF_ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_RU_ACTINST (
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
,
  INDEX ACT_IDX_RU_ACTI_START (START_TIME_)
,
  INDEX ACT_IDX_RU_ACTI_END (END_TIME_)
,
  INDEX ACT_IDX_RU_ACTI_PROC (PROC_INST_ID_)
,
  INDEX ACT_IDX_RU_ACTI_PROC_ACT (PROC_INST_ID_, ACT_ID_)
,
  INDEX ACT_IDX_RU_ACTI_EXEC (EXECUTION_ID_)
,
  INDEX ACT_IDX_RU_ACTI_EXEC_ACT (EXECUTION_ID_, ACT_ID_)
,
  INDEX ACT_IDX_RU_ACTI_TASK (TASK_ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_HI_PROCINST (
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
,
  INDEX ACT_IDX_HI_PRO_INST_END (END_TIME_)
,
  INDEX ACT_IDX_HI_PRO_I_BUSKEY (BUSINESS_KEY_)
,
  INDEX ACT_IDX_HI_PRO_SUPER_PROCINST (SUPER_PROCESS_INSTANCE_ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_HI_ACTINST (
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
,
  INDEX ACT_IDX_HI_ACT_INST_START (START_TIME_)
,
  INDEX ACT_IDX_HI_ACT_INST_END (END_TIME_)
,
  INDEX ACT_IDX_HI_ACT_INST_PROCINST (PROC_INST_ID_, ACT_ID_)
,
  INDEX ACT_IDX_HI_ACT_INST_EXEC (EXECUTION_ID_, ACT_ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_HI_DETAIL (
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
,
  INDEX ACT_IDX_HI_DETAIL_PROC_INST (PROC_INST_ID_)
,
  INDEX ACT_IDX_HI_DETAIL_ACT_INST (ACT_INST_ID_)
,
  INDEX ACT_IDX_HI_DETAIL_TIME (TIME_)
,
  INDEX ACT_IDX_HI_DETAIL_NAME (NAME_)
,
  INDEX ACT_IDX_HI_DETAIL_TASK_ID (TASK_ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_HI_COMMENT (
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
CREATE TABLE IF NOT EXISTS ACT_HI_ATTACHMENT (
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
CREATE TABLE IF NOT EXISTS ACT_ID_PROPERTY (
    NAME_ varchar(64),
    VALUE_ varchar(300),
    REV_ integer,
    primary key (NAME_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_ID_BYTEARRAY (
    ID_ varchar(64),
    REV_ integer,
    NAME_ varchar(255),
    BYTES_ LONGBLOB,
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_ID_GROUP (
    ID_ varchar(64),
    REV_ integer,
    NAME_ varchar(255),
    TYPE_ varchar(255),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_ID_MEMBERSHIP (
    USER_ID_ varchar(64),
    GROUP_ID_ varchar(64),
    primary key (USER_ID_, GROUP_ID_)
,
  constraint ACT_FK_MEMB_GROUP
    foreign key (GROUP_ID_)
    references ACT_ID_GROUP (ID_)
,
  constraint ACT_FK_MEMB_USER
    foreign key (USER_ID_)
    references ACT_ID_USER (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_ID_USER (
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
CREATE TABLE IF NOT EXISTS ACT_ID_INFO (
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
CREATE TABLE IF NOT EXISTS ACT_ID_TOKEN (
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
CREATE TABLE IF NOT EXISTS ACT_ID_PRIV (
    ID_ varchar(64) not null,
    NAME_ varchar(255) not null,
    primary key (ID_)
,
  constraint ACT_UNIQ_PRIV_NAME
    unique (NAME_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_ID_PRIV_MAPPING (
    ID_ varchar(64) not null,
    PRIV_ID_ varchar(64) not null,
    USER_ID_ varchar(255),
    GROUP_ID_ varchar(255),
    primary key (ID_)
,
  INDEX ACT_IDX_PRIV_USER (USER_ID_)
,
  INDEX ACT_IDX_PRIV_GROUP (GROUP_ID_)
,
  constraint ACT_FK_PRIV_MAPPING
    foreign key (PRIV_ID_)
    references ACT_ID_PRIV (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_RU_TASK (
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
,
  INDEX ACT_IDX_TASK_CREATE (CREATE_TIME_)
,
  INDEX ACT_IDX_TASK_SCOPE (SCOPE_ID_, SCOPE_TYPE_)
,
  INDEX ACT_IDX_TASK_SUB_SCOPE (SUB_SCOPE_ID_, SCOPE_TYPE_)
,
  INDEX ACT_IDX_TASK_SCOPE_DEF (SCOPE_DEFINITION_ID_, SCOPE_TYPE_)
,
  constraint ACT_FK_TASK_EXE
    foreign key (EXECUTION_ID_)
    references ACT_RU_EXECUTION (ID_)
,
  constraint ACT_FK_TASK_PROCINST
    foreign key (PROC_INST_ID_)
    references ACT_RU_EXECUTION (ID_)
,
  constraint ACT_FK_TASK_PROCDEF
  	foreign key (PROC_DEF_ID_)
  	references ACT_RE_PROCDEF (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_RU_VARIABLE (
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
,
  INDEX ACT_IDX_VARIABLE_TASK_ID (TASK_ID_)
,
  INDEX ACT_IDX_RU_VAR_SCOPE_ID_TYPE (SCOPE_ID_, SCOPE_TYPE_)
,
  INDEX ACT_IDX_RU_VAR_SUB_ID_TYPE (SUB_SCOPE_ID_, SCOPE_TYPE_)
,
  constraint ACT_FK_VAR_EXE 
    foreign key (EXECUTION_ID_) 
    references ACT_RU_EXECUTION (ID_)
,
  constraint ACT_FK_VAR_PROCINST
    foreign key (PROC_INST_ID_)
    references ACT_RU_EXECUTION(ID_)
,
  constraint ACT_FK_VAR_BYTEARRAY 
    foreign key (BYTEARRAY_ID_) 
    references ACT_GE_BYTEARRAY (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_RU_IDENTITYLINK (
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
,
  INDEX ACT_IDX_ATHRZ_PROCEDEF (PROC_DEF_ID_)
,
  INDEX ACT_IDX_IDENT_LNK_USER (USER_ID_)
,
  INDEX ACT_IDX_IDENT_LNK_GROUP (GROUP_ID_)
,
  INDEX ACT_IDX_IDENT_LNK_SCOPE (SCOPE_ID_, SCOPE_TYPE_)
,
  INDEX ACT_IDX_IDENT_LNK_SUB_SCOPE (SUB_SCOPE_ID_, SCOPE_TYPE_)
,
  INDEX ACT_IDX_IDENT_LNK_SCOPE_DEF (SCOPE_DEFINITION_ID_, SCOPE_TYPE_)
,
  constraint ACT_FK_TSKASS_TASK 
    foreign key (TASK_ID_) 
    references ACT_RU_TASK (ID_)
,
  constraint ACT_FK_ATHRZ_PROCEDEF 
    foreign key (PROC_DEF_ID_) 
    references ACT_RE_PROCDEF(ID_)
,
  constraint ACT_FK_IDL_PROCINST
    foreign key (PROC_INST_ID_) 
    references ACT_RU_EXECUTION (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_RU_ENTITYLINK (
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
,
  INDEX ACT_IDX_ENT_LNK_SCOPE (SCOPE_ID_, SCOPE_TYPE_, LINK_TYPE_)
,
  INDEX ACT_IDX_ENT_LNK_REF_SCOPE (REF_SCOPE_ID_, REF_SCOPE_TYPE_, LINK_TYPE_)
,
  INDEX ACT_IDX_ENT_LNK_ROOT_SCOPE (ROOT_SCOPE_ID_, ROOT_SCOPE_TYPE_, LINK_TYPE_)
,
  INDEX ACT_IDX_ENT_LNK_SCOPE_DEF (SCOPE_DEFINITION_ID_, SCOPE_TYPE_, LINK_TYPE_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_RU_EVENT_SUBSCR (
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
,
  INDEX ACT_IDX_EVENT_SUBSCR_CONFIG_ (CONFIGURATION_)
,
  INDEX ACT_IDX_EVENT_SUBSCR_SCOPEREF_ (SCOPE_ID_, SCOPE_TYPE_)
,
  constraint ACT_FK_EVENT_EXEC
    foreign key (EXECUTION_ID_)
    references ACT_RU_EXECUTION(ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS FLW_RU_BATCH (
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
CREATE TABLE IF NOT EXISTS FLW_RU_BATCH_PART (
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
,
  INDEX FLW_IDX_BATCH_PART (BATCH_ID_)
,
  constraint FLW_FK_BATCH_PART_PARENT
    foreign key (BATCH_ID_)
    references FLW_RU_BATCH (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_RU_JOB (
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
,
  INDEX ACT_IDX_JOB_EXCEPTION_STACK_ID (EXCEPTION_STACK_ID_)
,
  INDEX ACT_IDX_JOB_CUSTOM_VALUES_ID (CUSTOM_VALUES_ID_)
,
  INDEX ACT_IDX_JOB_CORRELATION_ID (CORRELATION_ID_)
,
  INDEX ACT_IDX_JOB_SCOPE (SCOPE_ID_, SCOPE_TYPE_)
,
  INDEX ACT_IDX_JOB_SUB_SCOPE (SUB_SCOPE_ID_, SCOPE_TYPE_)
,
  INDEX ACT_IDX_JOB_SCOPE_DEF (SCOPE_DEFINITION_ID_, SCOPE_TYPE_)
,
  constraint ACT_FK_JOB_EXECUTION 
    foreign key (EXECUTION_ID_) 
    references ACT_RU_EXECUTION (ID_)
,
  constraint ACT_FK_JOB_PROCESS_INSTANCE 
    foreign key (PROCESS_INSTANCE_ID_) 
    references ACT_RU_EXECUTION (ID_)
,
  constraint ACT_FK_JOB_PROC_DEF
    foreign key (PROC_DEF_ID_) 
    references ACT_RE_PROCDEF (ID_)
,
  constraint ACT_FK_JOB_EXCEPTION
    foreign key (EXCEPTION_STACK_ID_)
    references ACT_GE_BYTEARRAY (ID_)
,
  constraint ACT_FK_JOB_CUSTOM_VALUES
    foreign key (CUSTOM_VALUES_ID_)
    references ACT_GE_BYTEARRAY (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_RU_TIMER_JOB (
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
,
  INDEX ACT_IDX_TIMER_JOB_EXCEPTION_STACK_ID (EXCEPTION_STACK_ID_)
,
  INDEX ACT_IDX_TIMER_JOB_CUSTOM_VALUES_ID (CUSTOM_VALUES_ID_)
,
  INDEX ACT_IDX_TIMER_JOB_CORRELATION_ID (CORRELATION_ID_)
,
  INDEX ACT_IDX_TIMER_JOB_DUEDATE (DUEDATE_)
,
  INDEX ACT_IDX_TJOB_SCOPE (SCOPE_ID_, SCOPE_TYPE_)
,
  INDEX ACT_IDX_TJOB_SUB_SCOPE (SUB_SCOPE_ID_, SCOPE_TYPE_)
,
  INDEX ACT_IDX_TJOB_SCOPE_DEF (SCOPE_DEFINITION_ID_, SCOPE_TYPE_)
,
  constraint ACT_FK_TIMER_JOB_EXECUTION 
    foreign key (EXECUTION_ID_) 
    references ACT_RU_EXECUTION (ID_)
,
  constraint ACT_FK_TIMER_JOB_PROCESS_INSTANCE 
    foreign key (PROCESS_INSTANCE_ID_) 
    references ACT_RU_EXECUTION (ID_)
,
  constraint ACT_FK_TIMER_JOB_PROC_DEF
    foreign key (PROC_DEF_ID_) 
    references ACT_RE_PROCDEF (ID_)
,
  constraint ACT_FK_TIMER_JOB_EXCEPTION
    foreign key (EXCEPTION_STACK_ID_)
    references ACT_GE_BYTEARRAY (ID_)
,
  constraint ACT_FK_TIMER_JOB_CUSTOM_VALUES
    foreign key (CUSTOM_VALUES_ID_)
    references ACT_GE_BYTEARRAY (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_RU_SUSPENDED_JOB (
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
,
  INDEX ACT_IDX_SUSPENDED_JOB_EXCEPTION_STACK_ID (EXCEPTION_STACK_ID_)
,
  INDEX ACT_IDX_SUSPENDED_JOB_CUSTOM_VALUES_ID (CUSTOM_VALUES_ID_)
,
  INDEX ACT_IDX_SUSPENDED_JOB_CORRELATION_ID (CORRELATION_ID_)
,
  INDEX ACT_IDX_SJOB_SCOPE (SCOPE_ID_, SCOPE_TYPE_)
,
  INDEX ACT_IDX_SJOB_SUB_SCOPE (SUB_SCOPE_ID_, SCOPE_TYPE_)
,
  INDEX ACT_IDX_SJOB_SCOPE_DEF (SCOPE_DEFINITION_ID_, SCOPE_TYPE_)
,
  constraint ACT_FK_SUSPENDED_JOB_EXECUTION 
    foreign key (EXECUTION_ID_) 
    references ACT_RU_EXECUTION (ID_)
,
  constraint ACT_FK_SUSPENDED_JOB_PROCESS_INSTANCE 
    foreign key (PROCESS_INSTANCE_ID_) 
    references ACT_RU_EXECUTION (ID_)
,
  constraint ACT_FK_SUSPENDED_JOB_PROC_DEF
    foreign key (PROC_DEF_ID_) 
    references ACT_RE_PROCDEF (ID_)
,
  constraint ACT_FK_SUSPENDED_JOB_EXCEPTION
    foreign key (EXCEPTION_STACK_ID_)
    references ACT_GE_BYTEARRAY (ID_)
,
  constraint ACT_FK_SUSPENDED_JOB_CUSTOM_VALUES
    foreign key (CUSTOM_VALUES_ID_)
    references ACT_GE_BYTEARRAY (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_RU_DEADLETTER_JOB (
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
,
  INDEX ACT_IDX_DEADLETTER_JOB_EXCEPTION_STACK_ID (EXCEPTION_STACK_ID_)
,
  INDEX ACT_IDX_DEADLETTER_JOB_CUSTOM_VALUES_ID (CUSTOM_VALUES_ID_)
,
  INDEX ACT_IDX_DEADLETTER_JOB_CORRELATION_ID (CORRELATION_ID_)
,
  INDEX ACT_IDX_DJOB_SCOPE (SCOPE_ID_, SCOPE_TYPE_)
,
  INDEX ACT_IDX_DJOB_SUB_SCOPE (SUB_SCOPE_ID_, SCOPE_TYPE_)
,
  INDEX ACT_IDX_DJOB_SCOPE_DEF (SCOPE_DEFINITION_ID_, SCOPE_TYPE_)
,
  constraint ACT_FK_DEADLETTER_JOB_EXECUTION 
    foreign key (EXECUTION_ID_) 
    references ACT_RU_EXECUTION (ID_)
,
  constraint ACT_FK_DEADLETTER_JOB_PROCESS_INSTANCE 
    foreign key (PROCESS_INSTANCE_ID_) 
    references ACT_RU_EXECUTION (ID_)
,
  constraint ACT_FK_DEADLETTER_JOB_PROC_DEF
    foreign key (PROC_DEF_ID_) 
    references ACT_RE_PROCDEF (ID_)
,
  constraint ACT_FK_DEADLETTER_JOB_EXCEPTION
    foreign key (EXCEPTION_STACK_ID_)
    references ACT_GE_BYTEARRAY (ID_)
,
  constraint ACT_FK_DEADLETTER_JOB_CUSTOM_VALUES
    foreign key (CUSTOM_VALUES_ID_)
    references ACT_GE_BYTEARRAY (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_RU_HISTORY_JOB (
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
CREATE TABLE IF NOT EXISTS ACT_RU_EXTERNAL_JOB (
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
,
  INDEX ACT_IDX_EXTERNAL_JOB_EXCEPTION_STACK_ID (EXCEPTION_STACK_ID_)
,
  INDEX ACT_IDX_EXTERNAL_JOB_CUSTOM_VALUES_ID (CUSTOM_VALUES_ID_)
,
  INDEX ACT_IDX_EXTERNAL_JOB_CORRELATION_ID (CORRELATION_ID_)
,
  INDEX ACT_IDX_EJOB_SCOPE (SCOPE_ID_, SCOPE_TYPE_)
,
  INDEX ACT_IDX_EJOB_SUB_SCOPE (SUB_SCOPE_ID_, SCOPE_TYPE_)
,
  INDEX ACT_IDX_EJOB_SCOPE_DEF (SCOPE_DEFINITION_ID_, SCOPE_TYPE_)
,
  constraint ACT_FK_EXTERNAL_JOB_EXCEPTION
    foreign key (EXCEPTION_STACK_ID_)
    references ACT_GE_BYTEARRAY (ID_)
,
  constraint ACT_FK_EXTERNAL_JOB_CUSTOM_VALUES
    foreign key (CUSTOM_VALUES_ID_)
    references ACT_GE_BYTEARRAY (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_HI_ENTITYLINK (
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
,
  INDEX ACT_IDX_HI_ENT_LNK_SCOPE (SCOPE_ID_, SCOPE_TYPE_, LINK_TYPE_)
,
  INDEX ACT_IDX_HI_ENT_LNK_REF_SCOPE (REF_SCOPE_ID_, REF_SCOPE_TYPE_, LINK_TYPE_)
,
  INDEX ACT_IDX_HI_ENT_LNK_ROOT_SCOPE (ROOT_SCOPE_ID_, ROOT_SCOPE_TYPE_, LINK_TYPE_)
,
  INDEX ACT_IDX_HI_ENT_LNK_SCOPE_DEF (SCOPE_DEFINITION_ID_, SCOPE_TYPE_, LINK_TYPE_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_HI_IDENTITYLINK (
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
,
  INDEX ACT_IDX_HI_IDENT_LNK_TASK (TASK_ID_)
,
  INDEX ACT_IDX_HI_IDENT_LNK_PROCINST (PROC_INST_ID_)
,
  INDEX ACT_IDX_HI_IDENT_LNK_USER (USER_ID_)
,
  INDEX ACT_IDX_HI_IDENT_LNK_SCOPE (SCOPE_ID_, SCOPE_TYPE_)
,
  INDEX ACT_IDX_HI_IDENT_LNK_SUB_SCOPE (SUB_SCOPE_ID_, SCOPE_TYPE_)
,
  INDEX ACT_IDX_HI_IDENT_LNK_SCOPE_DEF (SCOPE_DEFINITION_ID_, SCOPE_TYPE_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_HI_TASKINST (
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
,
  INDEX ACT_IDX_HI_TASK_INST_PROCINST (PROC_INST_ID_)
,
  INDEX ACT_IDX_HI_TASK_SCOPE (SCOPE_ID_, SCOPE_TYPE_)
,
  INDEX ACT_IDX_HI_TASK_SUB_SCOPE (SUB_SCOPE_ID_, SCOPE_TYPE_)
,
  INDEX ACT_IDX_HI_TASK_SCOPE_DEF (SCOPE_DEFINITION_ID_, SCOPE_TYPE_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
CREATE TABLE IF NOT EXISTS ACT_HI_TSK_LOG (
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
CREATE TABLE IF NOT EXISTS ACT_HI_VARINST (
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
,
  INDEX ACT_IDX_HI_PROCVAR_PROC_INST (PROC_INST_ID_)
,
  INDEX ACT_IDX_HI_PROCVAR_TASK_ID (TASK_ID_)
,
  INDEX ACT_IDX_HI_PROCVAR_EXE (EXECUTION_ID_)
,
  INDEX ACT_IDX_HI_PROCVAR_NAME_TYPE (NAME_, VAR_TYPE_)
,
  INDEX ACT_IDX_HI_VAR_SCOPE_ID_TYPE (SCOPE_ID_, SCOPE_TYPE_)
,
  INDEX ACT_IDX_HI_VAR_SUB_ID_TYPE (SUB_SCOPE_ID_, SCOPE_TYPE_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

-- ========== 版本与初始化数据（INSERT IGNORE，12 条）==========
INSERT IGNORE INTO ACT_GE_PROPERTY
values ('common.schema.version', '7.0.0.0', 1);
INSERT IGNORE INTO ACT_GE_PROPERTY
values ('next.dbid', '1', 1);
INSERT IGNORE INTO ACT_GE_PROPERTY
values ('schema.version', '7.0.0.0', 1);
INSERT IGNORE INTO ACT_GE_PROPERTY
values ('schema.history', 'create(7.0.0.0)', 1);
INSERT IGNORE INTO ACT_ID_PROPERTY
values ('schema.version', '7.0.0.0', 1);
INSERT IGNORE INTO ACT_GE_PROPERTY values ('task.schema.version', '7.0.0.0', 1);
INSERT IGNORE INTO ACT_GE_PROPERTY values ('variable.schema.version', '7.0.0.0', 1);
INSERT IGNORE INTO ACT_GE_PROPERTY values ('identitylink.schema.version', '7.0.0.0', 1);
INSERT IGNORE INTO ACT_GE_PROPERTY values ('entitylink.schema.version', '7.0.0.0', 1);
INSERT IGNORE INTO ACT_GE_PROPERTY values ('eventsubscription.schema.version', '7.0.0.0', 1);
INSERT IGNORE INTO ACT_GE_PROPERTY values ('batch.schema.version', '7.0.0.0', 1);
INSERT IGNORE INTO ACT_GE_PROPERTY values ('job.schema.version', '7.0.0.0', 1);

SET FOREIGN_KEY_CHECKS = 1;