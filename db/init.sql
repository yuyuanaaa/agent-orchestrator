-- =====================================================================
-- agent-orchestrator 建库建表脚本（MySQL 8.0+）
-- 表结构依据 src/main/java/com/agentorchestrator/platform/entity 下的实体类生成，
-- 字段命名遵循 MyBatis-Plus 驼峰 → 下划线映射约定。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS agent_platform DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE agent_platform;

-- ---------------------------------------------------------------------
-- 用户表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `openid`      VARCHAR(64)  DEFAULT NULL COMMENT '微信用户唯一标识',
    `name`        VARCHAR(64)  DEFAULT NULL COMMENT '姓名（登录账号）',
    `phone`       VARCHAR(32)  DEFAULT NULL COMMENT '手机号',
    `password`    VARCHAR(128) DEFAULT NULL COMMENT '登录密码（BCrypt）',
    `sex`         VARCHAR(4)   DEFAULT NULL COMMENT '性别',
    `id_number`   VARCHAR(32)  DEFAULT NULL COMMENT '身份证号',
    `avatar`      VARCHAR(255) DEFAULT NULL COMMENT '头像',
    `create_time` DATETIME     DEFAULT NULL COMMENT '创建时间',
    `delete_id`   INT          DEFAULT 0 COMMENT '删除标识',
    `version`     INT          DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_name` (`name`)
) ENGINE = InnoDB COMMENT ='用户信息';

-- ---------------------------------------------------------------------
-- 菜品及套餐分类表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `category`;
CREATE TABLE `category` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `type`        INT          DEFAULT NULL COMMENT '类型 1:菜品分类 2:套餐分类',
    `name`        VARCHAR(64)  NOT NULL COMMENT '分类名称',
    `sort`        INT          DEFAULT 0 COMMENT '顺序',
    `status`      INT          DEFAULT 1 COMMENT '分类状态 0:禁用 1:启用',
    `create_time` DATETIME     DEFAULT NULL COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT NULL COMMENT '更新时间',
    `create_user` BIGINT       DEFAULT NULL COMMENT '创建人',
    `update_user` BIGINT       DEFAULT NULL COMMENT '修改人',
    `delete_id`   INT          DEFAULT 0 COMMENT '删除标识',
    `version`     INT          DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    KEY `idx_category_name` (`name`)
) ENGINE = InnoDB COMMENT ='菜品及套餐分类';

-- ---------------------------------------------------------------------
-- 菜品表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `dish`;
CREATE TABLE `dish` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(64)   NOT NULL COMMENT '菜品名称',
    `category_id` BIGINT        NOT NULL COMMENT '菜品分类id',
    `price`       DECIMAL(10,2) DEFAULT NULL COMMENT '菜品价格',
    `image`       VARCHAR(255)  DEFAULT NULL COMMENT '图片（相对路径，访问时拼接域名）',
    `description` VARCHAR(512)  DEFAULT NULL COMMENT '描述信息',
    `status`      INT           DEFAULT 1 COMMENT '0:停售 1:起售',
    `create_time` DATETIME      DEFAULT NULL COMMENT '创建时间',
    `update_time` DATETIME      DEFAULT NULL COMMENT '更新时间',
    `create_user` BIGINT        DEFAULT NULL COMMENT '创建人',
    `update_user` BIGINT        DEFAULT NULL COMMENT '修改人',
    `delete_id`   INT           DEFAULT 0 COMMENT '删除标识',
    `version`     INT           DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    KEY `idx_dish_category` (`category_id`),
    UNIQUE KEY `uk_dish_name` (`name`)
) ENGINE = InnoDB COMMENT ='菜品';

-- ---------------------------------------------------------------------
-- 套餐表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `setmeal`;
CREATE TABLE `setmeal` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `category_id` BIGINT        NOT NULL COMMENT '菜品分类id',
    `name`        VARCHAR(64)   NOT NULL COMMENT '套餐名称',
    `price`       DECIMAL(10,2) DEFAULT NULL COMMENT '套餐价格',
    `status`      INT           DEFAULT 1 COMMENT '售卖状态 0:停售 1:起售',
    `description` VARCHAR(512)  DEFAULT NULL COMMENT '描述信息',
    `image`       VARCHAR(255)  DEFAULT NULL COMMENT '图片（相对路径）',
    `create_time` DATETIME      DEFAULT NULL COMMENT '创建时间',
    `update_time` DATETIME      DEFAULT NULL COMMENT '更新时间',
    `create_user` BIGINT        DEFAULT NULL COMMENT '创建人',
    `update_user` BIGINT        DEFAULT NULL COMMENT '修改人',
    `delete_id`   INT           DEFAULT 0 COMMENT '删除标识',
    `version`     INT           DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    KEY `idx_setmeal_category` (`category_id`),
    UNIQUE KEY `uk_setmeal_name` (`name`)
) ENGINE = InnoDB COMMENT ='套餐';

-- ---------------------------------------------------------------------
-- 套餐菜品关系表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `setmeal_dish`;
CREATE TABLE `setmeal_dish` (
    `id`         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `setmeal_id` BIGINT        NOT NULL COMMENT '套餐id',
    `dish_id`    BIGINT        NOT NULL COMMENT '菜品id',
    `name`       VARCHAR(64)   DEFAULT NULL COMMENT '菜品名称（冗余字段）',
    `price`      DECIMAL(10,2) DEFAULT NULL COMMENT '菜品单价（冗余字段）',
    `copies`     INT           DEFAULT 1 COMMENT '菜品份数',
    `delete_id`  INT           DEFAULT 0 COMMENT '删除标识',
    `version`    INT           DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    KEY `idx_sd_setmeal` (`setmeal_id`),
    KEY `idx_sd_dish` (`dish_id`),
    KEY `idx_sd_name` (`name`)
) ENGINE = InnoDB COMMENT ='套餐菜品关系';

-- ---------------------------------------------------------------------
-- 订单表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `orders`;
CREATE TABLE `orders` (
    `id`                      BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `number`                  VARCHAR(64)   DEFAULT NULL COMMENT '订单号',
    `status`                  INT           DEFAULT 1 COMMENT '订单状态 1:待付款 2:待接单 3:已接单 4:派送中 5:已完成 6:已取消 7:退款',
    `user_id`                 BIGINT        NOT NULL COMMENT '下单用户',
    `address_book_id`         BIGINT        DEFAULT NULL COMMENT '地址id',
    `order_time`              DATETIME      DEFAULT NULL COMMENT '下单时间',
    `checkout_time`           DATETIME      DEFAULT NULL COMMENT '结账时间',
    `pay_method`              INT           DEFAULT NULL COMMENT '支付方式 1:微信 2:支付宝',
    `pay_status`              INT           DEFAULT 0 COMMENT '支付状态 0:未支付 1:已支付 2:退款',
    `amount`                  DECIMAL(10,2) DEFAULT NULL COMMENT '实收金额',
    `remark`                  VARCHAR(255)  DEFAULT NULL COMMENT '备注',
    `phone`                   VARCHAR(32)   DEFAULT NULL COMMENT '手机号',
    `address`                 VARCHAR(255)  DEFAULT NULL COMMENT '地址',
    `user_name`               VARCHAR(64)   DEFAULT NULL COMMENT '用户名称',
    `consignee`               VARCHAR(64)   DEFAULT NULL COMMENT '收货人',
    `cancel_reason`           VARCHAR(255)  DEFAULT NULL COMMENT '订单取消原因',
    `rejection_reason`        VARCHAR(255)  DEFAULT NULL COMMENT '订单拒绝原因',
    `cancel_time`             DATETIME      DEFAULT NULL COMMENT '订单取消时间',
    `estimated_delivery_time` DATETIME      DEFAULT NULL COMMENT '预计送达时间',
    `delivery_status`         INT           DEFAULT 1 COMMENT '配送状态 1:立即送出 0:选择具体时间',
    `delivery_time`           DATETIME      DEFAULT NULL COMMENT '送达时间',
    `pack_amount`             INT           DEFAULT NULL COMMENT '打包费',
    `tableware_number`        INT           DEFAULT NULL COMMENT '餐具数量',
    `tableware_status`        INT           DEFAULT NULL COMMENT '餐具数量状态 1:按餐量提供 0:选择具体数量',
    `delete_id`               INT           DEFAULT 0 COMMENT '删除标识',
    `version`                 INT           DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_orders_number` (`number`),
    KEY `idx_orders_user` (`user_id`),
    KEY `idx_orders_phone` (`phone`)
) ENGINE = InnoDB COMMENT ='订单';

