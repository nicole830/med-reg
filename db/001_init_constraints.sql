-- 医院门诊预约挂号系统 · 初始化后补建约束（可重复执行）
-- 用法：mysql -uroot -p --default-character-set=utf8mb4 -D order < db/001_init_constraints.sql
--
-- 背景：order220912.sql 只给业务表建了主键，放号唯一索引与支付流水唯一键是历史上用
--       ALTER 手工加的，只存在于当时的运行库。新环境导入 dump 后必须执行本脚本，
--       否则"放号防重"和"一单一流水"就只剩应用层逻辑，并发放号会真的出重号。

-- ---------------------------------------------------------------------------
-- 1. 放号唯一索引：同一医生 + 日期 + 时段 + 编号只能出现一次
--    注意：4 个 varchar(255) 拼联合索引，utf8mb3 下 4*255*3=3060 字节，刚好低于
--    MySQL 3072 字节上限；若这类表被改成 utf8mb4，本 ALTER 会报 1071(key too long)，
--    届时需要缩短字段长度而不是加前缀索引（前缀索引会破坏唯一性语义）。
-- ---------------------------------------------------------------------------
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'a_doctor_scheduling'
      AND index_name = 'uk_doc_date_step_num'
);
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE `a_doctor_scheduling` ADD UNIQUE KEY `uk_doc_date_step_num` (`doctor_id`,`date`,`step`,`number`)',
    'SELECT ''uk_doc_date_step_num 已存在，跳过'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 2. 支付流水表 + 一单一流水唯一键
--    JPA（ddl-auto: update）会在启动时自动建表，但不会建唯一键，所以这里显式声明
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `a_payment_record` (
  `id` varchar(255) NOT NULL,
  `create_by` varchar(255) DEFAULT NULL,
  `create_time` datetime(6) DEFAULT NULL,
  `del_flag` int(11) NOT NULL DEFAULT 0,
  `update_by` varchar(255) DEFAULT NULL,
  `update_time` datetime(6) DEFAULT NULL,
  `order_id` varchar(255) NOT NULL COMMENT '挂号单ID',
  `user_id` varchar(255) DEFAULT NULL,
  `user_name` varchar(255) DEFAULT NULL,
  `amount` decimal(19,2) DEFAULT NULL,
  `status` int(11) NOT NULL DEFAULT 0 COMMENT '0支付成功 1已退款',
  `pay_no` varchar(64) DEFAULT NULL,
  `refund_no` varchar(64) DEFAULT NULL,
  `remark` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='挂号支付流水';

SET @pay_idx_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'a_payment_record'
      AND index_name = 'uk_pay_order'
);
SET @ddl := IF(@pay_idx_exists = 0,
    'ALTER TABLE `a_payment_record` ADD UNIQUE KEY `uk_pay_order` (`order_id`)',
    'SELECT ''uk_pay_order 已存在，跳过'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 3. 自检：两条唯一索引都应出现在结果里
-- ---------------------------------------------------------------------------
SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND index_name IN ('uk_doc_date_step_num', 'uk_pay_order')
GROUP BY table_name, index_name;