-- ---------------------------------------------------------------------
-- 订单明细表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `order_detail`;
CREATE TABLE `order_detail` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(64)   DEFAULT NULL COMMENT '名字',
    `image`       VARCHAR(255)  DEFAULT NULL COMMENT '图片',
    `order_id`    BIGINT        NOT NULL COMMENT '订单id',
    `dish_id`     BIGINT        DEFAULT NULL COMMENT '菜品id（-1 表示该明细是套餐项而非菜品项；判定是否为菜品项统一用 dish_id > 0，真实自增主键从 1 开始）',
    `setmeal_id`  BIGINT        DEFAULT NULL COMMENT '套餐id（-1 表示该明细是菜品项而非套餐项；判定是否为套餐项统一用 setmeal_id > 0）',
    `dish_flavor` VARCHAR(64)   DEFAULT NULL COMMENT '口味',
    `number`      INT           DEFAULT 1 COMMENT '数量',
    `amount`      DECIMAL(10,2) DEFAULT NULL COMMENT '金额',
    `delete_id`   INT           DEFAULT 0 COMMENT '删除标识',
    `version`     INT           DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    KEY `idx_od_order` (`order_id`)
) ENGINE = InnoDB COMMENT ='订单明细';

-- =====================================================================
-- 示例数据（可选执行，便于快速体验查询菜品 / 套餐 / 下单链路）
-- =====================================================================

-- 分类
INSERT INTO `category` (`type`, `name`, `sort`, `status`, `create_time`) VALUES
(1, '主食', 1, 1, NOW()),
(1, '小吃', 2, 1, NOW()),
(1, '饮品', 3, 1, NOW()),
(2, '商务套餐', 1, 1, NOW());

-- 菜品
INSERT INTO `dish` (`name`, `category_id`, `price`, `description`, `status`, `create_time`) VALUES
('招牌牛肉饭', 1, 28.00, '精选黄牛肉，慢炖两小时', 1, NOW()),
('香辣鸡腿堡', 2, 18.50, '现炸鸡腿排，微辣', 1, NOW()),
('薯条（大份）', 2, 9.00, '现切现炸', 1, NOW()),
('冰镇柠檬茶', 3, 8.00, '手打柠檬，清爽解腻', 1, NOW());

-- 套餐
INSERT INTO `setmeal` (`category_id`, `name`, `price`, `status`, `description`, `create_time`) VALUES
(4, '单人工作餐A', 32.00, 1, '招牌牛肉饭 + 冰镇柠檬茶', NOW()),
(4, '双人分享餐B', 56.00, 1, '招牌牛肉饭 + 香辣鸡腿堡 + 薯条 + 两杯柠檬茶', NOW());

-- 套餐-菜品关系
INSERT INTO `setmeal_dish` (`setmeal_id`, `dish_id`, `name`, `price`, `copies`) VALUES
(1, 1, '招牌牛肉饭', 28.00, 1),
(1, 4, '冰镇柠檬茶', 8.00, 1),
(2, 1, '招牌牛肉饭', 28.00, 1),
(2, 2, '香辣鸡腿堡', 18.50, 1),
(2, 3, '薯条（大份）', 9.00, 1),
(2, 4, '冰镇柠檬茶', 8.00, 2);

-- 用户说明：不在此处插入用户。
-- 首次调用 POST /user/login 用新用户名登录会自动注册（密码 BCrypt 加密存储），
-- 无需手工造数。
