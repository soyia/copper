
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `acceptance_bill` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `bill_no` varchar(64) NOT NULL,
  `instrument_no` varchar(128) NOT NULL,
  `bill_type` varchar(32) NOT NULL COMMENT 'BANK_ACCEPTANCE, COMMERCIAL_ACCEPTANCE',
  `customer_id` bigint unsigned NOT NULL,
  `sales_order_id` bigint unsigned NOT NULL,
  `face_amount` decimal(18,2) NOT NULL,
  `issue_date` date NOT NULL,
  `due_date` date NOT NULL,
  `received_date` date NOT NULL,
  `drawer` varchar(255) NOT NULL DEFAULT '',
  `acceptor` varchar(255) NOT NULL DEFAULT '',
  `settlement_payment_id` bigint unsigned NOT NULL DEFAULT '0',
  `status` varchar(24) NOT NULL DEFAULT 'HELD' COMMENT 'HELD, DISCOUNTED, MATURED, ENDORSED, BOUNCED',
  `discount_date` date DEFAULT NULL,
  `discount_bank` varchar(255) NOT NULL DEFAULT '',
  `discount_fee` decimal(18,2) NOT NULL DEFAULT '0.00',
  `net_proceeds` decimal(18,2) NOT NULL DEFAULT '0.00',
  `disposed_at` timestamp NULL DEFAULT NULL,
  `memo` varchar(500) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_acceptance_bill_no` (`bill_no`),
  UNIQUE KEY `uk_acceptance_instrument_no` (`instrument_no`),
  KEY `idx_acceptance_order` (`sales_order_id`),
  KEY `idx_acceptance_customer` (`customer_id`,`status`),
  KEY `idx_acceptance_due` (`status`,`due_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='应收承兑票据';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `acceptance_bill_event` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `event_no` varchar(64) NOT NULL,
  `bill_id` bigint unsigned NOT NULL,
  `event_type` varchar(24) NOT NULL COMMENT 'RECEIVE, DISCOUNT, MATURITY, ENDORSE, BOUNCE',
  `occurred_date` date NOT NULL,
  `cash_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `fee_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `counterparty` varchar(255) NOT NULL DEFAULT '',
  `memo` varchar(500) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_acceptance_event_no` (`event_no`),
  KEY `idx_acceptance_event_bill` (`bill_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='承兑票据事件流水';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `customer_advance_refund` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `refund_no` varchar(64) NOT NULL,
  `customer_id` bigint unsigned NOT NULL,
  `amount` decimal(18,2) NOT NULL,
  `refund_method` varchar(16) NOT NULL DEFAULT 'BANK',
  `refunded_date` date NOT NULL,
  `memo` varchar(500) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_advance_refund_no` (`refund_no`),
  KEY `idx_customer_advance_refund_customer` (`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户预收款退款';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inbound_attachment` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `inbound_id` bigint unsigned NOT NULL,
  `file_name` varchar(255) NOT NULL,
  `content_type` varchar(100) NOT NULL,
  `file_size` bigint unsigned NOT NULL,
  `sha256` char(64) NOT NULL,
  `storage_path` varchar(1000) DEFAULT NULL,
  `file_content` longblob COMMENT '仅用于升级时迁移旧数据，新附件禁止写入',
  `uploaded_by` bigint unsigned NOT NULL DEFAULT '0',
  `uploaded_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inbound_attachment_hash` (`inbound_id`,`sha256`),
  KEY `idx_inbound_attachment_order` (`inbound_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='入库单发货单图片附件';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inbound_audit_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `inbound_id` bigint unsigned NOT NULL,
  `action` varchar(16) NOT NULL,
  `reason` varchar(500) NOT NULL DEFAULT '',
  `operator_id` bigint unsigned NOT NULL DEFAULT '0',
  `operator_name` varchar(100) NOT NULL DEFAULT '系统用户',
  `snapshot_id` bigint unsigned NOT NULL DEFAULT '0',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_inbound_audit_log_order` (`inbound_id`,`id`),
  KEY `idx_inbound_audit_log_snapshot` (`snapshot_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='入库单提交撤回与审核轨迹';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inbound_item` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `inbound_id` bigint unsigned NOT NULL,
  `product_id` bigint unsigned NOT NULL,
  `product_sku_snapshot` varchar(128) NOT NULL DEFAULT '',
  `product_name_snapshot` varchar(255) NOT NULL DEFAULT '',
  `product_spec_snapshot` varchar(1000) NOT NULL DEFAULT '',
  `purchase_order_item_id` bigint unsigned NOT NULL DEFAULT '0',
  `requested_quantity` decimal(18,6) NOT NULL DEFAULT '0.000000',
  `requested_unit` varchar(64) NOT NULL DEFAULT '',
  `inventory_batch_id` bigint unsigned NOT NULL DEFAULT '0',
  `batch_no` varchar(64) NOT NULL,
  `serial_number` varchar(64) NOT NULL DEFAULT '',
  `quantity` decimal(18,3) NOT NULL,
  `base_unit` varchar(64) NOT NULL DEFAULT '',
  `package_quantity` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `price_unit` varchar(64) NOT NULL DEFAULT '',
  `pricing_quantity` decimal(18,6) NOT NULL DEFAULT '0.000000',
  `package_unit` varchar(64) NOT NULL DEFAULT '',
  `packaging_plan_json` longtext NOT NULL,
  `packaging_effective_revision_id` bigint unsigned NOT NULL DEFAULT '0',
  `packaging_snapshot_hash` char(64) NOT NULL DEFAULT '',
  `package_barcode` varchar(128) NOT NULL DEFAULT '',
  `unit_cost` decimal(18,4) NOT NULL,
  `amount` decimal(18,2) NOT NULL,
  `production_date` date DEFAULT NULL,
  `shelf_life_mode` varchar(16) NOT NULL DEFAULT 'NONE',
  `shelf_life_value` int unsigned DEFAULT NULL,
  `shelf_life_unit` varchar(16) NOT NULL DEFAULT '',
  `expiration_date` date DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_inbound_item_order` (`inbound_id`),
  KEY `idx_inbound_item_product` (`product_id`),
  KEY `idx_inbound_item_expiration` (`expiration_date`),
  KEY `idx_inbound_purchase_item` (`purchase_order_item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='进货入库明细';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inbound_order` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `order_no` varchar(64) NOT NULL,
  `purchase_order_id` bigint unsigned NOT NULL DEFAULT '0',
  `supplier_id` bigint unsigned NOT NULL,
  `warehouse_id` bigint unsigned NOT NULL,
  `source_type` varchar(32) NOT NULL DEFAULT 'NO_ORDER',
  `source_note` varchar(255) NOT NULL DEFAULT '',
  `business_date` date NOT NULL,
  `send_date` date DEFAULT NULL,
  `send_number` varchar(64) NOT NULL DEFAULT '',
  `invoice_ids` varchar(255) NOT NULL DEFAULT '',
  `original_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `discount_rate` decimal(6,2) NOT NULL DEFAULT '0.00',
  `adjustment_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `status` varchar(16) NOT NULL DEFAULT 'DRAFT',
  `rejection_reason` varchar(500) NOT NULL DEFAULT '',
  `submitted_at` timestamp NULL DEFAULT NULL,
  `withdrawn_at` timestamp NULL DEFAULT NULL,
  `rejected_at` timestamp NULL DEFAULT NULL,
  `audited_at` timestamp NULL DEFAULT NULL,
  `stocked_at` timestamp NULL DEFAULT NULL,
  `total_quantity` decimal(18,3) NOT NULL DEFAULT '0.000',
  `total_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `payable_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `paid_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `payment_status` varchar(16) NOT NULL DEFAULT 'UNPAID',
  `memo` varchar(500) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inbound_order_no` (`order_no`),
  KEY `idx_inbound_supplier` (`supplier_id`),
  KEY `idx_inbound_warehouse` (`warehouse_id`),
  KEY `idx_inbound_purchase_order` (`purchase_order_id`),
  KEY `idx_inbound_payment_status` (`payment_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='进货入库单';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inbound_package_instance` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `inbound_item_id` bigint unsigned NOT NULL,
  `package_code` varchar(128) NOT NULL,
  `unit_name` varchar(64) NOT NULL,
  `actual_base_quantity` decimal(18,6) NOT NULL,
  `base_unit` varchar(64) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inbound_package_instance_code` (`inbound_item_id`,`package_code`),
  KEY `idx_inbound_package_instance_item` (`inbound_item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inbound_review_snapshot` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `inbound_id` bigint unsigned NOT NULL,
  `round_no` int unsigned NOT NULL,
  `order_no` varchar(64) NOT NULL,
  `purchase_order_id` bigint unsigned NOT NULL DEFAULT '0',
  `purchase_order_no` varchar(64) NOT NULL DEFAULT '',
  `supplier_id` bigint unsigned NOT NULL,
  `supplier_name` varchar(255) NOT NULL,
  `warehouse_id` bigint unsigned NOT NULL,
  `warehouse_name` varchar(128) NOT NULL,
  `source_type` varchar(32) NOT NULL,
  `source_note` varchar(500) NOT NULL DEFAULT '',
  `business_date` date NOT NULL,
  `send_date` date DEFAULT NULL,
  `send_number` varchar(128) NOT NULL DEFAULT '',
  `original_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `discount_rate` decimal(9,4) NOT NULL DEFAULT '0.0000',
  `adjustment_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `total_quantity` decimal(18,3) NOT NULL DEFAULT '0.000',
  `quantity_summary` varchar(1000) NOT NULL DEFAULT '',
  `total_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `memo` varchar(500) NOT NULL DEFAULT '',
  `snapshot_hash` char(64) NOT NULL,
  `capture_source` varchar(24) NOT NULL DEFAULT 'SUBMIT',
  `submitted_by_name` varchar(100) NOT NULL DEFAULT '系统用户',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inbound_review_snapshot_round` (`inbound_id`,`round_no`),
  KEY `idx_inbound_review_snapshot_order` (`inbound_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='入库审核批次不可变快照';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inbound_review_snapshot_attachment` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `snapshot_id` bigint unsigned NOT NULL,
  `original_attachment_id` bigint unsigned NOT NULL DEFAULT '0',
  `file_name` varchar(255) NOT NULL,
  `content_type` varchar(100) NOT NULL,
  `file_size` bigint unsigned NOT NULL,
  `sha256` char(64) NOT NULL,
  `storage_path` varchar(1000) DEFAULT NULL,
  `file_content` longblob COMMENT '仅用于升级时迁移旧数据，新附件禁止写入',
  `original_uploaded_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_inbound_review_snapshot_attachment` (`snapshot_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='入库审核批次发货单影像快照';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inbound_review_snapshot_item` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `snapshot_id` bigint unsigned NOT NULL,
  `line_no` int unsigned NOT NULL,
  `product_id` bigint unsigned NOT NULL,
  `sku` varchar(128) NOT NULL DEFAULT '',
  `product_name` varchar(255) NOT NULL DEFAULT '',
  `spec` varchar(1000) NOT NULL DEFAULT '',
  `batch_no` varchar(64) NOT NULL DEFAULT '',
  `serial_number` varchar(64) NOT NULL DEFAULT '',
  `requested_quantity` decimal(18,6) NOT NULL DEFAULT '0.000000',
  `requested_unit` varchar(64) NOT NULL DEFAULT '',
  `quantity` decimal(18,3) NOT NULL DEFAULT '0.000',
  `base_unit` varchar(64) NOT NULL DEFAULT '',
  `package_quantity` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `package_unit` varchar(64) NOT NULL DEFAULT '',
  `price_unit` varchar(64) NOT NULL DEFAULT '',
  `pricing_quantity` decimal(18,6) NOT NULL DEFAULT '0.000000',
  `package_barcode` varchar(128) NOT NULL DEFAULT '',
  `unit_cost` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `production_date` date DEFAULT NULL,
  `shelf_life_mode` varchar(16) NOT NULL DEFAULT 'NONE',
  `shelf_life_value` int unsigned DEFAULT NULL,
  `shelf_life_unit` varchar(16) NOT NULL DEFAULT '',
  `expiration_date` date DEFAULT NULL,
  `packaging_plan_json` longtext NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inbound_review_snapshot_item_line` (`snapshot_id`,`line_no`),
  KEY `idx_inbound_review_snapshot_item` (`snapshot_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='入库审核批次商品快照';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inventory_batch` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `product_id` bigint unsigned NOT NULL,
  `warehouse_id` bigint unsigned NOT NULL,
  `batch_no` varchar(64) NOT NULL,
  `serial_number` varchar(64) NOT NULL DEFAULT '',
  `source_type` varchar(32) NOT NULL,
  `source_id` bigint unsigned NOT NULL,
  `base_unit` varchar(64) NOT NULL DEFAULT '',
  `packaging_version_no` int unsigned NOT NULL DEFAULT '0',
  `packaging_effective_revision_id` bigint unsigned NOT NULL DEFAULT '0',
  `packaging_profile_snapshot_json` longtext,
  `packaging_snapshot_hash` char(64) NOT NULL DEFAULT '',
  `packaging_tracking_mode` varchar(24) NOT NULL DEFAULT 'QUANTITY_ONLY',
  `package_barcode` varchar(128) NOT NULL DEFAULT '',
  `quantity_in` decimal(18,3) NOT NULL,
  `quantity_available` decimal(18,3) NOT NULL,
  `package_quantity_in` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `package_quantity_available` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `unit_cost` decimal(18,4) NOT NULL,
  `amount_cost` decimal(18,2) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'AVAILABLE',
  `received_at` date NOT NULL,
  `production_date` date DEFAULT NULL,
  `shelf_life_mode` varchar(16) NOT NULL DEFAULT 'NONE',
  `shelf_life_value` int unsigned DEFAULT NULL,
  `shelf_life_unit` varchar(16) NOT NULL DEFAULT '',
  `expiration_date` date DEFAULT NULL,
  `scan_time` timestamp NULL DEFAULT NULL,
  `shipped_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inventory_batch` (`warehouse_id`,`product_id`,`batch_no`),
  KEY `idx_inventory_product` (`product_id`),
  KEY `idx_inventory_warehouse` (`warehouse_id`),
  KEY `idx_inventory_batch_expiration` (`status`,`expiration_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存批次';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inventory_movement` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `movement_no` varchar(64) NOT NULL,
  `movement_type` varchar(24) NOT NULL COMMENT 'INBOUND, OUTBOUND, ADJUST, CANCEL',
  `product_id` bigint unsigned NOT NULL,
  `warehouse_id` bigint unsigned NOT NULL,
  `batch_id` bigint unsigned NOT NULL,
  `quantity` decimal(18,3) NOT NULL,
  `unit_price` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `ref_type` varchar(32) NOT NULL,
  `ref_id` bigint unsigned NOT NULL,
  `occurred_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `memo` varchar(500) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inventory_movement_no` (`movement_no`),
  KEY `idx_inventory_movement_ref` (`ref_type`,`ref_id`),
  KEY `idx_inventory_movement_batch` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存流水';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inventory_package_balance` (
  `batch_id` bigint unsigned NOT NULL,
  `product_id` bigint unsigned NOT NULL,
  `unit_name` varchar(64) NOT NULL,
  `quantity` decimal(18,6) NOT NULL DEFAULT '0.000000',
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`batch_id`,`unit_name`),
  KEY `idx_inventory_package_product` (`product_id`,`unit_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inventory_package_instance` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `batch_id` bigint unsigned NOT NULL,
  `product_id` bigint unsigned NOT NULL,
  `source_instance_id` bigint unsigned NOT NULL DEFAULT '0',
  `package_code` varchar(128) NOT NULL,
  `unit_name` varchar(64) NOT NULL,
  `actual_base_quantity` decimal(18,6) NOT NULL,
  `base_unit` varchar(64) NOT NULL,
  `status` varchar(24) NOT NULL DEFAULT 'AVAILABLE',
  `outbound_item_id` bigint unsigned NOT NULL DEFAULT '0',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `changed_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inventory_package_instance_code` (`batch_id`,`package_code`),
  KEY `idx_inventory_package_instance_available` (`batch_id`,`status`,`id`),
  KEY `idx_inventory_package_instance_outbound` (`outbound_item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `inventory_package_movement` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `batch_id` bigint unsigned NOT NULL,
  `product_id` bigint unsigned NOT NULL,
  `movement_type` varchar(24) NOT NULL,
  `from_unit` varchar(64) NOT NULL DEFAULT '',
  `from_quantity` decimal(18,6) NOT NULL DEFAULT '0.000000',
  `to_unit` varchar(64) NOT NULL DEFAULT '',
  `to_quantity` decimal(18,6) NOT NULL DEFAULT '0.000000',
  `ref_type` varchar(32) NOT NULL DEFAULT '',
  `ref_id` bigint unsigned NOT NULL DEFAULT '0',
  `memo` varchar(255) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_inventory_package_movement_batch` (`batch_id`,`id`),
  KEY `idx_inventory_package_movement_ref` (`ref_type`,`ref_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `organization_department` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `parent_id` bigint unsigned NOT NULL DEFAULT '0',
  `code` varchar(32) NOT NULL,
  `name` varchar(64) NOT NULL,
  `manager_employee_id` bigint unsigned DEFAULT NULL,
  `sort_no` int NOT NULL DEFAULT '0',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `disabled_reason` varchar(255) NOT NULL DEFAULT '',
  `disabled_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_department_code` (`code`),
  UNIQUE KEY `uk_department_parent_name` (`parent_id`,`name`),
  KEY `idx_department_parent_status` (`parent_id`,`status`,`sort_no`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='部门组织树';
/*!40101 SET character_set_client = @saved_cs_client */;

INSERT INTO `organization_department` (`id`, `parent_id`, `code`, `name`, `manager_employee_id`, `sort_no`, `status`, `disabled_reason`, `disabled_at`, `created_at`, `updated_at`) VALUES (1,0,'ROOT','本企业',NULL,0,'ENABLED','',NULL,'2026-07-12 23:57:52','2026-07-12 23:58:12');
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `organization_employee` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `employee_no` varchar(32) NOT NULL,
  `name` varchar(64) NOT NULL,
  `department_id` bigint unsigned NOT NULL,
  `job_title` varchar(64) NOT NULL DEFAULT '',
  `mobile` varchar(32) NOT NULL DEFAULT '',
  `email` varchar(128) NOT NULL DEFAULT '',
  `hire_date` date DEFAULT NULL,
  `leave_date` date DEFAULT NULL,
  `department_manager` tinyint unsigned NOT NULL DEFAULT '0',
  `employment_status` varchar(16) NOT NULL DEFAULT 'ACTIVE',
  `leave_reason` varchar(255) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_employee_no` (`employee_no`),
  KEY `idx_employee_department_status` (`department_id`,`employment_status`),
  KEY `idx_employee_mobile` (`mobile`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='人员档案';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `organization_employee_number_sequence` (
  `id` tinyint unsigned NOT NULL,
  `next_value` bigint unsigned NOT NULL,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='人员编码序列';
/*!40101 SET character_set_client = @saved_cs_client */;

INSERT INTO `organization_employee_number_sequence` (`id`, `next_value`, `updated_at`) VALUES (1,1,'2026-07-13 03:31:02');
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `outbound_advance_allocation` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `outbound_id` bigint unsigned NOT NULL,
  `customer_id` bigint unsigned NOT NULL,
  `amount` decimal(18,2) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'RESERVED' COMMENT 'RESERVED, APPLIED, RELEASED',
  `applied_payment_id` bigint unsigned NOT NULL DEFAULT '0',
  `memo` varchar(500) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_outbound_advance_outbound` (`outbound_id`,`status`),
  KEY `idx_outbound_advance_customer` (`customer_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='直接出库客户预收款占用';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `outbound_allocation` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `outbound_id` bigint unsigned NOT NULL,
  `outbound_item_id` bigint unsigned NOT NULL,
  `sales_order_item_id` bigint unsigned NOT NULL DEFAULT '0',
  `product_id` bigint unsigned NOT NULL,
  `inventory_batch_id` bigint unsigned NOT NULL,
  `quantity` decimal(18,3) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_outbound_allocation_order` (`outbound_id`),
  KEY `idx_outbound_allocation_item` (`outbound_item_id`),
  KEY `idx_outbound_allocation_batch` (`inventory_batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='出库库存分配';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `outbound_item` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `outbound_id` bigint unsigned NOT NULL,
  `sales_order_item_id` bigint unsigned NOT NULL DEFAULT '0',
  `product_id` bigint unsigned NOT NULL,
  `product_sku_snapshot` varchar(128) NOT NULL DEFAULT '',
  `product_name_snapshot` varchar(255) NOT NULL DEFAULT '',
  `product_spec_snapshot` varchar(1000) NOT NULL DEFAULT '',
  `requested_quantity` decimal(18,6) NOT NULL DEFAULT '0.000000',
  `requested_unit` varchar(64) NOT NULL DEFAULT '',
  `batch_id` bigint unsigned DEFAULT NULL,
  `quantity` decimal(18,3) NOT NULL,
  `base_unit` varchar(64) NOT NULL DEFAULT '',
  `package_quantity` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `package_unit` varchar(64) NOT NULL DEFAULT '',
  `packaging_plan_json` longtext NOT NULL,
  `packaging_effective_revision_id` bigint unsigned NOT NULL DEFAULT '0',
  `packaging_snapshot_hash` char(64) NOT NULL DEFAULT '',
  `selected_package_instance_ids_json` longtext,
  `price_unit` varchar(64) NOT NULL DEFAULT '',
  `pricing_quantity` decimal(18,6) NOT NULL DEFAULT '0.000000',
  `unit_price` decimal(18,4) NOT NULL,
  `amount` decimal(18,2) NOT NULL,
  `returned_quantity` decimal(24,6) NOT NULL DEFAULT '0.000000',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_outbound_item_order` (`outbound_id`),
  KEY `idx_outbound_item_product` (`product_id`),
  KEY `idx_outbound_item_batch` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售出库明细';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `outbound_order` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `order_no` varchar(64) NOT NULL,
  `sales_order_id` bigint unsigned NOT NULL DEFAULT '0',
  `source_type` varchar(24) NOT NULL DEFAULT 'DIRECT',
  `source_channel` varchar(24) NOT NULL DEFAULT 'OTHER',
  `entry_mode` varchar(24) NOT NULL DEFAULT 'NORMAL',
  `completeness_status` varchar(24) NOT NULL DEFAULT 'COMPLETE',
  `settlement_status` varchar(24) NOT NULL DEFAULT 'SETTLED',
  `customer_id` bigint unsigned NOT NULL,
  `address_id` bigint unsigned NOT NULL DEFAULT '0',
  `address_snapshot` varchar(1000) NOT NULL DEFAULT '',
  `warehouse_id` bigint unsigned NOT NULL,
  `business_date` date NOT NULL,
  `actual_shipped_at` datetime DEFAULT NULL,
  `late_entry_reason` varchar(500) NOT NULL DEFAULT '',
  `allow_over_shipment` tinyint unsigned NOT NULL DEFAULT '0',
  `over_shipment_reason` varchar(500) NOT NULL DEFAULT '',
  `idempotency_key` varchar(64) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'SHIPPED',
  `total_quantity` decimal(18,3) NOT NULL DEFAULT '0.000',
  `package_quantity` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `original_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `discount_rate` decimal(6,2) NOT NULL DEFAULT '0.00',
  `adjustment_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `total_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `receivable_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `paid_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `payment_status` varchar(16) NOT NULL DEFAULT 'UNPAID',
  `shipment_payment_policy` varchar(24) NOT NULL DEFAULT 'NONE',
  `shipment_control_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `shipment_required_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `shipment_funded_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `shipped_at` timestamp NULL DEFAULT NULL,
  `confirmed_at` timestamp NULL DEFAULT NULL,
  `settled_at` timestamp NULL DEFAULT NULL,
  `cancelled_at` timestamp NULL DEFAULT NULL,
  `reversal_no` varchar(64) NOT NULL DEFAULT '',
  `reversal_reason` varchar(500) NOT NULL DEFAULT '',
  `reversed_at` timestamp NULL DEFAULT NULL,
  `memo` varchar(500) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_outbound_order_no` (`order_no`),
  UNIQUE KEY `uk_outbound_idempotency_key` (`idempotency_key`),
  KEY `idx_outbound_customer` (`customer_id`),
  KEY `idx_outbound_warehouse` (`warehouse_id`),
  KEY `idx_outbound_followup` (`status`,`completeness_status`,`settlement_status`,`business_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售出库单';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `packaging_template` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `code` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `description` varchar(500) NOT NULL DEFAULT '',
  `current_revision_id` bigint unsigned NOT NULL DEFAULT '0',
  `current_revision_no` int unsigned NOT NULL DEFAULT '0',
  `lock_version` int unsigned NOT NULL DEFAULT '0',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `created_by` bigint unsigned NOT NULL DEFAULT '0',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_packaging_template_code` (`code`),
  UNIQUE KEY `uk_packaging_template_name` (`name`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='reusable packaging template';
/*!40101 SET character_set_client = @saved_cs_client */;

INSERT INTO `packaging_template` (`id`, `code`, `name`, `description`, `current_revision_id`, `current_revision_no`, `lock_version`, `status`, `created_by`, `updated_by`, `created_at`, `updated_at`) VALUES (1,'BASE-WEIGHT','基础重量计量','以kg为主库存、采购、销售和发货单位',1,1,1,'ENABLED',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `packaging_template` (`id`, `code`, `name`, `description`, `current_revision_id`, `current_revision_no`, `lock_version`, `status`, `created_by`, `updated_by`, `created_at`, `updated_at`) VALUES (2,'BASE-BOX','基础箱计量','以箱为主库存、采购、销售和发货单位',2,1,1,'ENABLED',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `packaging_template` (`id`, `code`, `name`, `description`, `current_revision_id`, `current_revision_no`, `lock_version`, `status`, `created_by`, `updated_by`, `created_at`, `updated_at`) VALUES (3,'BASE-PIECE','基础件计量','以件为主库存、采购、销售和发货单位',3,1,1,'ENABLED',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `packaging_template` (`id`, `code`, `name`, `description`, `current_revision_id`, `current_revision_no`, `lock_version`, `status`, `created_by`, `updated_by`, `created_at`, `updated_at`) VALUES (4,'BASE-STICK','基础支计量','以支为主库存、采购、销售和发货单位',4,1,1,'ENABLED',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `packaging_template_revision` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `template_id` bigint unsigned NOT NULL,
  `revision_no` int unsigned NOT NULL,
  `revision_status` varchar(16) NOT NULL DEFAULT 'PUBLISHED',
  `base_unit_id` bigint unsigned NOT NULL DEFAULT '0',
  `base_unit` varchar(64) NOT NULL,
  `minimum_sales_unit_id` bigint unsigned NOT NULL DEFAULT '0',
  `minimum_sales_unit` varchar(64) NOT NULL,
  `minimum_outbound_unit_id` bigint unsigned NOT NULL DEFAULT '0',
  `minimum_outbound_unit` varchar(64) NOT NULL,
  `default_purchase_unit_id` bigint unsigned NOT NULL DEFAULT '0',
  `default_purchase_unit` varchar(64) NOT NULL,
  `default_sales_unit_id` bigint unsigned NOT NULL DEFAULT '0',
  `default_sales_unit` varchar(64) NOT NULL,
  `rounding_policy` varchar(24) NOT NULL DEFAULT 'REJECT',
  `content_hash` char(64) NOT NULL DEFAULT '',
  `change_reason` varchar(500) NOT NULL DEFAULT '',
  `created_by` bigint unsigned NOT NULL DEFAULT '0',
  `published_by` bigint unsigned NOT NULL DEFAULT '0',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `published_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_packaging_template_revision` (`template_id`,`revision_no`),
  KEY `idx_packaging_template_revision_status` (`template_id`,`revision_status`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='immutable packaging template revision';
/*!40101 SET character_set_client = @saved_cs_client */;

INSERT INTO `packaging_template_revision` (`id`, `template_id`, `revision_no`, `revision_status`, `base_unit_id`, `base_unit`, `minimum_sales_unit_id`, `minimum_sales_unit`, `minimum_outbound_unit_id`, `minimum_outbound_unit`, `default_purchase_unit_id`, `default_purchase_unit`, `default_sales_unit_id`, `default_sales_unit`, `rounding_policy`, `content_hash`, `change_reason`, `created_by`, `published_by`, `created_at`, `published_at`) VALUES (1,1,1,'PUBLISHED',1,'kg',1,'kg',1,'kg',1,'kg',1,'kg','REJECT','39e4f2d732211816b877ac3cd8c8f34eca71ec7dbb88f6cf6fefc52b404f485b','系统迁移生成基础模板',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `packaging_template_revision` (`id`, `template_id`, `revision_no`, `revision_status`, `base_unit_id`, `base_unit`, `minimum_sales_unit_id`, `minimum_sales_unit`, `minimum_outbound_unit_id`, `minimum_outbound_unit`, `default_purchase_unit_id`, `default_purchase_unit`, `default_sales_unit_id`, `default_sales_unit`, `rounding_policy`, `content_hash`, `change_reason`, `created_by`, `published_by`, `created_at`, `published_at`) VALUES (2,2,1,'PUBLISHED',4,'箱',4,'箱',4,'箱',4,'箱',4,'箱','REJECT','7aa99cd77e5457f0394b2d6d44d278190c8233b115302a63194a3e451bd3d923','系统迁移生成基础模板',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `packaging_template_revision` (`id`, `template_id`, `revision_no`, `revision_status`, `base_unit_id`, `base_unit`, `minimum_sales_unit_id`, `minimum_sales_unit`, `minimum_outbound_unit_id`, `minimum_outbound_unit`, `default_purchase_unit_id`, `default_purchase_unit`, `default_sales_unit_id`, `default_sales_unit`, `rounding_policy`, `content_hash`, `change_reason`, `created_by`, `published_by`, `created_at`, `published_at`) VALUES (3,3,1,'PUBLISHED',204,'件',204,'件',204,'件',204,'件',204,'件','REJECT','493054fcbccbec1394e4b1e4ea68f316811d8c36efe6371e576a9ff7b2022e58','系统迁移生成基础模板',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `packaging_template_revision` (`id`, `template_id`, `revision_no`, `revision_status`, `base_unit_id`, `base_unit`, `minimum_sales_unit_id`, `minimum_sales_unit`, `minimum_outbound_unit_id`, `minimum_outbound_unit`, `default_purchase_unit_id`, `default_purchase_unit`, `default_sales_unit_id`, `default_sales_unit`, `rounding_policy`, `content_hash`, `change_reason`, `created_by`, `published_by`, `created_at`, `published_at`) VALUES (4,4,1,'PUBLISHED',205,'支',205,'支',205,'支',205,'支',205,'支','REJECT','90570987b8e21cbe5687cc2b20939921f3ff0332c772ebbe3d821f891edd8603','系统迁移生成基础模板',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `packaging_template_rule` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `template_revision_id` bigint unsigned NOT NULL,
  `rule_key` varchar(128) NOT NULL,
  `parent_unit_id` bigint unsigned NOT NULL DEFAULT '0',
  `parent_unit` varchar(64) NOT NULL,
  `child_unit_id` bigint unsigned NOT NULL DEFAULT '0',
  `child_unit` varchar(64) NOT NULL,
  `conversion_mode` varchar(24) NOT NULL DEFAULT 'FIXED',
  `fixed_child_quantity` decimal(24,6) DEFAULT NULL,
  `reference_base_quantity` decimal(24,6) DEFAULT NULL,
  `breakable` tinyint unsigned NOT NULL DEFAULT '0',
  `repackable` tinyint unsigned NOT NULL DEFAULT '0',
  `purchase_allowed` tinyint unsigned NOT NULL DEFAULT '1',
  `sales_allowed` tinyint unsigned NOT NULL DEFAULT '1',
  `outbound_allowed` tinyint unsigned NOT NULL DEFAULT '1',
  `integer_only` tinyint unsigned NOT NULL DEFAULT '1',
  `sort_no` int NOT NULL DEFAULT '0',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_packaging_template_rule` (`template_revision_id`,`rule_key`),
  KEY `idx_packaging_template_rule_revision` (`template_revision_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='packaging template revision rule';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `partner` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `business_no` varchar(32) NOT NULL,
  `type` varchar(16) NOT NULL COMMENT 'CUSTOMER or SUPPLIER',
  `type_mask` tinyint unsigned NOT NULL DEFAULT '1' COMMENT '1 customer, 2 supplier, 3 both',
  `entity_type` varchar(24) NOT NULL DEFAULT 'COMPANY',
  `name` varchar(128) NOT NULL,
  `alias_name` varchar(128) NOT NULL DEFAULT '',
  `credit_code` varchar(128) NOT NULL DEFAULT '',
  `contact_person` varchar(64) NOT NULL DEFAULT '',
  `phone` varchar(32) NOT NULL DEFAULT '',
  `address` varchar(255) NOT NULL DEFAULT '',
  `credit_limit` decimal(18,2) NOT NULL DEFAULT '0.00',
  `top_fixed` tinyint unsigned NOT NULL DEFAULT '0',
  `cooperation_start_date` date DEFAULT NULL,
  `establishment_date` date DEFAULT NULL,
  `source` varchar(64) NOT NULL DEFAULT '',
  `tags` varchar(255) NOT NULL DEFAULT '',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `disabled_reason` varchar(255) NOT NULL DEFAULT '',
  `disabled_at` timestamp NULL DEFAULT NULL,
  `memo` varchar(500) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `normalized_name` varchar(128) GENERATED ALWAYS AS (lower(trim(`name`))) STORED,
  `normalized_credit_code` varchar(128) GENERATED ALWAYS AS (nullif(lower(trim(`credit_code`)),_utf8mb4'')) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_partner_business_no` (`business_no`),
  UNIQUE KEY `uk_partner_normalized_name` (`normalized_name`),
  UNIQUE KEY `uk_partner_normalized_credit_code` (`normalized_credit_code`),
  KEY `idx_partner_status_role` (`status`,`type_mask`),
  KEY `idx_partner_top_status` (`top_fixed`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户供应商';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `partner_account` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `partner_id` bigint unsigned NOT NULL,
  `account_type` varchar(32) NOT NULL DEFAULT 'TRADE',
  `balance` decimal(18,2) NOT NULL DEFAULT '0.00',
  `frozen_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `credit_limit` decimal(18,2) NOT NULL DEFAULT '0.00',
  `opposite_balance` decimal(18,2) NOT NULL DEFAULT '0.00',
  `opposite_credit_limit` decimal(18,2) NOT NULL DEFAULT '0.00',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account_partner_type` (`partner_id`,`account_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='往来账户';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `partner_account_action` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `partner_id` bigint unsigned NOT NULL DEFAULT '0',
  `category` varchar(32) NOT NULL DEFAULT '',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `action_type` varchar(32) NOT NULL DEFAULT '',
  `bank` varchar(128) NOT NULL DEFAULT '',
  `bank_account_name` varchar(128) NOT NULL DEFAULT '',
  `bank_account_no` varchar(128) NOT NULL DEFAULT '',
  `bank_serial_no` varchar(128) NOT NULL DEFAULT '',
  `occurred_at` timestamp NULL DEFAULT NULL,
  `memo` varchar(500) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_account_action_partner` (`partner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账户收付款动作';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `partner_account_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `partner_id` bigint unsigned NOT NULL DEFAULT '0',
  `account_type` varchar(32) NOT NULL DEFAULT '',
  `source_type` varchar(32) NOT NULL DEFAULT '',
  `source_id` bigint unsigned NOT NULL DEFAULT '0',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `amount_before` decimal(18,2) NOT NULL DEFAULT '0.00',
  `amount_after` decimal(18,2) NOT NULL DEFAULT '0.00',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_account_log_partner` (`partner_id`),
  KEY `idx_account_log_source` (`source_type`,`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账户流水';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `partner_address` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `partner_id` bigint unsigned NOT NULL,
  `name` varchar(128) NOT NULL DEFAULT '',
  `address_type` varchar(24) NOT NULL DEFAULT 'RECEIVING',
  `province_code` varchar(16) NOT NULL DEFAULT '',
  `city_code` varchar(16) NOT NULL DEFAULT '',
  `district_code` varchar(16) NOT NULL DEFAULT '',
  `detail` varchar(255) NOT NULL DEFAULT '',
  `contact_person` varchar(64) NOT NULL DEFAULT '',
  `phone` varchar(32) NOT NULL DEFAULT '',
  `first_choice` tinyint unsigned NOT NULL DEFAULT '0',
  `memo` varchar(500) NOT NULL DEFAULT '',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_address_partner` (`partner_id`),
  KEY `idx_address_partner_type` (`partner_id`,`address_type`,`first_choice`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='收货地址';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `partner_bank_account` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `partner_id` bigint unsigned NOT NULL,
  `account_name` varchar(128) NOT NULL DEFAULT '',
  `bank_name` varchar(128) NOT NULL DEFAULT '',
  `bank_account` varchar(128) NOT NULL DEFAULT '',
  `first_choice` tinyint unsigned NOT NULL DEFAULT '0',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `memo` varchar(500) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_partner_bank_account` (`partner_id`,`first_choice`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='往来单位银行账户';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `partner_contact` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `partner_id` bigint unsigned NOT NULL,
  `name` varchar(64) NOT NULL,
  `job_title` varchar(64) NOT NULL DEFAULT '',
  `department` varchar(64) NOT NULL DEFAULT '',
  `gender` tinyint unsigned NOT NULL DEFAULT '0',
  `phone` varchar(32) NOT NULL DEFAULT '',
  `wechat` varchar(64) NOT NULL DEFAULT '',
  `email` varchar(128) NOT NULL DEFAULT '',
  `preferred_method` varchar(24) NOT NULL DEFAULT 'PHONE',
  `first_choice` tinyint unsigned NOT NULL DEFAULT '0',
  `birthday` date DEFAULT NULL,
  `memo` varchar(500) NOT NULL DEFAULT '',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_contact_partner` (`partner_id`),
  KEY `idx_contact_partner_primary` (`partner_id`,`first_choice`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='联系人';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `partner_customer_profile` (
  `partner_id` bigint unsigned NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `customer_level` varchar(24) NOT NULL DEFAULT 'NORMAL',
  `settlement_method` varchar(32) NOT NULL DEFAULT 'PER_ORDER',
  `settlement_days` int unsigned NOT NULL DEFAULT '0',
  `credit_limit` decimal(18,2) NOT NULL DEFAULT '0.00',
  `tax_included` tinyint unsigned NOT NULL DEFAULT '1',
  `invoice_type` varchar(32) NOT NULL DEFAULT 'NONE',
  `tax_rate` decimal(6,2) NOT NULL DEFAULT '0.00',
  `preferred_products` varchar(500) NOT NULL DEFAULT '',
  `disabled_reason` varchar(255) NOT NULL DEFAULT '',
  `disabled_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`partner_id`),
  KEY `idx_partner_customer_role_status` (`status`,`partner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户角色资料';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `partner_followup` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `partner_id` bigint unsigned NOT NULL,
  `contact_id` bigint unsigned DEFAULT NULL,
  `followup_type` varchar(32) NOT NULL DEFAULT 'VISIT',
  `method_type` varchar(32) NOT NULL DEFAULT '',
  `occurred_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `next_follow_date` date DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'OPEN',
  `completed_at` timestamp NULL DEFAULT NULL,
  `memo` varchar(500) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_followup_partner` (`partner_id`),
  KEY `idx_followup_partner_status_next` (`partner_id`,`status`,`next_follow_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户沟通记录';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `partner_number_sequence` (
  `id` tinyint unsigned NOT NULL,
  `next_value` bigint unsigned NOT NULL,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='往来单位无前缀业务编号序列';
/*!40101 SET character_set_client = @saved_cs_client */;

INSERT INTO `partner_number_sequence` (`id`, `next_value`, `updated_at`) VALUES (1,1,'2026-07-13 03:31:02');
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `partner_special_date` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `partner_id` bigint unsigned NOT NULL,
  `contact_id` bigint unsigned DEFAULT NULL,
  `date_type` varchar(32) NOT NULL DEFAULT 'OTHER',
  `title` varchar(128) NOT NULL,
  `special_date` date NOT NULL,
  `repeat_yearly` tinyint unsigned NOT NULL DEFAULT '1',
  `remind_days_before` int NOT NULL DEFAULT '7',
  `memo` varchar(500) NOT NULL DEFAULT '',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_special_date_partner` (`partner_id`),
  KEY `idx_special_date_contact` (`contact_id`),
  KEY `idx_special_date_next` (`special_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户供应商特殊日期';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `partner_supplier_profile` (
  `partner_id` bigint unsigned NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `settlement_method` varchar(32) NOT NULL DEFAULT 'PER_ORDER',
  `settlement_days` int unsigned NOT NULL DEFAULT '0',
  `lead_time_days` int unsigned NOT NULL DEFAULT '0',
  `min_order_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `tax_included` tinyint unsigned NOT NULL DEFAULT '1',
  `invoice_type` varchar(32) NOT NULL DEFAULT 'NONE',
  `tax_rate` decimal(6,2) NOT NULL DEFAULT '0.00',
  `qualification_memo` varchar(500) NOT NULL DEFAULT '',
  `disabled_reason` varchar(255) NOT NULL DEFAULT '',
  `disabled_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`partner_id`),
  KEY `idx_partner_supplier_role_status` (`status`,`partner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商角色资料';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `payable_movement` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `movement_no` varchar(64) NOT NULL,
  `supplier_id` bigint unsigned NOT NULL,
  `ref_type` varchar(32) NOT NULL,
  `ref_id` bigint unsigned NOT NULL,
  `direction` varchar(16) NOT NULL COMMENT 'INCREASE increases payable, DECREASE decreases payable',
  `amount` decimal(18,2) NOT NULL,
  `balance_after` decimal(18,2) NOT NULL DEFAULT '0.00',
  `occurred_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `memo` varchar(500) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payable_movement_no` (`movement_no`),
  KEY `idx_payable_supplier` (`supplier_id`),
  KEY `idx_payable_ref` (`ref_type`,`ref_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='应付流水';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `payment` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `payment_no` varchar(64) NOT NULL,
  `customer_id` bigint unsigned NOT NULL,
  `sales_order_id` bigint unsigned NOT NULL DEFAULT '0',
  `outbound_id` bigint unsigned DEFAULT NULL,
  `amount` decimal(18,2) NOT NULL,
  `payment_method` varchar(32) NOT NULL DEFAULT 'BANK',
  `account_type` varchar(32) NOT NULL DEFAULT '',
  `received_date` date NOT NULL,
  `verification_status` varchar(16) NOT NULL DEFAULT 'VERIFIED',
  `memo` varchar(500) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_no` (`payment_no`),
  KEY `idx_payment_customer` (`customer_id`),
  KEY `idx_payment_outbound` (`outbound_id`),
  KEY `idx_payment_sales_order` (`sales_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='回款记录';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `platform_listing` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `inventory_batch_id` bigint unsigned NOT NULL,
  `product_id` bigint unsigned NOT NULL,
  `warehouse_id` bigint unsigned NOT NULL,
  `listing_no` varchar(64) NOT NULL,
  `quantity` decimal(18,3) NOT NULL,
  `price` decimal(18,4) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'DRAFT',
  `published_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_platform_listing_no` (`listing_no`),
  KEY `idx_listing_batch` (`inventory_batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台货源';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `category_id` bigint unsigned NOT NULL DEFAULT '0',
  `product_series_id` bigint unsigned NOT NULL DEFAULT '0',
  `spec_preset_id` bigint unsigned NOT NULL DEFAULT '0',
  `spec_signature` char(64) DEFAULT NULL,
  `sku` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `grade` varchar(64) NOT NULL DEFAULT '',
  `temper` varchar(64) NOT NULL DEFAULT '',
  `standard` varchar(128) NOT NULL DEFAULT '',
  `spec` varchar(128) NOT NULL DEFAULT '',
  `attribute_json` varchar(2000) NOT NULL DEFAULT '{}',
  `barcode` varchar(64) NOT NULL DEFAULT '',
  `original_number` varchar(128) NOT NULL DEFAULT '',
  `unit` varchar(16) NOT NULL DEFAULT 'kg',
  `default_price` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `archive_ids` varchar(255) NOT NULL DEFAULT '',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_sku` (`sku`),
  UNIQUE KEY `uk_product_series_spec_signature` (`product_series_id`,`spec_signature`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品资料';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_category` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `parent_id` bigint unsigned NOT NULL DEFAULT '0',
  `name` varchar(128) NOT NULL,
  `sort_no` int NOT NULL DEFAULT '0',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_category_parent_name` (`parent_id`,`name`)
) ENGINE=InnoDB AUTO_INCREMENT=110 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='产品分类';
/*!40101 SET character_set_client = @saved_cs_client */;

INSERT INTO `product_category` (`id`, `parent_id`, `name`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (1,0,'铜管',10,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_category` (`id`, `parent_id`, `name`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (2,0,'铜板带',20,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_category` (`id`, `parent_id`, `name`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (3,0,'铜棒型材',30,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_category` (`id`, `parent_id`, `name`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (4,0,'保温材料',40,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_category` (`id`, `parent_id`, `name`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (5,0,'铜管件',50,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_category` (`id`, `parent_id`, `name`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (101,1,'制冷铜管',11,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_category` (`id`, `parent_id`, `name`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (102,4,'空调保温材料',41,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_category` (`id`, `parent_id`, `name`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (103,5,'空调分歧管',51,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_category` (`id`, `parent_id`, `name`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (104,5,'紫铜管件',52,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_category` (`id`, `parent_id`, `name`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (105,1,'覆塑铜管',12,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_category` (`id`, `parent_id`, `name`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (106,1,'特种铜管',13,'DISABLED','2026-07-08 15:19:21','2026-07-10 13:31:18');
INSERT INTO `product_category` (`id`, `parent_id`, `name`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (107,0,'铜原料',60,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_category` (`id`, `parent_id`, `name`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (108,3,'铜排母线',31,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_category` (`id`, `parent_id`, `name`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (109,0,'铜焊材',70,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_custom_field` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(128) NOT NULL,
  `field_type` varchar(32) NOT NULL DEFAULT 'TEXT',
  `value_type` varchar(32) NOT NULL DEFAULT 'TEXT',
  `unit_category_id` bigint unsigned DEFAULT NULL,
  `option_json` varchar(1000) NOT NULL DEFAULT '[]',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=230 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='自定义字段';
/*!40101 SET character_set_client = @saved_cs_client */;

INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (101,'外径','NUMBER','DECIMAL',1,'[]','ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (102,'壁厚','NUMBER','DECIMAL',1,'[]','ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (103,'长度','NUMBER','DECIMAL',1,'[]','ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (104,'品名','SELECT','TEXT',NULL,'[\"黑色管\",\"红色管\",\"蓝色管\"]','ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (105,'型号','TEXT','TEXT',NULL,'[]','ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (106,'颜色','SELECT','TEXT',NULL,'[\"黑色\",\"红色\",\"蓝色\"]','ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (201,'包装方式','TEXT','TEXT',NULL,'[]','DISABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (202,'产品编码','TEXT','TEXT',NULL,'[]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (203,'规格描述','TEXT','TEXT',NULL,'[]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (204,'品牌系列','SELECT','TEXT',NULL,'[\"大金空调系列\",\"格力空调系列\",\"美的空调系列\",\"日立空调系列\"]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (205,'管件类型','SELECT','TEXT',NULL,'[\"卡槽紫铜直接\",\"异径紫铜直接\",\"紫铜三通\",\"弯头\",\"管帽\",\"直接\",\"更多规格\"]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (206,'国标规格','TEXT','TEXT',1,'[]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (207,'英制规格','TEXT','TEXT',NULL,'[]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (208,'外尺寸','NUMBER','DECIMAL',1,'[]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (209,'单层内径','NUMBER','DECIMAL',1,'[]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (210,'单层外径','NUMBER','DECIMAL',1,'[]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (211,'双层内径','NUMBER','DECIMAL',1,'[]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (212,'双层外径','NUMBER','DECIMAL',1,'[]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (213,'内径','NUMBER','DECIMAL',1,'[]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (214,'底壁厚','NUMBER','DECIMAL',1,'[]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (215,'齿高','NUMBER','DECIMAL',1,'[]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (216,'总壁厚','NUMBER','DECIMAL',1,'[]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (217,'螺纹数','NUMBER','INTEGER',NULL,'[]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (218,'阻燃等级','SELECT','TEXT',NULL,'[\"B1级\"]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (219,'宽度','NUMBER','DECIMAL',1,'[]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (220,'厚度','NUMBER','DECIMAL',1,'[]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (221,'截面类型','SELECT','TEXT',NULL,'[\"矩形管\",\"方形管\",\"椭圆管\",\"半圆管\",\"D型管\",\"十字花管\",\"其他\"]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (222,'牌号','TEXT','TEXT',NULL,'[]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (223,'纯度','TEXT','TEXT',NULL,'[]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (224,'适配品牌','TEXT','TEXT',NULL,'[]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (225,'包装规格','TEXT','TEXT',NULL,'[]','DISABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (226,'材质','TEXT','TEXT',NULL,'[]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (227,'长度范围','TEXT','TEXT',1,'[]','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (228,'材料状态','TEXT','TEXT',NULL,'[]','ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_custom_field` (`id`, `name`, `field_type`, `value_type`, `unit_category_id`, `option_json`, `status`, `created_at`, `updated_at`) VALUES (229,'执行标准','TEXT','TEXT',NULL,'[]','ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_field_value` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `product_id` bigint unsigned NOT NULL,
  `series_field_id` bigint unsigned NOT NULL,
  `field_id` bigint unsigned NOT NULL DEFAULT '0',
  `field_name_snapshot` varchar(128) NOT NULL DEFAULT '',
  `display_name_snapshot` varchar(128) NOT NULL DEFAULT '',
  `value_text` varchar(1000) NOT NULL DEFAULT '',
  `value_decimal` decimal(30,10) DEFAULT NULL,
  `unit_id_snapshot` bigint unsigned NOT NULL DEFAULT '0',
  `unit_name_snapshot` varchar(64) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_field_value` (`product_id`,`series_field_id`),
  KEY `idx_product_field_value_series_field` (`series_field_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品规格字段实际值';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_measure_policy` (
  `product_id` bigint unsigned NOT NULL,
  `base_unit` varchar(64) NOT NULL,
  `minimum_sales_unit` varchar(64) NOT NULL,
  `minimum_outbound_unit` varchar(64) NOT NULL,
  `default_purchase_unit` varchar(64) NOT NULL,
  `default_sales_unit` varchar(64) NOT NULL,
  `rounding_policy` varchar(24) NOT NULL DEFAULT 'REJECT' COMMENT 'REJECT, ROUND_UP, ROUND_DOWN',
  `version_no` int unsigned NOT NULL DEFAULT '1',
  `effective_revision_id` bigint unsigned NOT NULL DEFAULT '0',
  `source_level` varchar(24) NOT NULL DEFAULT 'LEGACY',
  `source_template_id` bigint unsigned NOT NULL DEFAULT '0',
  `source_template_revision_id` bigint unsigned NOT NULL DEFAULT '0',
  `source_override_revision_id` bigint unsigned NOT NULL DEFAULT '0',
  `content_hash` char(64) NOT NULL DEFAULT '',
  `lock_version` int unsigned NOT NULL DEFAULT '0',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品计量与最小业务单位策略';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_measure_policy_version` (
  `product_id` bigint unsigned NOT NULL,
  `version_no` int unsigned NOT NULL,
  `effective_revision_id` bigint unsigned DEFAULT NULL,
  `base_unit` varchar(64) NOT NULL,
  `minimum_sales_unit` varchar(64) NOT NULL,
  `minimum_outbound_unit` varchar(64) NOT NULL,
  `default_purchase_unit` varchar(64) NOT NULL,
  `default_sales_unit` varchar(64) NOT NULL,
  `rounding_policy` varchar(24) NOT NULL DEFAULT 'REJECT',
  `source_level` varchar(24) NOT NULL DEFAULT 'LEGACY',
  `source_template_id` bigint unsigned NOT NULL DEFAULT '0',
  `source_template_revision_id` bigint unsigned NOT NULL DEFAULT '0',
  `source_override_revision_id` bigint unsigned NOT NULL DEFAULT '0',
  `product_series_id_snapshot` bigint unsigned NOT NULL DEFAULT '0',
  `content_hash` char(64) NOT NULL DEFAULT '',
  `profile_snapshot_json` longtext,
  `change_reason` varchar(500) NOT NULL DEFAULT '',
  `published_by` bigint unsigned NOT NULL DEFAULT '0',
  `published_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`product_id`,`version_no`),
  UNIQUE KEY `uk_product_measure_effective_revision` (`effective_revision_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_package_definition` (
  `product_id` bigint unsigned NOT NULL,
  `package_unit` varchar(64) NOT NULL,
  `barcode` varchar(128) DEFAULT NULL,
  `shelf_life_mode` varchar(16) NOT NULL DEFAULT 'NONE',
  `shelf_life_value` int unsigned DEFAULT NULL,
  `shelf_life_unit` varchar(16) NOT NULL DEFAULT '',
  `revision_id` bigint unsigned NOT NULL,
  `change_reason` varchar(500) NOT NULL DEFAULT '',
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`product_id`,`package_unit`),
  UNIQUE KEY `uk_product_package_barcode` (`barcode`),
  KEY `idx_product_package_definition_revision` (`revision_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品各包装当前条码和保质期定义';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_package_definition_revision` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `product_id` bigint unsigned NOT NULL,
  `revision_no` int unsigned NOT NULL,
  `change_reason` varchar(500) NOT NULL DEFAULT '',
  `created_by` bigint unsigned NOT NULL DEFAULT '0',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_package_definition_revision` (`product_id`,`revision_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品包装条码和保质期定义版本';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_package_definition_revision_item` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `revision_id` bigint unsigned NOT NULL,
  `product_id` bigint unsigned NOT NULL,
  `package_unit` varchar(64) NOT NULL,
  `barcode` varchar(128) NOT NULL DEFAULT '',
  `shelf_life_mode` varchar(16) NOT NULL DEFAULT 'NONE',
  `shelf_life_value` int unsigned DEFAULT NULL,
  `shelf_life_unit` varchar(16) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_package_definition_revision_item` (`revision_id`,`package_unit`),
  KEY `idx_product_package_definition_history` (`product_id`,`package_unit`,`revision_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品包装条码和保质期定义历史明细';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_package_rule` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `product_id` bigint unsigned NOT NULL,
  `version_no` int unsigned NOT NULL,
  `effective_revision_id` bigint unsigned NOT NULL DEFAULT '0',
  `rule_key` varchar(128) NOT NULL DEFAULT '',
  `parent_unit` varchar(64) NOT NULL,
  `parent_unit_id` bigint unsigned NOT NULL DEFAULT '0',
  `child_unit` varchar(64) NOT NULL,
  `child_unit_id` bigint unsigned NOT NULL DEFAULT '0',
  `conversion_mode` varchar(24) NOT NULL DEFAULT 'FIXED' COMMENT 'FIXED, ACTUAL_WEIGHT, THEORETICAL',
  `fixed_child_quantity` decimal(18,6) DEFAULT NULL,
  `reference_base_quantity` decimal(18,6) DEFAULT NULL,
  `breakable` tinyint unsigned NOT NULL DEFAULT '0',
  `repackable` tinyint unsigned NOT NULL DEFAULT '0',
  `purchase_allowed` tinyint unsigned NOT NULL DEFAULT '1',
  `sales_allowed` tinyint unsigned NOT NULL DEFAULT '1',
  `outbound_allowed` tinyint unsigned NOT NULL DEFAULT '1',
  `integer_only` tinyint unsigned NOT NULL DEFAULT '1',
  `sort_no` int NOT NULL DEFAULT '0',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `source_level` varchar(24) NOT NULL DEFAULT 'LEGACY',
  `overridden` tinyint unsigned NOT NULL DEFAULT '0',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_package_rule_version_parent` (`product_id`,`version_no`,`parent_unit`),
  KEY `idx_product_package_rule_active` (`product_id`,`version_no`,`status`),
  KEY `idx_product_package_rule_effective` (`effective_revision_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品多层包装及拆包规则版本';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_packaging_override` (
  `product_id` bigint unsigned NOT NULL,
  `current_revision_id` bigint unsigned NOT NULL DEFAULT '0',
  `current_revision_no` int unsigned NOT NULL DEFAULT '0',
  `lock_version` int unsigned NOT NULL DEFAULT '0',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='current product packaging override';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_packaging_override_revision` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `product_id` bigint unsigned NOT NULL,
  `revision_no` int unsigned NOT NULL,
  `based_on_template_revision_id` bigint unsigned NOT NULL DEFAULT '0',
  `policy_patch_json` longtext NOT NULL,
  `rule_patch_json` longtext NOT NULL,
  `content_hash` char(64) NOT NULL DEFAULT '',
  `change_reason` varchar(500) NOT NULL DEFAULT '',
  `status` varchar(16) NOT NULL DEFAULT 'PUBLISHED',
  `created_by` bigint unsigned NOT NULL DEFAULT '0',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `published_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_packaging_override_revision` (`product_id`,`revision_no`),
  KEY `idx_product_packaging_override_product` (`product_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='immutable product packaging difference revision';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_series` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `category_id` bigint unsigned NOT NULL DEFAULT '0',
  `name` varchar(128) NOT NULL,
  `alias_names` varchar(255) NOT NULL DEFAULT '',
  `package_unit_category_id` bigint unsigned NOT NULL DEFAULT '0',
  `price_unit_category_id` bigint unsigned NOT NULL DEFAULT '0',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_series_name` (`name`)
) ENGINE=InnoDB AUTO_INCREMENT=2016 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='产品系列';
/*!40101 SET character_set_client = @saved_cs_client */;

INSERT INTO `product_series` (`id`, `category_id`, `name`, `alias_names`, `package_unit_category_id`, `price_unit_category_id`, `status`, `created_at`, `updated_at`) VALUES (1,1,'铜材','紫铜,黄铜,电解铜',3,2,'ENABLED','2026-07-08 10:38:03','2026-07-08 11:31:35');
INSERT INTO `product_series` (`id`, `category_id`, `name`, `alias_names`, `package_unit_category_id`, `price_unit_category_id`, `status`, `created_at`, `updated_at`) VALUES (10,1,'铜直管','Copper Straight Tube,紫铜直管',3,2,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_series` (`id`, `category_id`, `name`, `alias_names`, `package_unit_category_id`, `price_unit_category_id`, `status`, `created_at`, `updated_at`) VALUES (11,4,'9mm分体式空调保温管','9mm保温管,空调保温管',3,3,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_series` (`id`, `category_id`, `name`, `alias_names`, `package_unit_category_id`, `price_unit_category_id`, `status`, `created_at`, `updated_at`) VALUES (12,4,'15mm工程用Class1橡塑保温管','15mm保温管,Class1橡塑保温管',3,3,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_series` (`id`, `category_id`, `name`, `alias_names`, `package_unit_category_id`, `price_unit_category_id`, `status`, `created_at`, `updated_at`) VALUES (2001,101,'紫铜大盘管','宏泰紫铜大盘管,制冷铜管,磷脱氧无缝紫铜管',3,2,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series` (`id`, `category_id`, `name`, `alias_names`, `package_unit_category_id`, `price_unit_category_id`, `status`, `created_at`, `updated_at`) VALUES (2002,101,'紫铜小盘管','宏泰小盘铜管,空调冷媒铜管,紫铜管',3,2,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series` (`id`, `category_id`, `name`, `alias_names`, `package_unit_category_id`, `price_unit_category_id`, `status`, `created_at`, `updated_at`) VALUES (2003,101,'蚊香盘铜管','蚊香盘紫铜管,空调铜管,空调连接铜管,R410A冷媒铜管',3,2,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series` (`id`, `category_id`, `name`, `alias_names`, `package_unit_category_id`, `price_unit_category_id`, `status`, `created_at`, `updated_at`) VALUES (2004,101,'紫铜直管','宏泰紫铜直管,建筑给水铜管,医疗铜管,医用脱脂无缝铜管',3,2,'DISABLED','2026-07-08 15:19:21','2026-07-10 13:31:08');
INSERT INTO `product_series` (`id`, `category_id`, `name`, `alias_names`, `package_unit_category_id`, `price_unit_category_id`, `status`, `created_at`, `updated_at`) VALUES (2005,101,'承插式直管','宏泰承插式铜直管,预胀管紫铜管,预扩口铜管,空调制冷铜管',3,2,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series` (`id`, `category_id`, `name`, `alias_names`, `package_unit_category_id`, `price_unit_category_id`, `status`, `created_at`, `updated_at`) VALUES (2006,102,'橡塑保温管','宏泰橡塑保温管,空调保温管,橡塑保温棉,橡塑保温板',3,3,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series` (`id`, `category_id`, `name`, `alias_names`, `package_unit_category_id`, `price_unit_category_id`, `status`, `created_at`, `updated_at`) VALUES (2007,103,'空调分歧管/分支器','宏泰分歧管,中央空调分支管,空调分支器',3,3,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series` (`id`, `category_id`, `name`, `alias_names`, `package_unit_category_id`, `price_unit_category_id`, `status`, `created_at`, `updated_at`) VALUES (2008,104,'紫铜管件','宏泰紫铜管件,紫铜管连接件,空调铜管管件,制冷配件',3,3,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series` (`id`, `category_id`, `name`, `alias_names`, `package_unit_category_id`, `price_unit_category_id`, `status`, `created_at`, `updated_at`) VALUES (2009,105,'覆塑铜管','覆塑铜管,铜塑复合管,塑覆紫铜管,建筑给排水饮用水管道',3,2,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series` (`id`, `category_id`, `name`, `alias_names`, `package_unit_category_id`, `price_unit_category_id`, `status`, `created_at`, `updated_at`) VALUES (2010,106,'内螺纹铜管','宏泰内螺纹紫铜管,空调制冷铜管,超导热换热器铜管',3,2,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series` (`id`, `category_id`, `name`, `alias_names`, `package_unit_category_id`, `price_unit_category_id`, `status`, `created_at`, `updated_at`) VALUES (2011,106,'毛细铜管','宏泰毛细铜管,冰箱铜管,精密仪器铜管',3,2,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series` (`id`, `category_id`, `name`, `alias_names`, `package_unit_category_id`, `price_unit_category_id`, `status`, `created_at`, `updated_at`) VALUES (2012,106,'异形铜管','异形紫铜管,矩形管,方形管,椭圆管,半圆管,D型管,十字花管',3,2,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series` (`id`, `category_id`, `name`, `alias_names`, `package_unit_category_id`, `price_unit_category_id`, `status`, `created_at`, `updated_at`) VALUES (2013,107,'电解铜','国家一级高纯电解铜,电解铜',3,2,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series` (`id`, `category_id`, `name`, `alias_names`, `package_unit_category_id`, `price_unit_category_id`, `status`, `created_at`, `updated_at`) VALUES (2014,108,'铜排','宏泰铜排,高纯度铜排,电气导电铜排',3,2,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series` (`id`, `category_id`, `name`, `alias_names`, `package_unit_category_id`, `price_unit_category_id`, `status`, `created_at`, `updated_at`) VALUES (2015,109,'铜焊条','宏泰铜焊条,银焊条,磷铜焊条,铜管焊接',3,3,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_series_field` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `product_series_id` bigint unsigned NOT NULL,
  `field_id` bigint unsigned NOT NULL,
  `field_name` varchar(128) NOT NULL,
  `display_name` varchar(128) NOT NULL DEFAULT '',
  `option_json` varchar(1000) NOT NULL DEFAULT '[]',
  `unit_category_id` bigint unsigned DEFAULT NULL,
  `unit_id` bigint unsigned DEFAULT NULL,
  `unit_name` varchar(64) NOT NULL DEFAULT '',
  `required` tinyint unsigned NOT NULL DEFAULT '0',
  `sku_dimension` tinyint unsigned NOT NULL DEFAULT '0',
  `include_in_spec` tinyint unsigned NOT NULL DEFAULT '1',
  `searchable` tinyint unsigned NOT NULL DEFAULT '0',
  `order_visible` tinyint unsigned NOT NULL DEFAULT '0',
  `min_value` decimal(30,10) DEFAULT NULL,
  `max_value` decimal(30,10) DEFAULT NULL,
  `decimal_scale` tinyint unsigned NOT NULL DEFAULT '3',
  `sort_no` int NOT NULL DEFAULT '0',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_series_field_series_field` (`product_series_id`,`field_id`),
  KEY `idx_series_field_series` (`product_series_id`)
) ENGINE=InnoDB AUTO_INCREMENT=300198 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='产品系列字段';
/*!40101 SET character_set_client = @saved_cs_client */;

INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (1001,10,101,'外径','外径','[]',1,5,'mm',1,1,1,1,1,NULL,NULL,3,10,'ENABLED','2026-07-08 11:31:35','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (1002,10,102,'壁厚','壁厚','[]',1,5,'mm',1,1,1,1,1,NULL,NULL,3,20,'ENABLED','2026-07-08 11:31:35','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (1003,10,103,'长度','长度','[]',1,5,'mm',0,0,1,0,0,NULL,NULL,3,30,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (1101,11,104,'品名','品名','[\"红色管\", \"蓝色管\", \"黑色管\"]',0,0,'',1,1,1,1,1,NULL,NULL,3,10,'ENABLED','2026-07-08 11:31:35','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (1102,11,105,'型号','型号','[]',NULL,NULL,'',1,1,1,1,1,NULL,NULL,3,20,'ENABLED','2026-07-08 11:31:35','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (1103,11,106,'颜色','颜色','[\"红色\", \"蓝色\", \"黑色\"]',NULL,NULL,'',0,0,1,0,0,NULL,NULL,3,30,'ENABLED','2026-07-08 11:31:35','2026-07-10 15:33:13');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (1201,12,104,'品名','品名','[\"红色管\", \"蓝色管\", \"黑色管\"]',NULL,NULL,'',1,1,1,1,1,NULL,NULL,3,10,'ENABLED','2026-07-08 11:31:35','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (1202,12,105,'型号','型号','[]',NULL,NULL,'',1,1,1,1,1,NULL,NULL,3,20,'ENABLED','2026-07-08 11:31:35','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (1203,12,106,'颜色','颜色','[\"红色\", \"蓝色\", \"黑色\"]',NULL,NULL,'',0,0,1,0,0,NULL,NULL,3,30,'ENABLED','2026-07-08 11:31:35','2026-07-10 15:33:13');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300001,2001,101,'外径','外径','[]',1,5,'mm',1,1,1,1,1,NULL,NULL,3,10,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300002,2001,102,'壁厚','壁厚','[]',1,5,'mm',1,1,1,1,1,NULL,NULL,3,20,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300003,2001,227,'长度范围','长度范围','[]',1,201,'m',0,0,1,0,0,NULL,NULL,3,30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300004,2001,201,'包装方式','包装方式','[]',NULL,NULL,'',0,0,0,0,0,NULL,NULL,3,40,'DISABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300011,2002,101,'外径','外径','[]',1,5,'mm',1,1,1,1,1,NULL,NULL,3,10,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300012,2002,102,'壁厚','壁厚','[]',1,5,'mm',1,1,1,1,1,NULL,NULL,3,20,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300013,2002,227,'长度范围','长度范围','[]',1,201,'m',0,0,1,0,0,NULL,NULL,3,30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300014,2002,201,'包装方式','包装方式','[]',NULL,NULL,'',0,0,0,0,0,NULL,NULL,3,40,'DISABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300021,2003,101,'外径','外径','[]',1,5,'mm',1,1,1,1,1,NULL,NULL,3,10,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300022,2003,102,'壁厚','壁厚','[]',1,5,'mm',1,1,1,1,1,NULL,NULL,3,20,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300023,2003,208,'外尺寸','外尺寸','[]',1,5,'mm',0,0,1,0,0,NULL,NULL,3,30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300024,2003,209,'单层内径','单层内径','[]',1,5,'mm',0,0,1,0,0,NULL,NULL,3,40,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300025,2003,210,'单层外径','单层外径','[]',1,5,'mm',0,0,1,0,0,NULL,NULL,3,50,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300026,2003,211,'双层内径','双层内径','[]',1,5,'mm',0,0,1,0,0,NULL,NULL,3,60,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300027,2003,212,'双层外径','双层外径','[]',1,5,'mm',0,0,1,0,0,NULL,NULL,3,70,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300028,2003,227,'长度范围','长度范围','[]',1,201,'m',0,0,1,0,0,NULL,NULL,3,80,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300041,2004,101,'外径','外径','[]',1,5,'mm',1,1,1,1,1,NULL,NULL,3,10,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300042,2004,102,'壁厚','壁厚','[]',1,5,'mm',1,1,1,1,1,NULL,NULL,3,20,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300043,2004,103,'长度','长度','[]',1,5,'mm',0,0,1,0,0,NULL,NULL,3,30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300044,2004,201,'包装方式','包装方式','[]',NULL,NULL,'',0,0,0,0,0,NULL,NULL,3,40,'DISABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300051,2005,101,'外径','外径','[]',1,5,'mm',1,1,1,1,1,NULL,NULL,3,10,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300052,2005,102,'壁厚','壁厚','[]',1,5,'mm',1,1,1,1,1,NULL,NULL,3,20,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300053,2005,103,'长度','长度','[]',1,5,'mm',0,0,1,0,0,NULL,NULL,3,30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300054,2005,201,'包装方式','包装方式','[]',NULL,NULL,'',0,0,0,0,0,NULL,NULL,3,40,'DISABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300061,2006,104,'品名','品名','[\"红色管\", \"蓝色管\", \"黑色管\"]',NULL,NULL,'',1,1,1,1,1,NULL,NULL,3,10,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300062,2006,105,'型号','型号','[]',NULL,NULL,'',1,1,1,1,1,NULL,NULL,3,20,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300063,2006,106,'颜色','颜色','[\"红色\", \"蓝色\", \"黑色\"]',NULL,NULL,'',0,0,1,0,0,NULL,NULL,3,30,'ENABLED','2026-07-08 15:19:21','2026-07-10 15:33:13');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300064,2006,218,'阻燃等级','阻燃等级','[\"B1级\"]',NULL,NULL,'',0,0,1,0,0,NULL,NULL,3,40,'ENABLED','2026-07-08 15:19:21','2026-07-10 15:33:13');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300065,2006,201,'包装方式','包装方式','[]',NULL,NULL,'',0,0,0,0,0,NULL,NULL,3,50,'DISABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300071,2007,204,'品牌系列','品牌系列','[\"大金空调系列\", \"日立空调系列\", \"格力空调系列\", \"美的空调系列\"]',NULL,NULL,'',1,1,1,1,1,NULL,NULL,3,10,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300072,2007,105,'型号','型号','[]',NULL,NULL,'',1,1,1,1,1,NULL,NULL,3,20,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300073,2007,224,'适配品牌','适配品牌','[]',NULL,NULL,'',0,0,1,0,0,NULL,NULL,3,30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300074,2007,201,'包装方式','包装方式','[]',NULL,NULL,'',0,0,0,0,0,NULL,NULL,3,40,'DISABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300081,2008,205,'管件类型','管件类型','[\"卡槽紫铜直接\", \"异径紫铜直接\", \"紫铜三通\"]',NULL,NULL,'',1,1,1,1,1,NULL,NULL,3,10,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300082,2008,206,'国标规格','国标规格','[]',1,5,'mm',0,0,1,0,0,NULL,NULL,3,20,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300083,2008,207,'英制规格','英制规格','[]',NULL,NULL,'',0,0,1,0,0,NULL,NULL,3,30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300084,2008,226,'材质','材质','[]',NULL,NULL,'',0,0,1,0,0,NULL,NULL,3,40,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300091,2009,202,'产品编码','产品编码','[]',NULL,NULL,'',1,1,1,1,1,NULL,NULL,3,10,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300092,2009,203,'规格描述','规格描述','[]',NULL,NULL,'',1,1,1,1,1,NULL,NULL,3,20,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300093,2009,101,'外径','外径','[]',1,5,'mm',0,0,1,0,0,NULL,NULL,3,30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300094,2009,103,'长度','长度','[]',1,201,'m',0,0,1,0,0,NULL,NULL,3,40,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300101,2010,105,'型号','型号','[]',NULL,NULL,'',1,1,1,1,1,NULL,NULL,3,10,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300102,2010,101,'外径','外径','[]',1,5,'mm',1,1,1,1,1,NULL,NULL,3,20,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300103,2010,213,'内径','内径','[]',1,5,'mm',0,0,1,0,0,NULL,NULL,3,30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300104,2010,214,'底壁厚','底壁厚','[]',1,5,'mm',0,0,1,0,0,NULL,NULL,3,40,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300105,2010,215,'齿高','齿高','[]',1,5,'mm',0,0,1,0,0,NULL,NULL,3,50,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300106,2010,216,'总壁厚','总壁厚','[]',1,5,'mm',0,0,1,0,0,NULL,NULL,3,60,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300107,2010,217,'螺纹数','螺纹数','[]',NULL,NULL,'',0,0,1,0,0,NULL,NULL,0,70,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300111,2011,202,'产品编码','产品编码','[]',NULL,NULL,'',1,1,1,1,1,NULL,NULL,3,10,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300112,2011,203,'规格描述','规格描述','[]',NULL,NULL,'',1,1,1,1,1,NULL,NULL,3,20,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300113,2011,101,'外径','外径','[]',1,5,'mm',0,0,1,0,0,NULL,NULL,3,30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300114,2011,213,'内径','内径','[]',1,5,'mm',0,0,1,0,0,NULL,NULL,3,40,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300121,2012,221,'截面类型','截面类型','[\"矩形管、方形管、椭圆管、半圆管、D型管、十字花管\"]',NULL,NULL,'',1,1,1,1,1,NULL,NULL,3,10,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300122,2012,226,'材质','材质','[]',NULL,NULL,'',0,0,1,0,0,NULL,NULL,3,20,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300123,2012,203,'规格描述','规格描述','[]',NULL,NULL,'',0,0,1,0,0,NULL,NULL,3,30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300131,2013,222,'牌号','牌号','[]',NULL,NULL,'',1,1,1,1,1,NULL,NULL,3,10,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300132,2013,223,'纯度','纯度','[]',NULL,NULL,'',0,0,1,0,0,NULL,NULL,3,20,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300133,2013,203,'规格描述','规格描述','[]',NULL,NULL,'',0,0,1,0,0,NULL,NULL,3,30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300141,2014,202,'产品编码','产品编码','[]',NULL,NULL,'',1,1,1,1,1,NULL,NULL,3,10,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300142,2014,219,'宽度','宽度','[]',1,5,'mm',1,1,1,1,1,NULL,NULL,3,20,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300143,2014,220,'厚度','厚度','[]',1,5,'mm',1,1,1,1,1,NULL,NULL,3,30,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300144,2014,203,'规格描述','规格描述','[]',NULL,NULL,'',0,0,1,0,0,NULL,NULL,3,40,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300151,2015,104,'品名','品名','[\"圆焊条\",\"扁焊条\"]',NULL,NULL,'',1,1,1,1,1,NULL,NULL,3,10,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300152,2015,105,'型号','型号','[]',NULL,NULL,'',1,1,1,1,1,NULL,NULL,3,20,'ENABLED','2026-07-08 15:19:21','2026-07-10 17:17:30');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300153,2015,203,'规格描述','规格描述','[]',NULL,NULL,'',0,0,1,0,0,NULL,NULL,3,30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300154,1,222,'牌号/材质','牌号/材质','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,10,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300155,2001,222,'牌号/材质','牌号/材质','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,50,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300156,2002,222,'牌号/材质','牌号/材质','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,50,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300157,2003,222,'牌号/材质','牌号/材质','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,90,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300158,2004,222,'牌号/材质','牌号/材质','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,50,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300159,2005,222,'牌号/材质','牌号/材质','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,50,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300160,2006,222,'牌号/材质','牌号/材质','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,60,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300161,2007,222,'牌号/材质','牌号/材质','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,50,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300162,2008,222,'牌号/材质','牌号/材质','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,50,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300163,2009,222,'牌号/材质','牌号/材质','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,50,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300164,2010,222,'牌号/材质','牌号/材质','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,80,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300165,2011,222,'牌号/材质','牌号/材质','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,50,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300166,2012,222,'牌号/材质','牌号/材质','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,40,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300167,2014,222,'牌号/材质','牌号/材质','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,50,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300168,2015,222,'牌号/材质','牌号/材质','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,40,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300169,2001,228,'材料状态','材料状态','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,60,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300170,2002,228,'材料状态','材料状态','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,60,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300171,2003,228,'材料状态','材料状态','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,100,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300172,2004,228,'材料状态','材料状态','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,60,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300173,2005,228,'材料状态','材料状态','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,60,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300174,2007,228,'材料状态','材料状态','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,60,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300175,2008,228,'材料状态','材料状态','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,60,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300176,2009,228,'材料状态','材料状态','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,60,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300177,2010,228,'材料状态','材料状态','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,90,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300178,2011,228,'材料状态','材料状态','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,60,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300179,2012,228,'材料状态','材料状态','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,50,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300180,2014,228,'材料状态','材料状态','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,60,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300184,11,229,'执行标准','执行标准','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,40,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300185,2001,229,'执行标准','执行标准','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,70,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300186,2002,229,'执行标准','执行标准','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,70,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300187,2003,229,'执行标准','执行标准','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,110,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300188,2004,229,'执行标准','执行标准','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,70,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300189,2005,229,'执行标准','执行标准','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,70,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300190,2006,229,'执行标准','执行标准','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,70,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300191,2008,229,'执行标准','执行标准','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,70,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300192,2009,229,'执行标准','执行标准','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,70,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300193,2010,229,'执行标准','执行标准','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,100,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300194,2011,229,'执行标准','执行标准','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,70,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300195,2012,229,'执行标准','执行标准','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,60,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300196,2014,229,'执行标准','执行标准','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,70,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
INSERT INTO `product_series_field` (`id`, `product_series_id`, `field_id`, `field_name`, `display_name`, `option_json`, `unit_category_id`, `unit_id`, `unit_name`, `required`, `sku_dimension`, `include_in_spec`, `searchable`, `order_visible`, `min_value`, `max_value`, `decimal_scale`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (300197,2015,229,'执行标准','执行标准','[]',NULL,NULL,'',0,0,1,1,1,NULL,NULL,0,50,'ENABLED','2026-07-12 12:29:57','2026-07-12 12:29:57');
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_series_package_unit` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `product_series_id` bigint unsigned NOT NULL,
  `name` varchar(64) NOT NULL DEFAULT '',
  `unit_id` bigint unsigned DEFAULT NULL,
  `unit_name` varchar(64) NOT NULL DEFAULT '',
  `exchange_rate` decimal(16,4) DEFAULT NULL,
  `primary_unit` tinyint unsigned NOT NULL DEFAULT '0',
  `volume` decimal(18,4) DEFAULT NULL,
  `gross_weight` decimal(18,4) DEFAULT NULL,
  `net_weight` decimal(18,4) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_series_package_unit` (`product_series_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='产品包装单位';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_series_packaging_binding` (
  `product_series_id` bigint unsigned NOT NULL,
  `template_id` bigint unsigned NOT NULL,
  `lock_version` int unsigned NOT NULL DEFAULT '0',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `change_reason` varchar(500) NOT NULL DEFAULT '',
  `created_by` bigint unsigned NOT NULL DEFAULT '0',
  `updated_by` bigint unsigned NOT NULL DEFAULT '0',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`product_series_id`),
  KEY `idx_series_packaging_binding_template` (`template_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='series packaging template binding';
/*!40101 SET character_set_client = @saved_cs_client */;

INSERT INTO `product_series_packaging_binding` (`product_series_id`, `template_id`, `lock_version`, `status`, `change_reason`, `created_by`, `updated_by`, `created_at`, `updated_at`) VALUES (1,1,1,'ENABLED','系统迁移：系列沿用原商品一致计量配置',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `product_series_packaging_binding` (`product_series_id`, `template_id`, `lock_version`, `status`, `change_reason`, `created_by`, `updated_by`, `created_at`, `updated_at`) VALUES (11,2,1,'ENABLED','系统迁移：系列沿用原商品一致计量配置',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `product_series_packaging_binding` (`product_series_id`, `template_id`, `lock_version`, `status`, `change_reason`, `created_by`, `updated_by`, `created_at`, `updated_at`) VALUES (2001,1,1,'ENABLED','系统迁移：系列沿用原商品一致计量配置',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `product_series_packaging_binding` (`product_series_id`, `template_id`, `lock_version`, `status`, `change_reason`, `created_by`, `updated_by`, `created_at`, `updated_at`) VALUES (2002,1,1,'ENABLED','系统迁移：系列沿用原商品一致计量配置',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `product_series_packaging_binding` (`product_series_id`, `template_id`, `lock_version`, `status`, `change_reason`, `created_by`, `updated_by`, `created_at`, `updated_at`) VALUES (2003,1,1,'ENABLED','系统迁移：系列沿用原商品一致计量配置',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `product_series_packaging_binding` (`product_series_id`, `template_id`, `lock_version`, `status`, `change_reason`, `created_by`, `updated_by`, `created_at`, `updated_at`) VALUES (2004,1,1,'ENABLED','系统迁移：系列沿用原商品一致计量配置',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `product_series_packaging_binding` (`product_series_id`, `template_id`, `lock_version`, `status`, `change_reason`, `created_by`, `updated_by`, `created_at`, `updated_at`) VALUES (2005,1,1,'ENABLED','系统迁移：系列沿用原商品一致计量配置',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `product_series_packaging_binding` (`product_series_id`, `template_id`, `lock_version`, `status`, `change_reason`, `created_by`, `updated_by`, `created_at`, `updated_at`) VALUES (2006,2,1,'ENABLED','系统迁移：系列沿用原商品一致计量配置',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `product_series_packaging_binding` (`product_series_id`, `template_id`, `lock_version`, `status`, `change_reason`, `created_by`, `updated_by`, `created_at`, `updated_at`) VALUES (2007,3,1,'ENABLED','系统迁移：系列沿用原商品一致计量配置',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `product_series_packaging_binding` (`product_series_id`, `template_id`, `lock_version`, `status`, `change_reason`, `created_by`, `updated_by`, `created_at`, `updated_at`) VALUES (2008,3,1,'ENABLED','系统迁移：系列沿用原商品一致计量配置',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `product_series_packaging_binding` (`product_series_id`, `template_id`, `lock_version`, `status`, `change_reason`, `created_by`, `updated_by`, `created_at`, `updated_at`) VALUES (2009,1,1,'ENABLED','系统迁移：系列沿用原商品一致计量配置',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `product_series_packaging_binding` (`product_series_id`, `template_id`, `lock_version`, `status`, `change_reason`, `created_by`, `updated_by`, `created_at`, `updated_at`) VALUES (2010,1,1,'ENABLED','系统迁移：系列沿用原商品一致计量配置',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `product_series_packaging_binding` (`product_series_id`, `template_id`, `lock_version`, `status`, `change_reason`, `created_by`, `updated_by`, `created_at`, `updated_at`) VALUES (2011,1,1,'ENABLED','系统迁移：系列沿用原商品一致计量配置',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `product_series_packaging_binding` (`product_series_id`, `template_id`, `lock_version`, `status`, `change_reason`, `created_by`, `updated_by`, `created_at`, `updated_at`) VALUES (2012,1,1,'ENABLED','系统迁移：系列沿用原商品一致计量配置',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `product_series_packaging_binding` (`product_series_id`, `template_id`, `lock_version`, `status`, `change_reason`, `created_by`, `updated_by`, `created_at`, `updated_at`) VALUES (2013,1,1,'ENABLED','系统迁移：系列沿用原商品一致计量配置',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `product_series_packaging_binding` (`product_series_id`, `template_id`, `lock_version`, `status`, `change_reason`, `created_by`, `updated_by`, `created_at`, `updated_at`) VALUES (2014,1,1,'ENABLED','系统迁移：系列沿用原商品一致计量配置',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
INSERT INTO `product_series_packaging_binding` (`product_series_id`, `template_id`, `lock_version`, `status`, `change_reason`, `created_by`, `updated_by`, `created_at`, `updated_at`) VALUES (2015,4,1,'ENABLED','系统迁移：系列沿用原商品一致计量配置',0,0,'2026-07-11 00:23:06','2026-07-11 00:23:06');
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_series_price_unit` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `product_series_id` bigint unsigned NOT NULL,
  `name` varchar(64) NOT NULL DEFAULT '',
  `unit_id` bigint unsigned DEFAULT NULL,
  `unit_name` varchar(64) NOT NULL DEFAULT '',
  `exchange_rate` decimal(16,4) DEFAULT NULL,
  `primary_unit` tinyint unsigned NOT NULL DEFAULT '0',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_series_price_unit` (`product_series_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='产品计价单位';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_spec_preset` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `product_series_id` bigint unsigned NOT NULL,
  `name` varchar(128) NOT NULL DEFAULT '',
  `model` varchar(128) NOT NULL DEFAULT '',
  `attribute_json` varchar(2000) NOT NULL DEFAULT '{}',
  `spec_text` varchar(255) NOT NULL DEFAULT '',
  `sku_suffix` varchar(128) NOT NULL DEFAULT '',
  `sort_no` int NOT NULL DEFAULT '0',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_spec_preset_series_model` (`product_series_id`,`name`,`model`),
  KEY `idx_spec_preset_series` (`product_series_id`)
) ENGINE=InnoDB AUTO_INCREMENT=305 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系列规格预设';
/*!40101 SET character_set_client = @saved_cs_client */;

INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (1,11,'黑色管','6*9*1.8*180','{\"品名\":\"黑色管\",\"型号\":\"6*9*1.8*180\",\"颜色\":\"黑色\"}','黑色管 6*9*1.8*180','BLK-6-9-180',10,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (2,11,'黑色管','10*9*1.8*140','{\"品名\":\"黑色管\",\"型号\":\"10*9*1.8*140\",\"颜色\":\"黑色\"}','黑色管 10*9*1.8*140','BLK-10-9-140',20,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (3,11,'黑色管','13*9*1.8*120','{\"品名\":\"黑色管\",\"型号\":\"13*9*1.8*120\",\"颜色\":\"黑色\"}','黑色管 13*9*1.8*120','BLK-13-9-120',30,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (4,11,'黑色管','16*9*1.8*80','{\"品名\":\"黑色管\",\"型号\":\"16*9*1.8*80\",\"颜色\":\"黑色\"}','黑色管 16*9*1.8*80','BLK-16-9-80',40,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (5,11,'黑色管','19*9*1.8*70','{\"品名\":\"黑色管\",\"型号\":\"19*9*1.8*70\",\"颜色\":\"黑色\"}','黑色管 19*9*1.8*70','BLK-19-9-70',50,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (6,11,'黑色管','22*9*1.8*70','{\"品名\":\"黑色管\",\"型号\":\"22*9*1.8*70\",\"颜色\":\"黑色\"}','黑色管 22*9*1.8*70','BLK-22-9-70',60,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (7,11,'黑色管','25*9*1.8*60','{\"品名\":\"黑色管\",\"型号\":\"25*9*1.8*60\",\"颜色\":\"黑色\"}','黑色管 25*9*1.8*60','BLK-25-9-60',70,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (8,11,'黑色管','32*9*1.8*50','{\"品名\":\"黑色管\",\"型号\":\"32*9*1.8*50\",\"颜色\":\"黑色\"}','黑色管 32*9*1.8*50','BLK-32-9-50',80,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (9,11,'黑色管','38*9*1.8*50','{\"品名\":\"黑色管\",\"型号\":\"38*9*1.8*50\",\"颜色\":\"黑色\"}','黑色管 38*9*1.8*50','BLK-38-9-50',90,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (10,11,'黑色管','48*9*1.8*30','{\"品名\":\"黑色管\",\"型号\":\"48*9*1.8*30\",\"颜色\":\"黑色\"}','黑色管 48*9*1.8*30','BLK-48-9-30',100,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (11,11,'红色管','25*9*1.8*60','{\"品名\":\"红色管\",\"型号\":\"25*9*1.8*60\",\"颜色\":\"红色\"}','红色管 25*9*1.8*60','RED-25-9-60',110,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (12,11,'红色管','32*9*1.8*50','{\"品名\":\"红色管\",\"型号\":\"32*9*1.8*50\",\"颜色\":\"红色\"}','红色管 32*9*1.8*50','RED-32-9-50',120,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (13,11,'蓝色管','25*9*1.8*60','{\"品名\":\"蓝色管\",\"型号\":\"25*9*1.8*60\",\"颜色\":\"蓝色\"}','蓝色管 25*9*1.8*60','BLU-25-9-60',130,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (14,11,'蓝色管','32*9*1.8*50','{\"品名\":\"蓝色管\",\"型号\":\"32*9*1.8*50\",\"颜色\":\"蓝色\"}','蓝色管 32*9*1.8*50','BLU-32-9-50',140,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (15,12,'黑色管','6*15*2*80','{\"品名\":\"黑色管\",\"型号\":\"6*15*2*80\",\"颜色\":\"黑色\"}','黑色管 6*15*2*80','BLK-6-15-80',10,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (16,12,'黑色管','10*15*2*80','{\"品名\":\"黑色管\",\"型号\":\"10*15*2*80\",\"颜色\":\"黑色\"}','黑色管 10*15*2*80','BLK-10-15-80',20,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (17,12,'黑色管','13*15*2*80','{\"品名\":\"黑色管\",\"型号\":\"13*15*2*80\",\"颜色\":\"黑色\"}','黑色管 13*15*2*80','BLK-13-15-80',30,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (18,12,'黑色管','16*15*2*70','{\"品名\":\"黑色管\",\"型号\":\"16*15*2*70\",\"颜色\":\"黑色\"}','黑色管 16*15*2*70','BLK-16-15-70',40,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (19,12,'黑色管','20*15*2*70','{\"品名\":\"黑色管\",\"型号\":\"20*15*2*70\",\"颜色\":\"黑色\"}','黑色管 20*15*2*70','BLK-20-15-70',50,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (20,12,'黑色管','22*15*2*60','{\"品名\":\"黑色管\",\"型号\":\"22*15*2*60\",\"颜色\":\"黑色\"}','黑色管 22*15*2*60','BLK-22-15-60',60,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (21,12,'黑色管','28*15*2*50','{\"品名\":\"黑色管\",\"型号\":\"28*15*2*50\",\"颜色\":\"黑色\"}','黑色管 28*15*2*50','BLK-28-15-50',70,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (22,12,'黑色管','32*15*2*30','{\"品名\":\"黑色管\",\"型号\":\"32*15*2*30\",\"颜色\":\"黑色\"}','黑色管 32*15*2*30','BLK-32-15-30',80,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (23,12,'红色管','6*15*2*80','{\"品名\":\"红色管\",\"型号\":\"6*15*2*80\",\"颜色\":\"红色\"}','红色管 6*15*2*80','RED-6-15-80',90,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (24,12,'红色管','10*15*2*80','{\"品名\":\"红色管\",\"型号\":\"10*15*2*80\",\"颜色\":\"红色\"}','红色管 10*15*2*80','RED-10-15-80',100,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (25,12,'红色管','13*15*2*80','{\"品名\":\"红色管\",\"型号\":\"13*15*2*80\",\"颜色\":\"红色\"}','红色管 13*15*2*80','RED-13-15-80',110,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (26,12,'红色管','16*15*2*70','{\"品名\":\"红色管\",\"型号\":\"16*15*2*70\",\"颜色\":\"红色\"}','红色管 16*15*2*70','RED-16-15-70',120,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (27,12,'红色管','20*15*2*70','{\"品名\":\"红色管\",\"型号\":\"20*15*2*70\",\"颜色\":\"红色\"}','红色管 20*15*2*70','RED-20-15-70',130,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (28,12,'蓝色管','6*15*2*80','{\"品名\":\"蓝色管\",\"型号\":\"6*15*2*80\",\"颜色\":\"蓝色\"}','蓝色管 6*15*2*80','BLU-6-15-80',140,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (29,12,'蓝色管','10*15*2*80','{\"品名\":\"蓝色管\",\"型号\":\"10*15*2*80\",\"颜色\":\"蓝色\"}','蓝色管 10*15*2*80','BLU-10-15-80',150,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (30,12,'蓝色管','13*15*2*80','{\"品名\":\"蓝色管\",\"型号\":\"13*15*2*80\",\"颜色\":\"蓝色\"}','蓝色管 13*15*2*80','BLU-13-15-80',160,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (31,12,'蓝色管','16*15*2*70','{\"品名\":\"蓝色管\",\"型号\":\"16*15*2*70\",\"颜色\":\"蓝色\"}','蓝色管 16*15*2*70','BLU-16-15-70',170,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (32,12,'蓝色管','20*15*2*70','{\"品名\":\"蓝色管\",\"型号\":\"20*15*2*70\",\"颜色\":\"蓝色\"}','蓝色管 20*15*2*70','BLU-20-15-70',180,'ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (33,2001,'官网常规','按官网规格表/可定制','{\"来源品牌\":\"宏泰铜业\",\"来源分类\":\"制冷系列\",\"来源链接\":\"https://www.hongtai-copper.com/copper-pipe.html\",\"用途\":\"制冷、空调连接管、供热、换热风机管、油管、水管道、气管、热交换器等\",\"状态\":\"软态\",\"标准\":\"GB/T17791；GB/T1527；GB/T 18033-2017；YS/T 450；ASTM B280；ASTM B68；ASTM B75；EN12735；AS1571；JISH3300；EN1057\",\"包装\":\"大木盘&小木盘，以塑料袋外覆封装\"}','官网常规规格/大木盘或小木盘','HT-2001-BASE',10,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (34,2002,'官网常规','按官网规格表/可定制','{\"来源品牌\":\"宏泰铜业\",\"来源分类\":\"制冷系列\",\"来源链接\":\"https://www.hongtai-copper.com/copper-tube.html\",\"用途\":\"空调制冷保温管、冷媒输送管道、换热风机管、水道管、气管、热水器、太阳能\",\"状态\":\"软态\",\"标准\":\"GB/T 17791；GB/T 1527；GB/T 18033-2017；YS/T 450；ASTM B280；ASTM B68；ASTM B75；EN 12735；AS 1571；JIS H3300；EN 1057\",\"包装\":\"小盘管包装\"}','官网常规规格/小盘管包装','HT-2002-BASE',20,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (35,2003,'官网常规','按官网规格表/可定制','{\"来源品牌\":\"宏泰铜业\",\"来源分类\":\"制冷系列\",\"来源链接\":\"https://www.hongtai-copper.com/pancake-coil.html\",\"用途\":\"空调制冷、水管、气管、空调安装及维修用铜管、冷媒输送铜管\",\"状态\":\"软态\",\"标准\":\"GB/T 17791；GB/T 1527；GB/T 18033-2017；YS/T 450；ASTM B280；ASTM B68；ASTM B75；ASTM B88；EN 12735；AS 1571；JIS H3300；EN 1057\",\"包装\":\"分盘封装，置入纸箱\"}','官网常规规格/分盘封装','HT-2003-BASE',30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (36,2004,'官网常规','按官网规格表/可定制','{\"来源品牌\":\"宏泰铜业\",\"来源分类\":\"制冷系列\",\"来源链接\":\"https://www.hongtai-copper.com/copper-straight-tube.html\",\"用途\":\"医疗气体、水管、气管、暖通、制冷、中央空调连接、导电等管道管材\",\"状态\":\"硬态、半硬态、软态\",\"标准\":\"GB/T 17791-2017；GB/T 1527-2017；GB/T 18033-2017；YS/T 450-2013；YS/T 650-2020\",\"包装\":\"套袋封装，两端加堵头，木架打捆\"}','官网常规规格/套袋封装','HT-2004-BASE',40,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (37,2005,'官网常规','按官网规格表/可定制','{\"来源品牌\":\"宏泰铜业\",\"来源分类\":\"制冷系列\",\"来源链接\":\"https://www.hongtai-copper.com/cheng-cha-shi-zhi-guan.html\",\"用途\":\"预制铜管胀管/铜管扩口，方便空调制冷、医用气体、给水等领域铜管连接工程施工\",\"状态\":\"硬态、半硬态、软态\",\"标准\":\"GB/T 17791-2017；GB/T 1527-2017；GB/T 18033-2017；YS/T 450-2013；YS/T 650-2020；ASTM B 819\",\"包装\":\"套袋封装，两端加堵头，木架打捆\"}','官网常规规格/预胀管预扩口','HT-2005-BASE',50,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (38,2006,'官网常规','按官网规格表/可定制','{\"来源品牌\":\"宏泰铜业\",\"来源分类\":\"制冷系列\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\",\"用途\":\"用于空调保温、冷热水管等管道保温\",\"标准\":\"GB/T 17794-2021（B1级）；GB 8624-2012（B1级）\",\"包装\":\"密封包装，封装后装入纸箱\"}','官网规格表/密封包装','HT-2006-BASE',60,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (39,2007,'官网常规','按官网规格表/可定制','{\"来源品牌\":\"宏泰铜业\",\"来源分类\":\"制冷系列\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\",\"用途\":\"用于中央空调、VRV多联空调系统，连接主机和多个末端设备的连接管\",\"状态\":\"硬态\",\"适配\":\"大金、格力、美的、日立等中央空调品牌\",\"包装\":\"纸盒、内包装+纸盒、纸箱\"}','官网适配型号/纸盒纸箱包装','HT-2007-BASE',70,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (40,2008,'官网常规','按官网规格表/可定制','{\"来源品牌\":\"宏泰铜业\",\"来源分类\":\"制冷系列\",\"来源链接\":\"https://www.hongtai-copper.com/copper-fitting.html\",\"用途\":\"紫铜管连接件；空调制冷管件；空调铜管配件\",\"状态\":\"硬态\",\"标准\":\"BS864；ANSI B16.22\",\"包装\":\"塑料袋和纸箱包装\"}','国标/英制规格/支持定制','HT-2008-BASE',80,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (41,2009,'官网常规','按官网规格表/可定制','{\"来源品牌\":\"宏泰铜业\",\"来源分类\":\"制冷系列\",\"来源链接\":\"https://www.hongtai-copper.com/137.html\",\"用途\":\"用于饮用冷热水管道、建筑给排水管道、燃气管道等\",\"状态\":\"软态、硬态\",\"标准\":\"YS/T 451-2002；EN 1057；ASTM B88\"}','官网产品编码规格','HT-2009-BASE',90,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (42,2010,'官网常规','按官网规格表/可定制','{\"来源品牌\":\"宏泰铜业\",\"来源分类\":\"制冷系列\",\"来源链接\":\"https://www.hongtai-copper.com/104.html\",\"用途\":\"超高导热性能，广泛应用于空调制冷行业\",\"状态\":\"软态\",\"标准\":\"GB/T 20928；EN 12735\",\"包装\":\"大盘包装，每盘约120KG\"}','官网内螺纹规格/大盘包装','HT-2010-BASE',100,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (43,2011,'官网常规','按官网规格表/可定制','{\"来源品牌\":\"宏泰铜业\",\"来源分类\":\"制冷系列\",\"来源链接\":\"https://www.hongtai-copper.com/138.html\",\"用途\":\"适用于冰箱、冰柜、空调等制冷行业，常用作制冷剂输送管道；以及精密仪器、仪表、电子行业\",\"状态\":\"硬态、软态\",\"标准\":\"GB/T 1531-2020；ASTM B360\",\"包装\":\"包装\"}','官网产品编码规格','HT-2011-BASE',110,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (44,2012,'官网常规','按官网规格表/可定制','{\"来源品牌\":\"宏泰铜业\",\"来源分类\":\"制冷系列\",\"来源链接\":\"https://www.hongtai-copper.com/141.html\",\"用途\":\"电子、电炉、电机及水暖工业用管\",\"状态\":\"硬态、软态\",\"标准\":\"GB/T 1527-2006\",\"规格\":\"矩形管、方形管、椭圆管、半圆管、D型管、十字花管等\"}','矩形/方形/椭圆/半圆/D型/十字花等','HT-2012-BASE',120,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (45,2013,'官网常规','按官网规格表/可定制','{\"来源品牌\":\"宏泰铜业\",\"来源分类\":\"制冷系列\",\"来源链接\":\"https://www.hongtai-copper.com/136.html\",\"用途\":\"主要应用于化工、电镀、铸造等领域\",\"说明\":\"国家一级高纯电解铜，具备权威机构检测报告\"}','官网常规规格','HT-2013-BASE',130,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (46,2014,'官网常规','按官网规格表/可定制','{\"来源品牌\":\"宏泰铜业\",\"来源分类\":\"制冷系列\",\"来源链接\":\"https://www.hongtai-copper.com/140.html\",\"用途\":\"电气和导电\",\"状态\":\"硬态，软态和半硬态\",\"标准\":\"GB 5585-85\",\"技术数据\":\"C11000，CU≥99.90\"}','官网产品编码规格','HT-2014-BASE',140,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (47,2015,'官网常规','按官网规格表/可定制','{\"来源品牌\":\"宏泰铜业\",\"来源分类\":\"制冷系列\",\"来源链接\":\"https://www.hongtai-copper.com/139.html\",\"用途\":\"主要应用于制冷、暖通、医疗输氧等管道的焊接工程\",\"标准\":\"GB/T 6418-2008\",\"规格\":\"圆焊条、扁焊条\"}','圆焊条/扁焊条','HT-2015-BASE',150,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (48,2003,'外形尺寸','3.18','{\"类型\":\"3.18\",\"外尺寸\":\"150\",\"单层内径\":\"150\",\"单层外径\":\"350\",\"双层内径\":\"150\",\"双层外径\":\"300\",\"长度\":\"9000-30000mm\",\"来源链接\":\"https://www.hongtai-copper.com/pancake-coil.html\"}','3.18mm 外形尺寸，长度9000-30000mm','OD318-DIM',10,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (49,2003,'外形尺寸','4.76','{\"类型\":\"4.76\",\"外尺寸\":\"200\",\"单层内径\":\"200\",\"单层外径\":\"400\",\"双层内径\":\"200\",\"双层外径\":\"400\",\"长度\":\"9000-30000mm\",\"来源链接\":\"https://www.hongtai-copper.com/pancake-coil.html\"}','4.76mm 外形尺寸，长度9000-30000mm','OD476-DIM',20,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (50,2003,'外形尺寸','6.35','{\"类型\":\"6.35\",\"外尺寸\":\"250\",\"单层内径\":\"250\",\"单层外径\":\"450\",\"双层内径\":\"300\",\"双层外径\":\"450\",\"长度\":\"9000-30000mm\",\"来源链接\":\"https://www.hongtai-copper.com/pancake-coil.html\"}','6.35mm 外形尺寸，长度9000-30000mm','OD635-DIM',30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (51,2003,'外形尺寸','7.94','{\"类型\":\"7.94\",\"外尺寸\":\"250\",\"单层内径\":\"250\",\"单层外径\":\"500\",\"双层内径\":\"300\",\"双层外径\":\"500\",\"长度\":\"9000-30000mm\",\"来源链接\":\"https://www.hongtai-copper.com/pancake-coil.html\"}','7.94mm 外形尺寸，长度9000-30000mm','OD794-DIM',40,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (52,2003,'外形尺寸','9.52','{\"类型\":\"9.52\",\"外尺寸\":\"300\",\"单层内径\":\"300\",\"单层外径\":\"500\",\"双层内径\":\"300\",\"双层外径\":\"500\",\"长度\":\"9000-30000mm\",\"来源链接\":\"https://www.hongtai-copper.com/pancake-coil.html\"}','9.52mm 外形尺寸，长度9000-30000mm','OD952-DIM',50,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (53,2003,'外形尺寸','12.7','{\"类型\":\"12.7\",\"外尺寸\":\"300\",\"单层内径\":\"300\",\"单层外径\":\"590\",\"双层内径\":\"300\",\"双层外径\":\"580\",\"长度\":\"9000-18000mm\",\"来源链接\":\"https://www.hongtai-copper.com/pancake-coil.html\"}','12.7mm 外形尺寸，长度9000-18000mm','OD127-DIM',60,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (54,2003,'外形尺寸','15.88','{\"类型\":\"15.88\",\"外尺寸\":\"350\",\"单层内径\":\"350\",\"单层外径\":\"650\",\"双层内径\":\"350\",\"双层外径\":\"580\",\"长度\":\"9000-18000mm\",\"来源链接\":\"https://www.hongtai-copper.com/pancake-coil.html\"}','15.88mm 外形尺寸，长度9000-18000mm','OD1588-DIM',70,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (55,2003,'外形尺寸','19.05','{\"类型\":\"19.05\",\"外尺寸\":\"350\",\"单层内径\":\"350\",\"单层外径\":\"700\",\"双层内径\":\"350\",\"双层外径\":\"600\",\"长度\":\"9000-18000mm\",\"来源链接\":\"https://www.hongtai-copper.com/pancake-coil.html\"}','19.05mm 外形尺寸，长度9000-18000mm','OD1905-DIM',80,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (56,2003,'外形尺寸','22.2','{\"类型\":\"22.2\",\"外尺寸\":\"350\",\"单层内径\":\"350\",\"单层外径\":\"750\",\"双层内径\":\"350\",\"双层外径\":\"630\",\"长度\":\"9000-18000mm\",\"来源链接\":\"https://www.hongtai-copper.com/pancake-coil.html\"}','22.2mm 外形尺寸，长度9000-18000mm','OD222-DIM',90,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (57,2003,'OD/Thickness','4.76*0.76','{\"外径\":\"4.76mm\",\"壁厚\":\"0.76mm\",\"长度\":\"15M或定制\",\"来源链接\":\"https://www.hongtai-copper.com/pancake-coil.html\"}','OD 4.76mm 壁厚0.76mm 长度15M或定制','OD476-T076',110,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (58,2003,'OD/Thickness','6.35*0.76','{\"外径\":\"6.35mm\",\"壁厚\":\"0.76mm\",\"长度\":\"15M或定制\",\"来源链接\":\"https://www.hongtai-copper.com/pancake-coil.html\"}','OD 6.35mm 壁厚0.76mm 长度15M或定制','OD635-T076',120,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (59,2003,'OD/Thickness','7.94*0.81','{\"外径\":\"7.94mm\",\"壁厚\":\"0.81mm\",\"长度\":\"15M或定制\",\"来源链接\":\"https://www.hongtai-copper.com/pancake-coil.html\"}','OD 7.94mm 壁厚0.81mm 长度15M或定制','OD794-T081',130,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (60,2003,'OD/Thickness','9.52*0.81','{\"外径\":\"9.52mm\",\"壁厚\":\"0.81mm\",\"长度\":\"15M或定制\",\"来源链接\":\"https://www.hongtai-copper.com/pancake-coil.html\"}','OD 9.52mm 壁厚0.81mm 长度15M或定制','OD952-T081',140,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (61,2003,'OD/Thickness','12.7*0.81','{\"外径\":\"12.7mm\",\"壁厚\":\"0.81mm\",\"长度\":\"15M或定制\",\"来源链接\":\"https://www.hongtai-copper.com/pancake-coil.html\"}','OD 12.7mm 壁厚0.81mm 长度15M或定制','OD127-T081',150,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (62,2003,'OD/Thickness','15.88*0.88','{\"外径\":15.88,\"壁厚\":0.88,\"长度\":\"15M或定制\",\"来源链接\":\"https://www.hongtai-copper.com/pancake-coil.html\",\"长度范围\":\"15\"}','OD 15.88mm 壁厚0.88mm 长度15M或定制','OD1588-T088',160,'ENABLED','2026-07-08 15:19:21','2026-07-12 13:14:59');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (63,2003,'OD/Thickness','19.05*0.88','{\"外径\":\"19.05mm\",\"壁厚\":\"0.88mm\",\"长度\":\"15M或定制\",\"来源链接\":\"https://www.hongtai-copper.com/pancake-coil.html\"}','OD 19.05mm 壁厚0.88mm 长度15M或定制','OD1905-T088',170,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (64,2003,'OD/Thickness','22.22*1.14','{\"外径\":\"22.22mm\",\"壁厚\":\"1.14mm\",\"长度\":\"15M或定制\",\"来源链接\":\"https://www.hongtai-copper.com/pancake-coil.html\"}','OD 22.22mm 壁厚1.14mm 长度15M或定制','OD2222-T114',180,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (65,2006,'9mm黑色管','6*9*1.8*180','{\"品名\":\"黑色管\",\"型号\":\"6*9*1.8*180\",\"颜色\":\"黑色\",\"系列\":\"9mm分体式空调保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','9mm 黑色管 6*9*1.8*180','9-BLK-6-9-180',10,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (66,2006,'9mm黑色管','10*9*1.8*140','{\"品名\":\"黑色管\",\"型号\":\"10*9*1.8*140\",\"颜色\":\"黑色\",\"系列\":\"9mm分体式空调保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','9mm 黑色管 10*9*1.8*140','9-BLK-10-9-140',20,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (67,2006,'9mm黑色管','13*9*1.8*120','{\"品名\":\"黑色管\",\"型号\":\"13*9*1.8*120\",\"颜色\":\"黑色\",\"系列\":\"9mm分体式空调保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','9mm 黑色管 13*9*1.8*120','9-BLK-13-9-120',30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (68,2006,'9mm黑色管','16*9*1.8*80','{\"品名\":\"黑色管\",\"型号\":\"16*9*1.8*80\",\"颜色\":\"黑色\",\"系列\":\"9mm分体式空调保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','9mm 黑色管 16*9*1.8*80','9-BLK-16-9-80',40,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (69,2006,'9mm黑色管','19*9*1.8*70','{\"品名\":\"黑色管\",\"型号\":\"19*9*1.8*70\",\"颜色\":\"黑色\",\"系列\":\"9mm分体式空调保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','9mm 黑色管 19*9*1.8*70','9-BLK-19-9-70',50,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (70,2006,'9mm黑色管','22*9*1.8*70','{\"品名\":\"黑色管\",\"型号\":\"22*9*1.8*70\",\"颜色\":\"黑色\",\"系列\":\"9mm分体式空调保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','9mm 黑色管 22*9*1.8*70','9-BLK-22-9-70',60,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (71,2006,'9mm黑色管','25*9*1.8*60','{\"品名\":\"黑色管\",\"型号\":\"25*9*1.8*60\",\"颜色\":\"黑色\",\"系列\":\"9mm分体式空调保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','9mm 黑色管 25*9*1.8*60','9-BLK-25-9-60',70,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (72,2006,'9mm黑色管','32*9*1.8*50','{\"品名\":\"黑色管\",\"型号\":\"32*9*1.8*50\",\"颜色\":\"黑色\",\"系列\":\"9mm分体式空调保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','9mm 黑色管 32*9*1.8*50','9-BLK-32-9-50',80,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (73,2006,'9mm黑色管','38*9*1.8*50','{\"品名\":\"黑色管\",\"型号\":\"38*9*1.8*50\",\"颜色\":\"黑色\",\"系列\":\"9mm分体式空调保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','9mm 黑色管 38*9*1.8*50','9-BLK-38-9-50',90,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (74,2006,'9mm黑色管','48*9*1.8*30','{\"品名\":\"黑色管\",\"型号\":\"48*9*1.8*30\",\"颜色\":\"黑色\",\"系列\":\"9mm分体式空调保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','9mm 黑色管 48*9*1.8*30','9-BLK-48-9-30',100,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (75,2006,'9mm红色管','25*9*1.8*60','{\"品名\":\"红色管\",\"型号\":\"25*9*1.8*60\",\"颜色\":\"红色\",\"系列\":\"9mm分体式空调保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','9mm 红色管 25*9*1.8*60','9-RED-25-9-60',110,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (76,2006,'9mm红色管','32*9*1.8*50','{\"品名\":\"红色管\",\"型号\":\"32*9*1.8*50\",\"颜色\":\"红色\",\"系列\":\"9mm分体式空调保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','9mm 红色管 32*9*1.8*50','9-RED-32-9-50',120,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (77,2006,'9mm蓝色管','25*9*1.8*60','{\"品名\":\"蓝色管\",\"型号\":\"25*9*1.8*60\",\"颜色\":\"蓝色\",\"系列\":\"9mm分体式空调保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','9mm 蓝色管 25*9*1.8*60','9-BLU-25-9-60',130,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (78,2006,'9mm蓝色管','32*9*1.8*50','{\"品名\":\"蓝色管\",\"型号\":\"32*9*1.8*50\",\"颜色\":\"蓝色\",\"系列\":\"9mm分体式空调保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','9mm 蓝色管 32*9*1.8*50','9-BLU-32-9-50',140,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (79,2006,'15mm黑色管','6*15*2*80','{\"品名\":\"黑色管\",\"型号\":\"6*15*2*80\",\"颜色\":\"黑色\",\"系列\":\"15mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','15mm 黑色管 6*15*2*80','15-BLK-6-15-80',210,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (80,2006,'15mm黑色管','10*15*2*80','{\"品名\":\"黑色管\",\"型号\":\"10*15*2*80\",\"颜色\":\"黑色\",\"系列\":\"15mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','15mm 黑色管 10*15*2*80','15-BLK-10-15-80',220,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (81,2006,'15mm黑色管','13*15*2*80','{\"品名\":\"黑色管\",\"型号\":\"13*15*2*80\",\"颜色\":\"黑色\",\"系列\":\"15mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','15mm 黑色管 13*15*2*80','15-BLK-13-15-80',230,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (82,2006,'15mm黑色管','16*15*2*70','{\"品名\":\"黑色管\",\"型号\":\"16*15*2*70\",\"颜色\":\"黑色\",\"系列\":\"15mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','15mm 黑色管 16*15*2*70','15-BLK-16-15-70',240,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (83,2006,'15mm黑色管','20*15*2*70','{\"品名\":\"黑色管\",\"型号\":\"20*15*2*70\",\"颜色\":\"黑色\",\"系列\":\"15mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','15mm 黑色管 20*15*2*70','15-BLK-20-15-70',250,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (84,2006,'15mm黑色管','22*15*2*60','{\"品名\":\"黑色管\",\"型号\":\"22*15*2*60\",\"颜色\":\"黑色\",\"系列\":\"15mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','15mm 黑色管 22*15*2*60','15-BLK-22-15-60',260,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (85,2006,'15mm黑色管','28*15*2*50','{\"品名\":\"黑色管\",\"型号\":\"28*15*2*50\",\"颜色\":\"黑色\",\"系列\":\"15mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','15mm 黑色管 28*15*2*50','15-BLK-28-15-50',270,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (86,2006,'15mm黑色管','32*15*2*30','{\"品名\":\"黑色管\",\"型号\":\"32*15*2*30\",\"颜色\":\"黑色\",\"系列\":\"15mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','15mm 黑色管 32*15*2*30','15-BLK-32-15-30',280,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (87,2006,'15mm红色管','6*15*2*80','{\"品名\":\"红色管\",\"型号\":\"6*15*2*80\",\"颜色\":\"红色\",\"系列\":\"15mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','15mm 红色管 6*15*2*80','15-RED-6-15-80',290,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (88,2006,'15mm红色管','10*15*2*80','{\"品名\":\"红色管\",\"型号\":\"10*15*2*80\",\"颜色\":\"红色\",\"系列\":\"15mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','15mm 红色管 10*15*2*80','15-RED-10-15-80',300,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (89,2006,'15mm红色管','13*15*2*80','{\"品名\":\"红色管\",\"型号\":\"13*15*2*80\",\"颜色\":\"红色\",\"系列\":\"15mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','15mm 红色管 13*15*2*80','15-RED-13-15-80',310,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (90,2006,'15mm红色管','16*15*2*70','{\"品名\":\"红色管\",\"型号\":\"16*15*2*70\",\"颜色\":\"红色\",\"系列\":\"15mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','15mm 红色管 16*15*2*70','15-RED-16-15-70',320,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (91,2006,'15mm红色管','20*15*2*70','{\"品名\":\"红色管\",\"型号\":\"20*15*2*70\",\"颜色\":\"红色\",\"系列\":\"15mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','15mm 红色管 20*15*2*70','15-RED-20-15-70',330,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (92,2006,'15mm蓝色管','6*15*2*80','{\"品名\":\"蓝色管\",\"型号\":\"6*15*2*80\",\"颜色\":\"蓝色\",\"系列\":\"15mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','15mm 蓝色管 6*15*2*80','15-BLU-6-15-80',340,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (93,2006,'15mm蓝色管','10*15*2*80','{\"品名\":\"蓝色管\",\"型号\":\"10*15*2*80\",\"颜色\":\"蓝色\",\"系列\":\"15mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','15mm 蓝色管 10*15*2*80','15-BLU-10-15-80',350,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (94,2006,'15mm蓝色管','13*15*2*80','{\"品名\":\"蓝色管\",\"型号\":\"13*15*2*80\",\"颜色\":\"蓝色\",\"系列\":\"15mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','15mm 蓝色管 13*15*2*80','15-BLU-13-15-80',360,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (95,2006,'15mm蓝色管','16*15*2*70','{\"品名\":\"蓝色管\",\"型号\":\"16*15*2*70\",\"颜色\":\"蓝色\",\"系列\":\"15mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','15mm 蓝色管 16*15*2*70','15-BLU-16-15-70',370,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (96,2006,'15mm蓝色管','20*15*2*70','{\"品名\":\"蓝色管\",\"型号\":\"20*15*2*70\",\"颜色\":\"蓝色\",\"系列\":\"15mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','15mm 蓝色管 20*15*2*70','15-BLU-20-15-70',380,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (97,2006,'20mm黑色管','16*20*2*50','{\"品名\":\"黑色管\",\"型号\":\"16*20*2*50\",\"颜色\":\"黑色\",\"系列\":\"20mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','20mm 黑色管 16*20*2*50','20-BLK-16-20-50',410,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (98,2006,'20mm黑色管','20*20*2*50','{\"品名\":\"黑色管\",\"型号\":\"20*20*2*50\",\"颜色\":\"黑色\",\"系列\":\"20mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','20mm 黑色管 20*20*2*50','20-BLK-20-20-50',420,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (99,2006,'20mm黑色管','22*20*2*45','{\"品名\":\"黑色管\",\"型号\":\"22*20*2*45\",\"颜色\":\"黑色\",\"系列\":\"20mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','20mm 黑色管 22*20*2*45','20-BLK-22-20-45',430,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (100,2006,'20mm黑色管','25*20*2*40','{\"品名\":\"黑色管\",\"型号\":\"25*20*2*40\",\"颜色\":\"黑色\",\"系列\":\"20mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','20mm 黑色管 25*20*2*40','20-BLK-25-20-40',440,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (101,2006,'20mm黑色管','28*20*2*40','{\"品名\":\"黑色管\",\"型号\":\"28*20*2*40\",\"颜色\":\"黑色\",\"系列\":\"20mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','20mm 黑色管 28*20*2*40','20-BLK-28-20-40',450,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (102,2006,'20mm黑色管','32*20*2*30','{\"品名\":\"黑色管\",\"型号\":\"32*20*2*30\",\"颜色\":\"黑色\",\"系列\":\"20mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','20mm 黑色管 32*20*2*30','20-BLK-32-20-30',460,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (103,2006,'20mm黑色管','34*20*2*30','{\"品名\":\"黑色管\",\"型号\":\"34*20*2*30\",\"颜色\":\"黑色\",\"系列\":\"20mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','20mm 黑色管 34*20*2*30','20-BLK-34-20-30',470,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (104,2006,'20mm黑色管','43*20*2*25','{\"品名\":\"黑色管\",\"型号\":\"43*20*2*25\",\"颜色\":\"黑色\",\"系列\":\"20mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','20mm 黑色管 43*20*2*25','20-BLK-43-20-25',480,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (105,2006,'20mm黑色管','48*20*2*25','{\"品名\":\"黑色管\",\"型号\":\"48*20*2*25\",\"颜色\":\"黑色\",\"系列\":\"20mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','20mm 黑色管 48*20*2*25','20-BLK-48-20-25',490,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (106,2006,'20mm红色管','16*20*2*50','{\"品名\":\"红色管\",\"型号\":\"16*20*2*50\",\"颜色\":\"红色\",\"系列\":\"20mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','20mm 红色管 16*20*2*50','20-RED-16-20-50',500,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (107,2006,'20mm红色管','22*20*2*45','{\"品名\":\"红色管\",\"型号\":\"22*20*2*45\",\"颜色\":\"红色\",\"系列\":\"20mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','20mm 红色管 22*20*2*45','20-RED-22-20-45',510,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (108,2006,'20mm红色管','25*20*2*40','{\"品名\":\"红色管\",\"型号\":\"25*20*2*40\",\"颜色\":\"红色\",\"系列\":\"20mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','20mm 红色管 25*20*2*40','20-RED-25-20-40',520,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (109,2006,'20mm红色管','28*20*2*40','{\"品名\":\"红色管\",\"型号\":\"28*20*2*40\",\"颜色\":\"红色\",\"系列\":\"20mm工程用Class1橡塑保温管\",\"阻燃等级\":\"B1级\",\"来源链接\":\"https://www.hongtai-copper.com/xiang-su-bao-wen-guan.html\"}','20mm 红色管 28*20*2*40','20-RED-28-20-40',530,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (110,2007,'大金空调系列','HONGTAI-22T','{\"品牌系列\":\"大金空调系列\",\"型号\":\"HONGTAI-22T\",\"适配品牌\":\"大金\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','大金空调系列 HONGTAI-22T','BR-DK-22T',10,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (111,2007,'大金空调系列','HONGTAI-33T','{\"品牌系列\":\"大金空调系列\",\"型号\":\"HONGTAI-33T\",\"适配品牌\":\"大金\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','大金空调系列 HONGTAI-33T','BR-DK-33T',20,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (112,2007,'大金空调系列','HONGTAI-72T','{\"品牌系列\":\"大金空调系列\",\"型号\":\"HONGTAI-72T\",\"适配品牌\":\"大金\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','大金空调系列 HONGTAI-72T','BR-DK-72T',30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (113,2007,'大金空调系列','HONGTAI-73T','{\"品牌系列\":\"大金空调系列\",\"型号\":\"HONGTAI-73T\",\"适配品牌\":\"大金\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','大金空调系列 HONGTAI-73T','BR-DK-73T',40,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (114,2007,'大金空调系列','HONGTAI-75T','{\"品牌系列\":\"大金空调系列\",\"型号\":\"HONGTAI-75T\",\"适配品牌\":\"大金\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','大金空调系列 HONGTAI-75T','BR-DK-75T',50,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (115,2007,'格力空调系列','HONGTAI-FQ01A','{\"品牌系列\":\"格力空调系列\",\"型号\":\"HONGTAI-FQ01A\",\"适配品牌\":\"格力\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','格力空调系列 HONGTAI-FQ01A','BR-GL-FQ01A',60,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (116,2007,'格力空调系列','HONGTAI-FQ01B','{\"品牌系列\":\"格力空调系列\",\"型号\":\"HONGTAI-FQ01B\",\"适配品牌\":\"格力\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','格力空调系列 HONGTAI-FQ01B','BR-GL-FQ01B',70,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (117,2007,'格力空调系列','HONGTAI-FQ01','{\"品牌系列\":\"格力空调系列\",\"型号\":\"HONGTAI-FQ01\",\"适配品牌\":\"格力\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','格力空调系列 HONGTAI-FQ01','BR-GL-FQ01',80,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (118,2007,'格力空调系列','HONGTAI-FQ02','{\"品牌系列\":\"格力空调系列\",\"型号\":\"HONGTAI-FQ02\",\"适配品牌\":\"格力\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','格力空调系列 HONGTAI-FQ02','BR-GL-FQ02',90,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (119,2007,'格力空调系列','HONGTAI-FQ03A','{\"品牌系列\":\"格力空调系列\",\"型号\":\"HONGTAI-FQ03A\",\"适配品牌\":\"格力\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','格力空调系列 HONGTAI-FQ03A','BR-GL-FQ03A',100,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (120,2007,'格力空调系列','HONGTAI-FQ04','{\"品牌系列\":\"格力空调系列\",\"型号\":\"HONGTAI-FQ04\",\"适配品牌\":\"格力\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','格力空调系列 HONGTAI-FQ04','BR-GL-FQ04',110,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (121,2007,'美的空调系列','HONGTAI-01C','{\"品牌系列\":\"美的空调系列\",\"型号\":\"HONGTAI-01C\",\"适配品牌\":\"美的\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','美的空调系列 HONGTAI-01C','BR-MD-01C',120,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (122,2007,'美的空调系列','HONGTAI-02C','{\"品牌系列\":\"美的空调系列\",\"型号\":\"HONGTAI-02C\",\"适配品牌\":\"美的\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','美的空调系列 HONGTAI-02C','BR-MD-02C',130,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (123,2007,'美的空调系列','HONGTAI-03C','{\"品牌系列\":\"美的空调系列\",\"型号\":\"HONGTAI-03C\",\"适配品牌\":\"美的\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','美的空调系列 HONGTAI-03C','BR-MD-03C',140,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (124,2007,'美的空调系列','HONGTAI-04C','{\"品牌系列\":\"美的空调系列\",\"型号\":\"HONGTAI-04C\",\"适配品牌\":\"美的\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','美的空调系列 HONGTAI-04C','BR-MD-04C',150,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (125,2007,'美的空调系列','HONGTAI-05C','{\"品牌系列\":\"美的空调系列\",\"型号\":\"HONGTAI-05C\",\"适配品牌\":\"美的\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','美的空调系列 HONGTAI-05C','BR-MD-05C',160,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (126,2007,'日立空调系列','HONGTAI-52SN','{\"品牌系列\":\"日立空调系列\",\"型号\":\"HONGTAI-52SN\",\"适配品牌\":\"日立\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','日立空调系列 HONGTAI-52SN','BR-RL-52SN',170,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (127,2007,'日立空调系列','HONGTAI-102SN','{\"品牌系列\":\"日立空调系列\",\"型号\":\"HONGTAI-102SN\",\"适配品牌\":\"日立\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','日立空调系列 HONGTAI-102SN','BR-RL-102SN',180,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (128,2007,'日立空调系列','HONGTAI-162SN','{\"品牌系列\":\"日立空调系列\",\"型号\":\"HONGTAI-162SN\",\"适配品牌\":\"日立\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','日立空调系列 HONGTAI-162SN','BR-RL-162SN',190,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (129,2007,'日立空调系列','HONGTAI-242SN','{\"品牌系列\":\"日立空调系列\",\"型号\":\"HONGTAI-242SN\",\"适配品牌\":\"日立\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','日立空调系列 HONGTAI-242SN','BR-RL-242SN',200,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (130,2007,'日立空调系列','HONGTAI-302SN','{\"品牌系列\":\"日立空调系列\",\"型号\":\"HONGTAI-302SN\",\"适配品牌\":\"日立\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','日立空调系列 HONGTAI-302SN','BR-RL-302SN',210,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (131,2007,'日立空调系列','HONGTAI-462SN','{\"品牌系列\":\"日立空调系列\",\"型号\":\"HONGTAI-462SN\",\"适配品牌\":\"日立\",\"来源链接\":\"https://www.hongtai-copper.com/air-conditioning-disperse-pipe.html\"}','日立空调系列 HONGTAI-462SN','BR-RL-462SN',220,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (132,2008,'卡槽紫铜直接','常用国标/英制规格','{\"管件类型\":\"卡槽紫铜直接\",\"国标规格\":\"6.35、7.94、9.52、12.7、15.88、19.05、22.2、28.6、34.93、41.28、53.98、66.68、80、89、108、133、159、219\",\"英制规格\":\"1/4、5/16、3/8、1/2、5/8、3/4、7/8、1 1/8、1 3/8、1 5/8、2 1/8、2 5/8、3 1/8、3 5/8、4 1/8、5 1/8、6 1/8、8 1/8\",\"来源链接\":\"https://www.hongtai-copper.com/copper-fitting.html\"}','卡槽紫铜直接 常用国标/英制规格','FITTING-SOCKET',10,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (133,2008,'异径紫铜直接','常用国标/英制规格','{\"管件类型\":\"异径紫铜直接\",\"规格说明\":\"官网表格列出多组变径国标/英制组合，支持定制生产\",\"来源链接\":\"https://www.hongtai-copper.com/copper-fitting.html\"}','异径紫铜直接 常用国标/英制规格','FITTING-REDUCER',20,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (134,2008,'紫铜三通','常用国标/英制规格','{\"管件类型\":\"紫铜三通\",\"国标规格\":\"4.76、6.35、7.94、9.52、12.7、15.88、19.05、22.2、28.6、34.9、41.28、53.98、66.68、79.38、92.08\",\"来源链接\":\"https://www.hongtai-copper.com/copper-fitting.html\"}','紫铜三通 常用国标/英制规格','FITTING-TEE',30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (135,2009,'产品编码','HTCG12-HD','{\"产品编码\":\"HTCG12\",\"规格描述\":\"12mm HDx5.8M\",\"来源链接\":\"https://www.hongtai-copper.com/137.html\"}','HTCG12 12mm HDx5.8M','HTCG12-HD',10,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (136,2009,'产品编码','HTCG15','{\"产品编码\":\"HTCG15\",\"规格描述\":\"15mm HDx5.8M\",\"来源链接\":\"https://www.hongtai-copper.com/137.html\"}','HTCG15 15mm HDx5.8M','HTCG15',20,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (137,2009,'产品编码','HTCG18-HD','{\"产品编码\":\"HTCG18\",\"规格描述\":\"18mm HD x5.8M\",\"来源链接\":\"https://www.hongtai-copper.com/137.html\"}','HTCG18 18mm HD x5.8M','HTCG18-HD',30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (138,2009,'产品编码','HTCG22','{\"产品编码\":\"HTCG22\",\"规格描述\":\"22mm HDx5.8M\",\"来源链接\":\"https://www.hongtai-copper.com/137.html\"}','HTCG22 22mm HDx5.8M','HTCG22',40,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (139,2009,'产品编码','HTCG28','{\"产品编码\":\"HTCG28\",\"规格描述\":\"28mm HDx5.8M\",\"来源链接\":\"https://www.hongtai-copper.com/137.html\"}','HTCG28 28mm HDx5.8M','HTCG28',50,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (140,2009,'产品编码','HTCG35','{\"产品编码\":\"HTCG35\",\"规格描述\":\"35mm HDx5.8M\",\"来源链接\":\"https://www.hongtai-copper.com/137.html\"}','HTCG35 35mm HDx5.8M','HTCG35',60,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (141,2009,'产品编码','HTCG42','{\"产品编码\":\"HTCG42\",\"规格描述\":\"42mm HDx5.8M\",\"来源链接\":\"https://www.hongtai-copper.com/137.html\"}','HTCG42 42mm HDx5.8M','HTCG42',70,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (142,2009,'产品编码','HTCG54','{\"产品编码\":\"HTCG54\",\"规格描述\":\"54mm HDx5.8M\",\"来源链接\":\"https://www.hongtai-copper.com/137.html\"}','HTCG54 54mm HDx5.8M','HTCG54',80,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (143,2009,'产品编码','HTCG6-COIL','{\"产品编码\":\"HTCG6\",\"规格描述\":\"PE COATED 6mm Coil\",\"来源链接\":\"https://www.hongtai-copper.com/137.html\"}','HTCG6 PE COATED 6mm Coil','HTCG6-COIL',90,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (144,2009,'产品编码','HTCG8-COIL','{\"产品编码\":\"HTCG8\",\"规格描述\":\"PE COATED 8mm Coil\",\"来源链接\":\"https://www.hongtai-copper.com/137.html\"}','HTCG8 PE COATED 8mm Coil','HTCG8-COIL',100,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (145,2009,'产品编码','HTCG10-COIL','{\"产品编码\":\"HTCG10\",\"规格描述\":\"PE COATED 10mm Coil\",\"来源链接\":\"https://www.hongtai-copper.com/137.html\"}','HTCG10 PE COATED 10mm Coil','HTCG10-COIL',110,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (146,2009,'产品编码','HTCG12-COIL','{\"产品编码\":\"HTCG12\",\"规格描述\":\"PE COATED 12mm Coil\",\"来源链接\":\"https://www.hongtai-copper.com/137.html\"}','HTCG12 PE COATED 12mm Coil','HTCG12-COIL',120,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (147,2009,'产品编码','HTCG14-COIL','{\"产品编码\":\"HTCG14\",\"规格描述\":\"PE COATED 14mm Coil\",\"来源链接\":\"https://www.hongtai-copper.com/137.html\"}','HTCG14 PE COATED 14mm Coil','HTCG14-COIL',130,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (148,2009,'产品编码','HTCG16-COIL','{\"产品编码\":\"HTCG16\",\"规格描述\":\"PE COATED 16mm Coil\",\"来源链接\":\"https://www.hongtai-copper.com/137.html\"}','HTCG16 PE COATED 16mm Coil','HTCG16-COIL',140,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (149,2009,'产品编码','HTCG18-COIL','{\"产品编码\":\"HTCG18\",\"规格描述\":\"PE COATED 18mm Coil\",\"来源链接\":\"https://www.hongtai-copper.com/137.html\"}','HTCG18 PE COATED 18mm Coil','HTCG18-COIL',150,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (150,2009,'产品编码','HTCG20-COIL','{\"产品编码\":\"HTCG20\",\"规格描述\":\"PE COATED 20mm Coil\",\"来源链接\":\"https://www.hongtai-copper.com/137.html\"}','HTCG20 PE COATED 20mm Coil','HTCG20-COIL',160,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (151,2010,'内螺纹规格','φ7.00×0.27+0.15-53-18','{\"序号\":\"1\",\"规格\":\"φ7.00×0.27+0.15-53-18\",\"外径\":\"7.00mm\",\"内径\":\"6.16mm\",\"底壁厚\":\"0.27mm\",\"齿高\":\"0.15mm\",\"总壁厚\":\"0.42mm\",\"齿顶角\":\"53\",\"螺旋角\":\"18\",\"螺纹数\":\"60\",\"来源链接\":\"https://www.hongtai-copper.com/104.html\"}','φ7.00×0.27+0.15-53-18','IG-700-027-015-53-18',10,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (152,2010,'内螺纹规格','φ7.00×0.25+0.18-40-18','{\"序号\":\"2\",\"规格\":\"φ7.00×0.25+0.18-40-18\",\"外径\":\"7.00mm\",\"内径\":\"6.14mm\",\"底壁厚\":\"0.25mm\",\"齿高\":\"0.18mm\",\"总壁厚\":\"0.43mm\",\"齿顶角\":\"40\",\"螺旋角\":\"18\",\"螺纹数\":\"50\",\"来源链接\":\"https://www.hongtai-copper.com/104.html\"}','φ7.00×0.25+0.18-40-18','IG-700-025-018-40-18',20,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (153,2010,'内螺纹规格','φ7.00×0.25+0.22-22-16','{\"序号\":\"3\",\"规格\":\"φ7.00×0.25+0.22-22-16\",\"外径\":\"7.00mm\",\"内径\":\"6.06mm\",\"底壁厚\":\"0.25mm\",\"齿高\":\"0.22mm\",\"总壁厚\":\"0.47mm\",\"齿顶角\":\"22\",\"螺旋角\":\"16\",\"螺纹数\":\"54\",\"来源链接\":\"https://www.hongtai-copper.com/104.html\"}','φ7.00×0.25+0.22-22-16','IG-700-025-022-22-16',30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (154,2010,'内螺纹规格','φ9.52×0.28+0.15-53-18','{\"序号\":\"4\",\"规格\":\"φ9.52×0.28+0.15-53-18\",\"外径\":\"9.52mm\",\"内径\":\"8.66mm\",\"底壁厚\":\"0.28mm\",\"齿高\":\"0.15mm\",\"总壁厚\":\"0.43mm\",\"齿顶角\":\"53\",\"螺旋角\":\"18\",\"螺纹数\":\"60\",\"来源链接\":\"https://www.hongtai-copper.com/104.html\"}','φ9.52×0.28+0.15-53-18','IG-952-028-015-53-18',40,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (155,2010,'内螺纹规格','φ9.52×0.30+0.20-53-18','{\"序号\":\"5\",\"规格\":\"φ9.52×0.30+0.20-53-18\",\"外径\":\"9.52mm\",\"内径\":\"8.52mm\",\"底壁厚\":\"0.30mm\",\"齿高\":\"0.20mm\",\"总壁厚\":\"0.50mm\",\"齿顶角\":\"53\",\"螺旋角\":\"18\",\"螺纹数\":\"60\",\"来源链接\":\"https://www.hongtai-copper.com/104.html\"}','φ9.52×0.30+0.20-53-18','IG-952-030-020-53-18',50,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (156,2010,'内螺纹规格','φ9.52×0.27+0.16-30-18','{\"序号\":\"6\",\"规格\":\"φ9.52×0.27+0.16-30-18\",\"外径\":\"9.52mm\",\"内径\":\"8.66mm\",\"底壁厚\":\"0.27mm\",\"齿高\":\"0.16mm\",\"总壁厚\":\"0.43mm\",\"齿顶角\":\"30\",\"螺旋角\":\"18\",\"螺纹数\":\"70\",\"来源链接\":\"https://www.hongtai-copper.com/104.html\"}','φ9.52×0.27+0.16-30-18','IG-952-027-016-30-18',60,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (157,2011,'产品编码','HTCF183A','{\"产品编码\":\"HTCF183A\",\"规格描述\":\"1.83mmx0.66mm\",\"来源链接\":\"https://www.hongtai-copper.com/138.html\"}','HTCF183A 1.83mmx0.66mm','HTCF183A',10,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (158,2011,'产品编码','HTCF183B','{\"产品编码\":\"HTCF183B\",\"规格描述\":\"1.83mm x0.711mm\",\"来源链接\":\"https://www.hongtai-copper.com/138.html\"}','HTCF183B 1.83mm x0.711mm','HTCF183B',20,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (159,2011,'产品编码','HTCF206A','{\"产品编码\":\"HTCF206A\",\"规格描述\":\"2.06mmx0.787mm\",\"来源链接\":\"https://www.hongtai-copper.com/138.html\"}','HTCF206A 2.06mmx0.787mm','HTCF206A',30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (160,2011,'产品编码','HTCF206B','{\"产品编码\":\"HTCF206B\",\"规格描述\":\"2.06mmx0.838mm\",\"来源链接\":\"https://www.hongtai-copper.com/138.html\"}','HTCF206B 2.06mmx0.838mm','HTCF206B',40,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (161,2011,'产品编码','HTCF221A','{\"产品编码\":\"HTCF221A\",\"规格描述\":\"2.21mmx0.914mm\",\"来源链接\":\"https://www.hongtai-copper.com/138.html\"}','HTCF221A 2.21mmx0.914mm','HTCF221A',50,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (162,2011,'产品编码','HTCF221B','{\"产品编码\":\"HTCF221B\",\"规格描述\":\"2.21mmx0.991mm\",\"来源链接\":\"https://www.hongtai-copper.com/138.html\"}','HTCF221B 2.21mmx0.991mm','HTCF221B',60,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (163,2011,'产品编码','HTCF236','{\"产品编码\":\"HTCF236\",\"规格描述\":\"2.36mmx1.067mm\",\"来源链接\":\"https://www.hongtai-copper.com/138.html\"}','HTCF236 2.36mmx1.067mm','HTCF236',70,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (164,2011,'产品编码','HTCF247','{\"产品编码\":\"HTCF247\",\"规格描述\":\"2.46mmx1.168mm\",\"来源链接\":\"https://www.hongtai-copper.com/138.html\"}','HTCF247 2.46mmx1.168mm','HTCF247',80,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (165,2011,'产品编码','HTCF251','{\"产品编码\":\"HTCF251\",\"规格描述\":\"2.51mmx1.245mm\",\"来源链接\":\"https://www.hongtai-copper.com/138.html\"}','HTCF251 2.51mmx1.245mm','HTCF251',90,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (166,2011,'产品编码','HTCF269','{\"产品编码\":\"HTCF269\",\"规格描述\":\"2.69mmx1.37mm\",\"来源链接\":\"https://www.hongtai-copper.com/138.html\"}','HTCF269 2.69mmx1.37mm','HTCF269',100,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (167,2011,'产品编码','HTCF284','{\"产品编码\":\"HTCF284\",\"规格描述\":\"2.84mm x1.50mm\",\"来源链接\":\"https://www.hongtai-copper.com/138.html\"}','HTCF284 2.84mm x1.50mm','HTCF284',110,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (168,2011,'产品编码','HTCF318A','{\"产品编码\":\"HTCF318A\",\"规格描述\":\"3.18mmx1.63mm\",\"来源链接\":\"https://www.hongtai-copper.com/138.html\"}','HTCF318A 3.18mmx1.63mm','HTCF318A',120,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (169,2011,'产品编码','HTCF318B','{\"产品编码\":\"HTCF318B\",\"规格描述\":\"3.18mmx1.78mm\",\"来源链接\":\"https://www.hongtai-copper.com/138.html\"}','HTCF318B 3.18mmx1.78mm','HTCF318B',130,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (170,2011,'产品编码','HTCF318C','{\"产品编码\":\"HTCF318C\",\"规格描述\":\"3.18mmx1.91mm\",\"来源链接\":\"https://www.hongtai-copper.com/138.html\"}','HTCF318C 3.18mmx1.91mm','HTCF318C',140,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (171,2012,'截面类型','矩形/方形/椭圆/半圆/D型/十字花','{\"截面类型\":\"矩形管、方形管、椭圆管、半圆管、D型管、十字花管\",\"材质\":\"紫铜\",\"来源链接\":\"https://www.hongtai-copper.com/141.html\"}','矩形/方形/椭圆/半圆/D型/十字花等异形管','PROFILED-CUSTOM',10,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (172,2014,'产品编码','HTCJ25-3','{\"产品编码\":\"HTCJ25-3\",\"尺寸\":\"25X3\",\"规格描述\":\"COPPER BAR 25x3mm\",\"来源链接\":\"https://www.hongtai-copper.com/140.html\"}','HTCJ25-3 COPPER BAR 25x3mm','HTCJ25-3',10,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (173,2014,'产品编码','HTCJ25-6','{\"产品编码\":\"HTCJ25-6\",\"尺寸\":\"25X6\",\"规格描述\":\"COPPER BAR 25x6mm\",\"来源链接\":\"https://www.hongtai-copper.com/140.html\"}','HTCJ25-6 COPPER BAR 25x6mm','HTCJ25-6',20,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (174,2014,'产品编码','HTCJ30-6','{\"产品编码\":\"HTCJ30-6\",\"尺寸\":\"30X6\",\"规格描述\":\"COPPER BAR 30x6mm\",\"来源链接\":\"https://www.hongtai-copper.com/140.html\"}','HTCJ30-6 COPPER BAR 30x6mm','HTCJ30-6',30,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (175,2014,'产品编码','HTCJ40-6','{\"产品编码\":\"HTCJ40-6\",\"尺寸\":\"40X6\",\"规格描述\":\"COPPER BAR 40x6mm\",\"来源链接\":\"https://www.hongtai-copper.com/140.html\"}','HTCJ40-6 COPPER BAR 40x6mm','HTCJ40-6',40,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (176,2014,'产品编码','HTCJ50-6','{\"产品编码\":\"HTCJ50-6\",\"尺寸\":\"50X6\",\"规格描述\":\"COPPER BAR 50x6mm\",\"来源链接\":\"https://www.hongtai-copper.com/140.html\"}','HTCJ50-6 COPPER BAR 50x6mm','HTCJ50-6',50,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (177,2014,'产品编码','HTCJ60-6','{\"产品编码\":\"HTCJ60-6\",\"尺寸\":\"60X6\",\"规格描述\":\"COPPER BAR 60x6mm\",\"来源链接\":\"https://www.hongtai-copper.com/140.html\"}','HTCJ60-6 COPPER BAR 60x6mm','HTCJ60-6',60,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (178,2014,'产品编码','HTCJ80-8','{\"产品编码\":\"HTCJ80-8\",\"尺寸\":\"80X8\",\"规格描述\":\"COPPER BAR 80x8mm\",\"来源链接\":\"https://www.hongtai-copper.com/140.html\"}','HTCJ80-8 COPPER BAR 80x8mm','HTCJ80-8',70,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (179,2014,'产品编码','HTCJ100-6','{\"产品编码\":\"HTCJ100-6\",\"尺寸\":\"100X6\",\"规格描述\":\"COPPER BAR 100x6mm\",\"来源链接\":\"https://www.hongtai-copper.com/140.html\"}','HTCJ100-6 COPPER BAR 100x6mm','HTCJ100-6',80,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (180,2015,'圆焊条','2.5*500','{\"品名\":\"圆焊条\",\"规格\":\"2.5*500mm\",\"来源链接\":\"https://www.hongtai-copper.com/139.html\"}','圆焊条 2.5*500mm','WR-ROUND-25-500',10,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (181,2015,'扁焊条','1.3*3.2*400','{\"品名\":\"扁焊条\",\"规格\":\"1.3*3.2*400mm\",\"来源链接\":\"https://www.hongtai-copper.com/139.html\"}','扁焊条 1.3*3.2*400mm','WR-FLAT-13-32-400',20,'ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_spec_preset` (`id`, `product_series_id`, `name`, `model`, `attribute_json`, `spec_text`, `sku_suffix`, `sort_no`, `status`, `created_at`, `updated_at`) VALUES (304,2015,'哈哈哈','333','{\"品名\":\"圆焊条\"}','哈哈哈 333','',10,'ENABLED','2026-07-10 15:41:39','2026-07-10 15:41:39');
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_spec_preset_value` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `preset_id` bigint unsigned NOT NULL,
  `series_field_id` bigint unsigned NOT NULL,
  `value_text` varchar(1000) NOT NULL DEFAULT '',
  `value_decimal` decimal(30,10) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_spec_preset_value_field` (`preset_id`,`series_field_id`),
  KEY `idx_spec_preset_value_series_field` (`series_field_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1033 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='规格预设字段默认值';
/*!40101 SET character_set_client = @saved_cs_client */;

INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (1,1,1101,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (2,1,1102,'6*9*1.8*180',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (3,1,1103,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (4,2,1101,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (5,2,1102,'10*9*1.8*140',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (6,2,1103,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (7,3,1101,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (8,3,1102,'13*9*1.8*120',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (9,3,1103,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (10,4,1101,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (11,4,1102,'16*9*1.8*80',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (12,4,1103,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (13,5,1101,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (14,5,1102,'19*9*1.8*70',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (15,5,1103,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (16,6,1101,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (17,6,1102,'22*9*1.8*70',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (18,6,1103,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (19,7,1101,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (20,7,1102,'25*9*1.8*60',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (21,7,1103,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (22,8,1101,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (23,8,1102,'32*9*1.8*50',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (24,8,1103,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (25,9,1101,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (26,9,1102,'38*9*1.8*50',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (27,9,1103,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (28,10,1101,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (29,10,1102,'48*9*1.8*30',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (30,10,1103,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (31,11,1101,'红色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (32,11,1102,'25*9*1.8*60',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (33,11,1103,'红色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (34,12,1101,'红色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (35,12,1102,'32*9*1.8*50',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (36,12,1103,'红色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (37,13,1101,'蓝色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (38,13,1102,'25*9*1.8*60',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (39,13,1103,'蓝色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (40,14,1101,'蓝色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (41,14,1102,'32*9*1.8*50',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (42,14,1103,'蓝色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (43,15,1201,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (44,15,1202,'6*15*2*80',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (45,15,1203,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (46,16,1201,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (47,16,1202,'10*15*2*80',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (48,16,1203,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (49,17,1201,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (50,17,1202,'13*15*2*80',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (51,17,1203,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (52,18,1201,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (53,18,1202,'16*15*2*70',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (54,18,1203,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (55,19,1201,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (56,19,1202,'20*15*2*70',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (57,19,1203,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (58,20,1201,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (59,20,1202,'22*15*2*60',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (60,20,1203,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (61,21,1201,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (62,21,1202,'28*15*2*50',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (63,21,1203,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (64,22,1201,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (65,22,1202,'32*15*2*30',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (66,22,1203,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (67,23,1201,'红色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (68,23,1202,'6*15*2*80',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (69,23,1203,'红色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (70,24,1201,'红色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (71,24,1202,'10*15*2*80',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (72,24,1203,'红色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (73,25,1201,'红色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (74,25,1202,'13*15*2*80',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (75,25,1203,'红色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (76,26,1201,'红色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (77,26,1202,'16*15*2*70',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (78,26,1203,'红色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (79,27,1201,'红色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (80,27,1202,'20*15*2*70',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (81,27,1203,'红色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (82,28,1201,'蓝色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (83,28,1202,'6*15*2*80',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (84,28,1203,'蓝色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (85,29,1201,'蓝色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (86,29,1202,'10*15*2*80',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (87,29,1203,'蓝色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (88,30,1201,'蓝色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (89,30,1202,'13*15*2*80',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (90,30,1203,'蓝色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (91,31,1201,'蓝色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (92,31,1202,'16*15*2*70',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (93,31,1203,'蓝色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (94,32,1201,'蓝色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (95,32,1202,'20*15*2*70',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (96,32,1203,'蓝色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (97,48,300023,'150',150.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (98,48,300024,'150',150.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (99,48,300025,'350',350.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (100,48,300026,'150',150.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (101,48,300027,'300',300.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (102,49,300023,'200',200.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (103,49,300024,'200',200.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (104,49,300025,'400',400.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (105,49,300026,'200',200.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (106,49,300027,'400',400.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (107,50,300023,'250',250.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (108,50,300024,'250',250.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (109,50,300025,'450',450.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (110,50,300026,'300',300.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (111,50,300027,'450',450.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (112,51,300023,'250',250.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (113,51,300024,'250',250.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (114,51,300025,'500',500.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (115,51,300026,'300',300.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (116,51,300027,'500',500.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (117,52,300023,'300',300.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (118,52,300024,'300',300.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (119,52,300025,'500',500.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (120,52,300026,'300',300.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (121,52,300027,'500',500.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (122,53,300023,'300',300.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (123,53,300024,'300',300.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (124,53,300025,'590',590.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (125,53,300026,'300',300.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (126,53,300027,'580',580.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (127,54,300023,'350',350.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (128,54,300024,'350',350.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (129,54,300025,'650',650.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (130,54,300026,'350',350.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (131,54,300027,'580',580.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (132,55,300023,'350',350.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (133,55,300024,'350',350.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (134,55,300025,'700',700.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (135,55,300026,'350',350.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (136,55,300027,'600',600.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (137,56,300023,'350',350.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (138,56,300024,'350',350.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (139,56,300025,'750',750.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (140,56,300026,'350',350.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (141,56,300027,'630',630.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (142,57,300021,'4.76mm',4.7600000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (143,57,300022,'0.76mm',0.7600000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (144,58,300021,'6.35mm',6.3500000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (145,58,300022,'0.76mm',0.7600000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (146,59,300021,'7.94mm',7.9400000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (147,59,300022,'0.81mm',0.8100000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (148,60,300021,'9.52mm',9.5200000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (149,60,300022,'0.81mm',0.8100000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (150,61,300021,'12.7mm',12.7000000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (151,61,300022,'0.81mm',0.8100000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (152,62,300021,'15.88',15.8800000000,'2026-07-10 15:17:03','2026-07-12 13:14:59');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (153,62,300022,'0.88',0.8800000000,'2026-07-10 15:17:03','2026-07-12 13:14:59');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (154,63,300021,'19.05mm',19.0500000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (155,63,300022,'0.88mm',0.8800000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (156,64,300021,'22.22mm',22.2200000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (157,64,300022,'1.14mm',1.1400000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (158,65,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (159,65,300062,'6*9*1.8*180',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (160,65,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (161,65,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (162,66,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (163,66,300062,'10*9*1.8*140',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (164,66,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (165,66,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (166,67,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (167,67,300062,'13*9*1.8*120',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (168,67,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (169,67,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (170,68,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (171,68,300062,'16*9*1.8*80',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (172,68,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (173,68,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (174,69,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (175,69,300062,'19*9*1.8*70',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (176,69,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (177,69,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (178,70,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (179,70,300062,'22*9*1.8*70',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (180,70,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (181,70,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (182,71,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (183,71,300062,'25*9*1.8*60',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (184,71,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (185,71,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (186,72,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (187,72,300062,'32*9*1.8*50',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (188,72,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (189,72,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (190,73,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (191,73,300062,'38*9*1.8*50',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (192,73,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (193,73,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (194,74,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (195,74,300062,'48*9*1.8*30',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (196,74,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (197,74,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (198,75,300061,'红色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (199,75,300062,'25*9*1.8*60',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (200,75,300063,'红色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (201,75,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (202,76,300061,'红色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (203,76,300062,'32*9*1.8*50',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (204,76,300063,'红色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (205,76,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (206,77,300061,'蓝色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (207,77,300062,'25*9*1.8*60',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (208,77,300063,'蓝色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (209,77,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (210,78,300061,'蓝色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (211,78,300062,'32*9*1.8*50',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (212,78,300063,'蓝色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (213,78,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (214,79,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (215,79,300062,'6*15*2*80',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (216,79,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (217,79,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (218,80,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (219,80,300062,'10*15*2*80',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (220,80,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (221,80,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (222,81,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (223,81,300062,'13*15*2*80',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (224,81,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (225,81,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (226,82,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (227,82,300062,'16*15*2*70',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (228,82,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (229,82,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (230,83,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (231,83,300062,'20*15*2*70',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (232,83,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (233,83,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (234,84,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (235,84,300062,'22*15*2*60',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (236,84,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (237,84,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (238,85,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (239,85,300062,'28*15*2*50',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (240,85,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (241,85,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (242,86,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (243,86,300062,'32*15*2*30',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (244,86,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (245,86,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (246,87,300061,'红色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (247,87,300062,'6*15*2*80',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (248,87,300063,'红色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (249,87,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (250,88,300061,'红色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (251,88,300062,'10*15*2*80',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (252,88,300063,'红色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (253,88,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (254,89,300061,'红色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (255,89,300062,'13*15*2*80',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (256,89,300063,'红色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (257,89,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (258,90,300061,'红色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (259,90,300062,'16*15*2*70',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (260,90,300063,'红色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (261,90,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (262,91,300061,'红色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (263,91,300062,'20*15*2*70',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (264,91,300063,'红色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (265,91,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (266,92,300061,'蓝色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (267,92,300062,'6*15*2*80',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (268,92,300063,'蓝色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (269,92,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (270,93,300061,'蓝色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (271,93,300062,'10*15*2*80',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (272,93,300063,'蓝色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (273,93,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (274,94,300061,'蓝色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (275,94,300062,'13*15*2*80',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (276,94,300063,'蓝色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (277,94,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (278,95,300061,'蓝色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (279,95,300062,'16*15*2*70',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (280,95,300063,'蓝色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (281,95,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (282,96,300061,'蓝色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (283,96,300062,'20*15*2*70',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (284,96,300063,'蓝色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (285,96,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (286,97,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (287,97,300062,'16*20*2*50',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (288,97,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (289,97,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (290,98,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (291,98,300062,'20*20*2*50',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (292,98,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (293,98,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (294,99,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (295,99,300062,'22*20*2*45',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (296,99,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (297,99,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (298,100,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (299,100,300062,'25*20*2*40',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (300,100,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (301,100,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (302,101,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (303,101,300062,'28*20*2*40',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (304,101,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (305,101,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (306,102,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (307,102,300062,'32*20*2*30',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (308,102,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (309,102,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (310,103,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (311,103,300062,'34*20*2*30',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (312,103,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (313,103,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (314,104,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (315,104,300062,'43*20*2*25',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (316,104,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (317,104,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (318,105,300061,'黑色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (319,105,300062,'48*20*2*25',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (320,105,300063,'黑色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (321,105,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (322,106,300061,'红色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (323,106,300062,'16*20*2*50',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (324,106,300063,'红色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (325,106,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (326,107,300061,'红色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (327,107,300062,'22*20*2*45',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (328,107,300063,'红色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (329,107,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (330,108,300061,'红色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (331,108,300062,'25*20*2*40',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (332,108,300063,'红色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (333,108,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (334,109,300061,'红色管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (335,109,300062,'28*20*2*40',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (336,109,300063,'红色',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (337,109,300064,'B1级',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (338,110,300072,'HONGTAI-22T',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (339,110,300071,'大金空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (340,110,300073,'大金',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (341,111,300072,'HONGTAI-33T',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (342,111,300071,'大金空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (343,111,300073,'大金',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (344,112,300072,'HONGTAI-72T',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (345,112,300071,'大金空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (346,112,300073,'大金',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (347,113,300072,'HONGTAI-73T',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (348,113,300071,'大金空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (349,113,300073,'大金',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (350,114,300072,'HONGTAI-75T',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (351,114,300071,'大金空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (352,114,300073,'大金',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (353,115,300072,'HONGTAI-FQ01A',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (354,115,300071,'格力空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (355,115,300073,'格力',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (356,116,300072,'HONGTAI-FQ01B',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (357,116,300071,'格力空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (358,116,300073,'格力',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (359,117,300072,'HONGTAI-FQ01',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (360,117,300071,'格力空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (361,117,300073,'格力',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (362,118,300072,'HONGTAI-FQ02',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (363,118,300071,'格力空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (364,118,300073,'格力',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (365,119,300072,'HONGTAI-FQ03A',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (366,119,300071,'格力空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (367,119,300073,'格力',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (368,120,300072,'HONGTAI-FQ04',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (369,120,300071,'格力空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (370,120,300073,'格力',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (371,121,300072,'HONGTAI-01C',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (372,121,300071,'美的空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (373,121,300073,'美的',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (374,122,300072,'HONGTAI-02C',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (375,122,300071,'美的空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (376,122,300073,'美的',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (377,123,300072,'HONGTAI-03C',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (378,123,300071,'美的空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (379,123,300073,'美的',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (380,124,300072,'HONGTAI-04C',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (381,124,300071,'美的空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (382,124,300073,'美的',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (383,125,300072,'HONGTAI-05C',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (384,125,300071,'美的空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (385,125,300073,'美的',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (386,126,300072,'HONGTAI-52SN',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (387,126,300071,'日立空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (388,126,300073,'日立',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (389,127,300072,'HONGTAI-102SN',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (390,127,300071,'日立空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (391,127,300073,'日立',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (392,128,300072,'HONGTAI-162SN',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (393,128,300071,'日立空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (394,128,300073,'日立',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (395,129,300072,'HONGTAI-242SN',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (396,129,300071,'日立空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (397,129,300073,'日立',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (398,130,300072,'HONGTAI-302SN',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (399,130,300071,'日立空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (400,130,300073,'日立',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (401,131,300072,'HONGTAI-462SN',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (402,131,300071,'日立空调系列',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (403,131,300073,'日立',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (404,132,300081,'卡槽紫铜直接',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (405,132,300082,'6.35、7.94、9.52、12.7、15.88、19.05、22.2、28.6、34.93、41.28、53.98、66.68、80、89、108、133、159、219',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (406,132,300083,'1/4、5/16、3/8、1/2、5/8、3/4、7/8、1 1/8、1 3/8、1 5/8、2 1/8、2 5/8、3 1/8、3 5/8、4 1/8、5 1/8、6 1/8、8 1/8',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (407,133,300081,'异径紫铜直接',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (408,134,300081,'紫铜三通',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (409,134,300082,'4.76、6.35、7.94、9.52、12.7、15.88、19.05、22.2、28.6、34.9、41.28、53.98、66.68、79.38、92.08',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (410,135,300091,'HTCG12',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (411,135,300092,'12mm HDx5.8M',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (412,136,300091,'HTCG15',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (413,136,300092,'15mm HDx5.8M',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (414,137,300091,'HTCG18',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (415,137,300092,'18mm HD x5.8M',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (416,138,300091,'HTCG22',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (417,138,300092,'22mm HDx5.8M',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (418,139,300091,'HTCG28',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (419,139,300092,'28mm HDx5.8M',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (420,140,300091,'HTCG35',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (421,140,300092,'35mm HDx5.8M',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (422,141,300091,'HTCG42',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (423,141,300092,'42mm HDx5.8M',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (424,142,300091,'HTCG54',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (425,142,300092,'54mm HDx5.8M',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (426,143,300091,'HTCG6',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (427,143,300092,'PE COATED 6mm Coil',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (428,144,300091,'HTCG8',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (429,144,300092,'PE COATED 8mm Coil',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (430,145,300091,'HTCG10',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (431,145,300092,'PE COATED 10mm Coil',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (432,146,300091,'HTCG12',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (433,146,300092,'PE COATED 12mm Coil',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (434,147,300091,'HTCG14',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (435,147,300092,'PE COATED 14mm Coil',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (436,148,300091,'HTCG16',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (437,148,300092,'PE COATED 16mm Coil',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (438,149,300091,'HTCG18',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (439,149,300092,'PE COATED 18mm Coil',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (440,150,300091,'HTCG20',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (441,150,300092,'PE COATED 20mm Coil',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (442,151,300102,'7.00mm',7.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (443,151,300103,'6.16mm',6.1600000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (444,151,300104,'0.27mm',0.2700000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (445,151,300105,'0.15mm',0.1500000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (446,151,300106,'0.42mm',0.4200000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (447,151,300107,'60',60.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (448,152,300102,'7.00mm',7.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (449,152,300103,'6.14mm',6.1400000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (450,152,300104,'0.25mm',0.2500000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (451,152,300105,'0.18mm',0.1800000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (452,152,300106,'0.43mm',0.4300000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (453,152,300107,'50',50.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (454,153,300102,'7.00mm',7.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (455,153,300103,'6.06mm',6.0600000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (456,153,300104,'0.25mm',0.2500000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (457,153,300105,'0.22mm',0.2200000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (458,153,300106,'0.47mm',0.4700000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (459,153,300107,'54',54.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (460,154,300102,'9.52mm',9.5200000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (461,154,300103,'8.66mm',8.6600000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (462,154,300104,'0.28mm',0.2800000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (463,154,300105,'0.15mm',0.1500000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (464,154,300106,'0.43mm',0.4300000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (465,154,300107,'60',60.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (466,155,300102,'9.52mm',9.5200000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (467,155,300103,'8.52mm',8.5200000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (468,155,300104,'0.30mm',0.3000000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (469,155,300105,'0.20mm',0.2000000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (470,155,300106,'0.50mm',0.5000000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (471,155,300107,'60',60.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (472,156,300102,'9.52mm',9.5200000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (473,156,300103,'8.66mm',8.6600000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (474,156,300104,'0.27mm',0.2700000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (475,156,300105,'0.16mm',0.1600000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (476,156,300106,'0.43mm',0.4300000000,'2026-07-10 15:17:03','2026-07-10 23:05:47');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (477,156,300107,'70',70.0000000000,'2026-07-10 15:17:03','2026-07-10 17:17:30');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (478,157,300111,'HTCF183A',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (479,157,300112,'1.83mmx0.66mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (480,158,300111,'HTCF183B',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (481,158,300112,'1.83mm x0.711mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (482,159,300111,'HTCF206A',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (483,159,300112,'2.06mmx0.787mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (484,160,300111,'HTCF206B',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (485,160,300112,'2.06mmx0.838mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (486,161,300111,'HTCF221A',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (487,161,300112,'2.21mmx0.914mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (488,162,300111,'HTCF221B',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (489,162,300112,'2.21mmx0.991mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (490,163,300111,'HTCF236',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (491,163,300112,'2.36mmx1.067mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (492,164,300111,'HTCF247',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (493,164,300112,'2.46mmx1.168mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (494,165,300111,'HTCF251',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (495,165,300112,'2.51mmx1.245mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (496,166,300111,'HTCF269',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (497,166,300112,'2.69mmx1.37mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (498,167,300111,'HTCF284',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (499,167,300112,'2.84mm x1.50mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (500,168,300111,'HTCF318A',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (501,168,300112,'3.18mmx1.63mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (502,169,300111,'HTCF318B',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (503,169,300112,'3.18mmx1.78mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (504,170,300111,'HTCF318C',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (505,170,300112,'3.18mmx1.91mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (506,171,300121,'矩形管、方形管、椭圆管、半圆管、D型管、十字花管',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (507,171,300122,'紫铜',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (508,172,300141,'HTCJ25-3',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (509,172,300144,'COPPER BAR 25x3mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (510,173,300141,'HTCJ25-6',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (511,173,300144,'COPPER BAR 25x6mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (512,174,300141,'HTCJ30-6',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (513,174,300144,'COPPER BAR 30x6mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (514,175,300141,'HTCJ40-6',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (515,175,300144,'COPPER BAR 40x6mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (516,176,300141,'HTCJ50-6',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (517,176,300144,'COPPER BAR 50x6mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (518,177,300141,'HTCJ60-6',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (519,177,300144,'COPPER BAR 60x6mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (520,178,300141,'HTCJ80-8',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (521,178,300144,'COPPER BAR 80x8mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (522,179,300141,'HTCJ100-6',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (523,179,300144,'COPPER BAR 100x6mm',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (524,180,300151,'圆焊条',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (525,181,300151,'扁焊条',NULL,'2026-07-10 15:17:03','2026-07-10 15:17:03');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (1025,304,300151,'圆焊条',NULL,'2026-07-10 15:41:39','2026-07-10 15:41:39');
INSERT INTO `product_spec_preset_value` (`id`, `preset_id`, `series_field_id`, `value_text`, `value_decimal`, `created_at`, `updated_at`) VALUES (1032,62,300028,'15',NULL,'2026-07-12 13:14:59','2026-07-12 13:14:59');
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_standard` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `code` varchar(128) NOT NULL,
  `name` varchar(255) NOT NULL DEFAULT '',
  `english_name` varchar(255) NOT NULL DEFAULT '',
  `standard_status` varchar(32) NOT NULL DEFAULT '现行',
  `ccs` varchar(32) NOT NULL DEFAULT '',
  `ics` varchar(32) NOT NULL DEFAULT '',
  `publish_date` date DEFAULT NULL,
  `implementation_date` date DEFAULT NULL,
  `abolition_date` date DEFAULT NULL,
  `competent_department` varchar(128) NOT NULL DEFAULT '',
  `responsible_department` varchar(128) NOT NULL DEFAULT '',
  `publisher` varchar(255) NOT NULL DEFAULT '',
  `memo` varchar(500) NOT NULL DEFAULT '',
  `source_url` varchar(500) NOT NULL DEFAULT '',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_standard_code` (`code`)
) ENGINE=InnoDB AUTO_INCREMENT=34 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='产品执行标准';
/*!40101 SET character_set_client = @saved_cs_client */;

INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (1,'GB/T 17791-2017','空调与制冷设备用铜及铜合金无缝管','Seamless copper and copper alloys tube for air conditioner and refrigeration equipment','现行','H62','77.150.30','2017-07-12','2018-02-01',NULL,'中国有色金属工业协会','中国有色金属工业协会','中华人民共和国国家质量监督检验检疫总局、中国国家标准化管理委员会','','https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=2E3CD378F73475075ED3FA21EE2DBF91','ENABLED','2026-07-08 11:31:35','2026-07-09 03:40:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (2,'GB/T 1527-2017','铜及铜合金拉制管','Drawn tube of copper and copper alloys','现行','H62','77.150.30','2017-07-12','2018-06-01',NULL,'中国有色金属工业协会','中国有色金属工业协会','中华人民共和国国家质量监督检验检疫总局、中国国家标准化管理委员会','','https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=3B1DBBA3980946A793B7B1E840F1EBB9','ENABLED','2026-07-08 11:31:35','2026-07-09 03:40:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (3,'GB/T 18033-2017','无缝铜水管和铜气管','Seamless copper tubes for water and gas','现行','H62','77.150.30','2017-07-12','2018-04-01',NULL,'中国有色金属工业协会','中国有色金属工业协会','中华人民共和国国家质量监督检验检疫总局、中国国家标准化管理委员会','','https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=D7FA36527B3A04A6D79A03A27E3FF0E8','ENABLED','2026-07-08 11:31:35','2026-07-09 03:40:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (4,'YS/T 450-2013','铜及铜合金管材','','现行','','',NULL,NULL,NULL,'','','','','','ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (5,'YS/T 650-2020','医用气体和真空用无缝铜管','','现行','','',NULL,NULL,NULL,'','','','','','ENABLED','2026-07-08 11:31:35','2026-07-08 11:31:35');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (6,'GB/T 17794-2008','柔性泡沫橡塑绝热制品','Preformed flexible elastomeric cellular thermal insulation','废止','Q25','91.120.10','2008-06-30','2009-04-01',NULL,'中国建筑材料联合会','中国建筑材料联合会','中华人民共和国国家质量监督检验检疫总局、中国国家标准化管理委员会','2009-04-01实施,代替GB/T 17794-1999','https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=60B9DCF6C0D19A830ADF6FCC54624BC1','ENABLED','2026-07-08 11:31:35','2026-07-09 03:40:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (7,'GB 8624-2012','建筑材料及制品燃烧性能分级','Classification for burning behavior of building materials and products','现行','C80','13.220.50','2012-12-31','2013-10-01',NULL,'国家消防救援局','国家消防救援局','中华人民共和国国家质量监督检验检疫总局、中国国家标准化管理委员会','','https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=EA060DF73D92A5AA9F63C1743DDB75DB','ENABLED','2026-07-08 11:31:35','2026-07-09 03:40:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (8,'GB/T 17791','空调与制冷设备用铜及铜合金无缝管','Seamless copper and copper alloys tube for air conditioner and refrigeration equipment','现行','H62','77.150.30','2017-07-12','2018-02-01',NULL,'中国有色金属工业协会','中国有色金属工业协会','中华人民共和国国家质量监督检验检疫总局、中国国家标准化管理委员会','','https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=2E3CD378F73475075ED3FA21EE2DBF91','ENABLED','2026-07-08 15:19:21','2026-07-09 03:40:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (9,'GB/T 1527','铜及铜合金拉制管','Drawn tube of copper and copper alloys','现行','H62','77.150.30','2017-07-12','2018-06-01',NULL,'中国有色金属工业协会','中国有色金属工业协会','中华人民共和国国家质量监督检验检疫总局、中国国家标准化管理委员会','','https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=3B1DBBA3980946A793B7B1E840F1EBB9','ENABLED','2026-07-08 15:19:21','2026-07-09 03:40:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (10,'YS/T 450','铜及铜合金管材','','现行','','',NULL,NULL,NULL,'','','','','','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (11,'ASTM B280','空调制冷用无缝铜管','','现行','','',NULL,NULL,NULL,'','','','','','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (12,'ASTM B68','无缝铜管标准','','现行','','',NULL,NULL,NULL,'','','','','','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (13,'ASTM B75','无缝铜管标准','','现行','','',NULL,NULL,NULL,'','','','','','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (14,'ASTM B88','无缝铜水管标准','','现行','','',NULL,NULL,NULL,'','','','','','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (15,'ASTM B819','医用气体系统铜管标准','','现行','','',NULL,NULL,NULL,'','','','','','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (16,'EN 12735','空调制冷用铜管标准','','现行','','',NULL,NULL,NULL,'','','','','','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (17,'AS 1571','铜管标准','','现行','','',NULL,NULL,NULL,'','','','','','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (18,'JIS H3300','铜及铜合金无缝管标准','','现行','','',NULL,NULL,NULL,'','','','','','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (19,'EN 1057','水和气体用铜管标准','','现行','','',NULL,NULL,NULL,'','','','','','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (20,'GB/T 17794-2021','柔性泡沫橡塑绝热制品','Preformed flexible elastomeric foam thermal insulation','现行','Q25','91.120.10','2021-08-20','2022-03-01',NULL,'中国建筑材料联合会','中国建筑材料联合会','国家市场监督管理总局、中国国家标准化管理委员会','第1号修改单','https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=7621F4ABF8064F44B28579459E868638','ENABLED','2026-07-08 15:19:21','2026-07-09 03:40:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (21,'BS864','管件标准','','现行','','',NULL,NULL,NULL,'','','','','','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (22,'ANSI B16.22','铜及铜合金焊接管件标准','','现行','','',NULL,NULL,NULL,'','','','','','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (23,'YS/T 451-2002','覆塑铜管相关标准','','现行','','',NULL,NULL,NULL,'','','','','','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (24,'GB/T 20928','无缝内螺纹铜管','Seamless inner grooved copper tube','现行','H62','77.150.30','2020-09-29','2021-08-01',NULL,'中国有色金属工业协会','中国有色金属工业协会','国家市场监督管理总局、中国国家标准化管理委员会','','https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=C69D24D43B2A74314E48A3B03136D038','ENABLED','2026-07-08 15:19:21','2026-07-09 03:40:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (25,'GB/T 1531-2020','铜及铜合金毛细管','Copper and copper alloy capillary tube','现行','H62','77.150.30','2020-09-29','2021-08-01',NULL,'中国有色金属工业协会','中国有色金属工业协会','国家市场监督管理总局、中国国家标准化管理委员会','','https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=4796E7EE852FFE815013638A1AA99649','ENABLED','2026-07-08 15:19:21','2026-07-09 03:40:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (26,'ASTM B360','硬拉铜毛细管标准','','现行','','',NULL,NULL,NULL,'','','','','','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (27,'GB/T 1527-2006','铜及铜合金拉制管','Drawn tube of copper and copper alloys','废止','H62','77.150.30','2006-09-26','2007-02-01',NULL,'中国有色金属工业协会','中国有色金属工业协会','中华人民共和国国家质量监督检验检疫总局、中国国家标准化管理委员会','2007-02-01实施,2007-02-01实施,代替GB/T 1527-1997,GB/T 8010-1987','https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=0B3390F336CB05022DFCAE9A61972D24','ENABLED','2026-07-08 15:19:21','2026-07-09 03:40:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (28,'GB 5585-85','电工用铜、铝及其合金母线','','现行','','',NULL,NULL,NULL,'','','','','','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (29,'GB/T 6418-2008','铜基钎料','Copper base brazing filler metals','废止','J33','25.160.50','2008-06-26','2009-01-01',NULL,'国家标准委','国家标准委','中华人民共和国国家质量监督检验检疫总局、中国国家标准化管理委员会','2009-01-01实施,代替GB/T 6418-1993','https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=52A16D0E5F531246306202F90D8B2C06','ENABLED','2026-07-08 15:19:21','2026-07-09 03:40:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (31,'GB/T 6418.1-2025','铜基钎料 第1部分：实心钎料','Copper base brazing filler metals—Part 1：Solid brazing filler metals','现行','J33','25.160.50','2025-03-28','2025-10-01',NULL,'国家标准委','国家标准委','国家市场监督管理总局、国家标准化管理委员会','','https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=30C78CC5EB93DDED6B5DA2E71CD448AE','ENABLED','2026-07-09 03:40:21','2026-07-09 03:40:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (32,'GB/T 5585.1-2018','电工用铜、铝及其合金母线  第1部分：铜和铜合金母线','Copper or aluminium and its alloy bus bars for electrical purposes—Part 1: Copper and copper alloy bus bars','现行','K11','29.060.10','2018-12-28','2019-07-01',NULL,'中国电器工业协会','中国电器工业协会','国家市场监督管理总局、中国国家标准化管理委员会','','https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=942B6BCC24D80C0796F31F217A4A4370','ENABLED','2026-07-09 03:40:21','2026-07-09 03:40:21');
INSERT INTO `product_standard` (`id`, `code`, `name`, `english_name`, `standard_status`, `ccs`, `ics`, `publish_date`, `implementation_date`, `abolition_date`, `competent_department`, `responsible_department`, `publisher`, `memo`, `source_url`, `status`, `created_at`, `updated_at`) VALUES (33,'GB/T 5585.2-2018','电工用铜、铝及其合金母线  第2部分：铝和铝合金母线','Copper or aluminium and its alloy bus bars for electrical purposes—Part 2: Aluminium and aluminium alloy bus bars','现行','K11','29.060.10','2018-12-28','2019-07-01',NULL,'中国电器工业协会','中国电器工业协会','国家市场监督管理总局、中国国家标准化管理委员会','','https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=D70AA7B5DFC0D0845CD88820A94B5D24','ENABLED','2026-07-09 03:40:21','2026-07-09 03:40:21');
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_temper` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL,
  `code` varchar(64) NOT NULL DEFAULT '',
  `english_name` varchar(128) NOT NULL DEFAULT '',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_temper_name` (`name`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='产品状态/硬度';
/*!40101 SET character_set_client = @saved_cs_client */;

INSERT INTO `product_temper` (`id`, `name`, `code`, `english_name`, `status`, `created_at`, `updated_at`) VALUES (1,'硬态','H','Hard','ENABLED','2026-07-08 11:31:35','2026-07-09 03:55:43');
INSERT INTO `product_temper` (`id`, `name`, `code`, `english_name`, `status`, `created_at`, `updated_at`) VALUES (2,'半硬态','1/2H','Half-hard','ENABLED','2026-07-08 11:31:35','2026-07-09 03:55:43');
INSERT INTO `product_temper` (`id`, `name`, `code`, `english_name`, `status`, `created_at`, `updated_at`) VALUES (3,'软态','O','Annealed','ENABLED','2026-07-08 11:31:35','2026-07-09 03:55:43');
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `purchase_order` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `order_no` varchar(64) NOT NULL,
  `supplier_id` bigint unsigned NOT NULL,
  `business_date` date NOT NULL,
  `expected_date` date DEFAULT NULL,
  `source_type` varchar(32) NOT NULL DEFAULT 'FORMAL',
  `source_note` varchar(255) NOT NULL DEFAULT '',
  `original_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `discount_rate` decimal(6,2) NOT NULL DEFAULT '0.00',
  `discount_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `adjustment_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `total_quantity` decimal(18,3) NOT NULL DEFAULT '0.000',
  `received_quantity` decimal(18,3) NOT NULL DEFAULT '0.000',
  `total_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `paid_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `payment_status` varchar(16) NOT NULL DEFAULT 'UNPAID',
  `status` varchar(16) NOT NULL DEFAULT 'ORDERED',
  `memo` varchar(500) NOT NULL DEFAULT '',
  `cancel_reason` varchar(500) NOT NULL DEFAULT '',
  `cancelled_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_purchase_order_no` (`order_no`),
  KEY `idx_purchase_supplier` (`supplier_id`),
  KEY `idx_purchase_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商订货单';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `purchase_order_item` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `purchase_order_id` bigint unsigned NOT NULL,
  `product_id` bigint unsigned NOT NULL,
  `product_sku_snapshot` varchar(128) NOT NULL DEFAULT '',
  `product_name_snapshot` varchar(255) NOT NULL DEFAULT '',
  `product_spec_snapshot` varchar(1000) NOT NULL DEFAULT '',
  `requested_quantity` decimal(18,6) NOT NULL DEFAULT '0.000000',
  `requested_unit` varchar(64) NOT NULL DEFAULT '',
  `quantity` decimal(18,3) NOT NULL,
  `base_unit` varchar(64) NOT NULL DEFAULT '',
  `received_quantity` decimal(18,3) NOT NULL DEFAULT '0.000',
  `received_requested_quantity` decimal(24,6) NOT NULL DEFAULT '0.000000',
  `price_unit` varchar(64) NOT NULL DEFAULT '',
  `pricing_quantity` decimal(18,6) NOT NULL DEFAULT '0.000000',
  `package_unit` varchar(64) NOT NULL DEFAULT '',
  `packaging_plan_json` longtext NOT NULL,
  `packaging_effective_revision_id` bigint unsigned NOT NULL DEFAULT '0',
  `packaging_snapshot_hash` char(64) NOT NULL DEFAULT '',
  `unit_price` decimal(18,4) NOT NULL,
  `amount` decimal(18,2) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_purchase_item_order` (`purchase_order_id`),
  KEY `idx_purchase_item_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商订货明细';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `receivable_adjustment` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `adjustment_no` varchar(64) NOT NULL,
  `customer_id` bigint unsigned NOT NULL,
  `sales_order_id` bigint unsigned NOT NULL,
  `adjustment_type` varchar(32) NOT NULL COMMENT 'COMMERCIAL_DISCOUNT, BAD_DEBT, SMALL_WAIVER',
  `amount` decimal(18,2) NOT NULL,
  `occurred_date` date NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT, CONFIRMED, CANCELLED, REVERSED',
  `reason` varchar(500) NOT NULL DEFAULT '',
  `evidence_ref` varchar(500) NOT NULL DEFAULT '',
  `confirmed_at` timestamp NULL DEFAULT NULL,
  `reversal_no` varchar(64) NOT NULL DEFAULT '',
  `reversal_reason` varchar(500) NOT NULL DEFAULT '',
  `reversed_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_receivable_adjustment_no` (`adjustment_no`),
  KEY `idx_receivable_adjustment_order` (`sales_order_id`,`status`),
  KEY `idx_receivable_adjustment_customer` (`customer_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='应收调整单';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `receivable_movement` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `movement_no` varchar(64) NOT NULL,
  `customer_id` bigint unsigned NOT NULL,
  `ref_type` varchar(32) NOT NULL,
  `ref_id` bigint unsigned NOT NULL,
  `direction` varchar(16) NOT NULL COMMENT 'DEBIT increases receivable, CREDIT decreases receivable',
  `amount` decimal(18,2) NOT NULL,
  `balance_after` decimal(18,2) NOT NULL DEFAULT '0.00',
  `occurred_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `memo` varchar(500) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_receivable_movement_no` (`movement_no`),
  KEY `idx_receivable_customer` (`customer_id`),
  KEY `idx_receivable_ref` (`ref_type`,`ref_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='应收流水';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sales_order` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `order_no` varchar(64) NOT NULL,
  `title` varchar(255) NOT NULL DEFAULT '',
  `customer_id` bigint unsigned NOT NULL,
  `address_id` bigint unsigned NOT NULL DEFAULT '0',
  `address_snapshot` varchar(1000) NOT NULL DEFAULT '',
  `business_date` date NOT NULL,
  `delivery_plan_date` date DEFAULT NULL,
  `original_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `discount_rate` decimal(6,2) NOT NULL DEFAULT '0.00',
  `discount_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `adjustment_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `total_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `receivable_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `receivable_basis` varchar(24) NOT NULL DEFAULT 'SHIPMENT',
  `received_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `adjusted_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `rounded_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `payment_status` varchar(16) NOT NULL DEFAULT 'UNPAID',
  `payment_term` varchar(24) NOT NULL DEFAULT 'POSTPAID',
  `shipment_payment_policy` varchar(24) NOT NULL DEFAULT 'NONE',
  `deposit_mode` varchar(16) NOT NULL DEFAULT 'AMOUNT',
  `deposit_rate` decimal(6,2) NOT NULL DEFAULT '0.00',
  `required_deposit_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `status` varchar(16) NOT NULL DEFAULT 'DRAFT',
  `memo` varchar(500) NOT NULL DEFAULT '',
  `cancel_reason` varchar(500) NOT NULL DEFAULT '',
  `cancelled_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sales_order_no` (`order_no`),
  KEY `idx_sales_order_customer` (`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售订单';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sales_order_advance_allocation` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `sales_order_id` bigint unsigned NOT NULL,
  `customer_id` bigint unsigned NOT NULL,
  `source_payment_id` bigint unsigned NOT NULL DEFAULT '0',
  `amount` decimal(18,2) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'RESERVED' COMMENT 'RESERVED, APPLIED, RELEASED',
  `applied_payment_id` bigint unsigned NOT NULL DEFAULT '0',
  `memo` varchar(500) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_so_advance_order` (`sales_order_id`,`status`),
  KEY `idx_so_advance_customer` (`customer_id`,`status`),
  KEY `idx_so_advance_source_payment` (`source_payment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售订单客户预收款占用';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sales_order_item` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `sales_order_id` bigint unsigned NOT NULL,
  `product_id` bigint unsigned NOT NULL,
  `product_sku_snapshot` varchar(128) NOT NULL DEFAULT '',
  `product_name_snapshot` varchar(255) NOT NULL DEFAULT '',
  `product_spec_snapshot` varchar(1000) NOT NULL DEFAULT '',
  `requested_quantity` decimal(18,6) NOT NULL DEFAULT '0.000000',
  `requested_unit` varchar(64) NOT NULL DEFAULT '',
  `quantity` decimal(18,3) NOT NULL,
  `shipped_quantity` decimal(24,6) NOT NULL DEFAULT '0.000000',
  `shipped_requested_quantity` decimal(24,6) NOT NULL DEFAULT '0.000000',
  `base_unit` varchar(64) NOT NULL DEFAULT '',
  `price_unit` varchar(64) NOT NULL DEFAULT '',
  `pricing_quantity` decimal(18,6) NOT NULL DEFAULT '0.000000',
  `package_unit` varchar(64) NOT NULL DEFAULT '',
  `packaging_plan_json` longtext NOT NULL,
  `packaging_effective_revision_id` bigint unsigned NOT NULL DEFAULT '0',
  `packaging_snapshot_hash` char(64) NOT NULL DEFAULT '',
  `no_charge` tinyint unsigned NOT NULL DEFAULT '0',
  `unit_price` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_sales_order_item_order` (`sales_order_id`),
  KEY `idx_sales_order_item_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售订单明细';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sales_return_item` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `sales_return_id` bigint unsigned NOT NULL,
  `outbound_item_id` bigint unsigned NOT NULL,
  `product_id` bigint unsigned NOT NULL,
  `inventory_batch_id` bigint unsigned NOT NULL,
  `quantity` decimal(24,6) NOT NULL,
  `base_unit` varchar(64) NOT NULL DEFAULT '',
  `unit_price` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_sales_return_item_return` (`sales_return_id`),
  KEY `idx_sales_return_item_outbound` (`outbound_item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售退货明细';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sales_return_order` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `return_no` varchar(64) NOT NULL,
  `outbound_id` bigint unsigned NOT NULL,
  `sales_order_id` bigint unsigned NOT NULL DEFAULT '0',
  `customer_id` bigint unsigned NOT NULL,
  `warehouse_id` bigint unsigned NOT NULL,
  `business_date` date NOT NULL,
  `status` varchar(24) NOT NULL DEFAULT 'CONFIRMED',
  `total_quantity` decimal(24,6) NOT NULL DEFAULT '0.000000',
  `total_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `receivable_reduction` decimal(18,2) NOT NULL DEFAULT '0.00',
  `advance_credit_amount` decimal(18,2) NOT NULL DEFAULT '0.00',
  `reason` varchar(500) NOT NULL DEFAULT '',
  `memo` varchar(500) NOT NULL DEFAULT '',
  `confirmed_at` timestamp NULL DEFAULT NULL,
  `reversal_no` varchar(64) NOT NULL DEFAULT '',
  `reversal_reason` varchar(500) NOT NULL DEFAULT '',
  `reversed_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sales_return_no` (`return_no`),
  KEY `idx_sales_return_outbound` (`outbound_id`,`status`),
  KEY `idx_sales_return_customer` (`customer_id`,`business_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售退货单';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `scan_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `code` varchar(255) NOT NULL,
  `scan_type` varchar(32) NOT NULL DEFAULT 'INVENTORY',
  `scan_count` int unsigned NOT NULL DEFAULT '1',
  `dealt` tinyint unsigned NOT NULL DEFAULT '0',
  `dealt_at` timestamp NULL DEFAULT NULL,
  `result` varchar(500) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_scan_log_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='扫码记录';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `security_audit_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `operator_user_id` bigint unsigned NOT NULL DEFAULT '0',
  `operator_name` varchar(64) NOT NULL DEFAULT '系统',
  `action_code` varchar(64) NOT NULL,
  `target_type` varchar(32) NOT NULL,
  `target_id` bigint unsigned NOT NULL DEFAULT '0',
  `summary` varchar(500) NOT NULL DEFAULT '',
  `before_json` json DEFAULT NULL,
  `after_json` json DEFAULT NULL,
  `ip_address` varchar(64) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_security_audit_target` (`target_type`,`target_id`,`created_at`),
  KEY `idx_security_audit_operator` (`operator_user_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='组织权限审计日志';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `security_business_record_owner` (
  `resource_type` varchar(32) NOT NULL,
  `record_id` bigint unsigned NOT NULL,
  `owner_employee_id` bigint unsigned NOT NULL,
  `owner_department_id` bigint unsigned NOT NULL,
  `created_by_user_id` bigint unsigned NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`resource_type`,`record_id`),
  KEY `idx_business_owner_employee` (`resource_type`,`owner_employee_id`,`record_id`),
  KEY `idx_business_owner_department` (`resource_type`,`owner_department_id`,`record_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业务记录人员部门归属';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `security_permission` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `code` varchar(96) NOT NULL,
  `module_code` varchar(64) NOT NULL,
  `module_name` varchar(64) NOT NULL,
  `permission_type` varchar(16) NOT NULL DEFAULT 'ACTION',
  `name` varchar(64) NOT NULL,
  `description` varchar(255) NOT NULL DEFAULT '',
  `sort_no` int NOT NULL DEFAULT '0',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_permission_code` (`code`),
  KEY `idx_permission_module_sort` (`module_code`,`sort_no`)
) ENGINE=InnoDB AUTO_INCREMENT=39 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='权限点目录';
/*!40101 SET character_set_client = @saved_cs_client */;

INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (1,'dashboard.view','dashboard','工作台','MENU','查看工作台','查看经营概览与待办',10,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (2,'partner.view','partner','往来单位','MENU','查看往来单位','查看客户、供应商及联系人',20,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (3,'partner.manage','partner','往来单位','ACTION','维护往来单位','新增、修改、停用往来单位及联系人',21,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (4,'product.view','product','商品资料','MENU','查看商品','查看商品、规格、单位和包装',30,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (5,'product.manage','product','商品资料','ACTION','维护商品','新增、修改商品主数据及包装配置',31,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (6,'product.cost.view','product','商品资料','FIELD','查看成本价','查看商品采购价、成本和毛利基础数据',32,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (7,'warehouse.view','warehouse','仓库资料','MENU','查看仓库','查看仓库及收货信息',40,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (8,'warehouse.manage','warehouse','仓库资料','ACTION','维护仓库','新增、修改和停用仓库',41,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (9,'purchase.view','purchase','采购订单','MENU','查看采购订单','查看采购订单及到货进度',50,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (10,'purchase.manage','purchase','采购订单','ACTION','维护采购订单','新增和修改采购草稿',51,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (11,'purchase.confirm','purchase','采购订单','ACTION','确认/取消采购','确认采购订单及规范取消',52,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (12,'inbound.view','inbound','进货入库','MENU','查看入库','查看入库单、附件和审核记录',60,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (13,'inbound.manage','inbound','进货入库','ACTION','维护入库','新增、修改入库草稿及附件',61,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (14,'inbound.submit','inbound','进货入库','ACTION','提交/撤回入库','提交审核及撤回本人申请',62,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (15,'inbound.audit','inbound','进货入库','ACTION','审核入库','通过或驳回入库，影响库存与应付',63,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (16,'inventory.view','inventory','库存管理','MENU','查看库存','查看批次、数量及包装库存',70,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (17,'inventory.adjust','inventory','库存管理','ACTION','调整库存','执行盘点、调整和库存冲正',71,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (18,'inventory.cost.view','inventory','库存管理','FIELD','查看库存成本','查看批次成本与库存金额',72,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (19,'sales.view','sales','销售订单','MENU','查看销售订单','查看销售订单及发货进度',80,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (20,'sales.manage','sales','销售订单','ACTION','维护销售订单','新增和修改销售草稿',81,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (21,'sales.confirm','sales','销售订单','ACTION','确认/取消销售','确认销售订单及规范取消',82,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (22,'sales.gross-profit.view','sales','销售订单','FIELD','查看毛利','查看销售成本、毛利额和毛利率',83,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (23,'outbound.view','outbound','销售出库','MENU','查看出库','查看出库单、退货和追溯信息',90,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (24,'outbound.manage','outbound','销售出库','ACTION','维护出库','新增、修改、确认和结算出库',91,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (25,'outbound.reverse','outbound','销售出库','ACTION','冲正出库','执行出库冲正及销售退货冲正',92,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (26,'finance.view','finance','财务往来','MENU','查看财务','查看应收应付、流水、核销和票据',100,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (27,'finance.manage','finance','财务往来','ACTION','办理收付款','登记收付款、预收预付和核销',101,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (28,'finance.approve','finance','财务往来','ACTION','确认/冲正财务','确认调整、承兑处理、退款和冲正',102,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (29,'finance.sensitive.view','finance','财务往来','FIELD','查看敏感资金数据','查看账户余额、手续费和完整资金账号',103,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (30,'platform.view','platform','销售平台','MENU','查看平台货源','查看对外平台货源',110,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (31,'platform.manage','platform','销售平台','ACTION','维护平台货源','发布、修改和下架平台货源',111,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (32,'organization.view','organization','部门人员','MENU','查看部门人员','查看部门树和人员档案',120,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (33,'organization.manage','organization','部门人员','ACTION','维护部门人员','新增、调整、停用部门及办理人员离职',121,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (34,'user.view','user','用户管理','MENU','查看用户','查看登录用户、角色和登录状态',130,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (35,'user.manage','user','用户管理','ACTION','维护用户','授权登录、分配角色、重置密码和停用账号',131,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (36,'role.view','role','角色权限','MENU','查看角色权限','查看角色、权限点和数据范围',140,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (37,'role.manage','role','角色权限','ACTION','维护角色权限','新增、复制、修改和停用自定义角色',141,'ENABLED');
INSERT INTO `security_permission` (`id`, `code`, `module_code`, `module_name`, `permission_type`, `name`, `description`, `sort_no`, `status`) VALUES (38,'security.audit.view','security','安全审计','MENU','查看安全审计','查看登录、授权、离职和停用记录',150,'ENABLED');
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `security_role` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `code` varchar(64) NOT NULL,
  `name` varchar(64) NOT NULL,
  `description` varchar(255) NOT NULL DEFAULT '',
  `system_preset` tinyint unsigned NOT NULL DEFAULT '0',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `disabled_reason` varchar(255) NOT NULL DEFAULT '',
  `disabled_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_security_role_code` (`code`),
  UNIQUE KEY `uk_security_role_name` (`name`),
  KEY `idx_security_role_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='安全角色';
/*!40101 SET character_set_client = @saved_cs_client */;

INSERT INTO `security_role` (`id`, `code`, `name`, `description`, `system_preset`, `status`, `disabled_reason`, `disabled_at`, `created_at`, `updated_at`) VALUES (1,'ADMIN','系统管理员','维护系统、组织、用户、角色及全部业务权限',1,'ENABLED','',NULL,'2026-07-12 23:57:52','2026-07-12 23:57:52');
INSERT INTO `security_role` (`id`, `code`, `name`, `description`, `system_preset`, `status`, `disabled_reason`, `disabled_at`, `created_at`, `updated_at`) VALUES (2,'OWNER','老板','查看并处理全部经营业务和敏感经营数据',1,'ENABLED','',NULL,'2026-07-12 23:57:52','2026-07-12 23:57:52');
INSERT INTO `security_role` (`id`, `code`, `name`, `description`, `system_preset`, `status`, `disabled_reason`, `disabled_at`, `created_at`, `updated_at`) VALUES (3,'MANAGER','经营主管','管理本部门及下级部门的日常业务',1,'ENABLED','',NULL,'2026-07-12 23:57:52','2026-07-12 23:57:52');
INSERT INTO `security_role` (`id`, `code`, `name`, `description`, `system_preset`, `status`, `disabled_reason`, `disabled_at`, `created_at`, `updated_at`) VALUES (4,'SALES','销售','客户、销售、出库和回款业务',1,'ENABLED','',NULL,'2026-07-12 23:57:52','2026-07-12 23:57:52');
INSERT INTO `security_role` (`id`, `code`, `name`, `description`, `system_preset`, `status`, `disabled_reason`, `disabled_at`, `created_at`, `updated_at`) VALUES (5,'PURCHASE','采购','供应商、采购和入库业务',1,'ENABLED','',NULL,'2026-07-12 23:57:52','2026-07-12 23:57:52');
INSERT INTO `security_role` (`id`, `code`, `name`, `description`, `system_preset`, `status`, `disabled_reason`, `disabled_at`, `created_at`, `updated_at`) VALUES (6,'WAREHOUSE','库管','仓库、入库、出库和库存业务',1,'ENABLED','',NULL,'2026-07-12 23:57:52','2026-07-12 23:57:52');
INSERT INTO `security_role` (`id`, `code`, `name`, `description`, `system_preset`, `status`, `disabled_reason`, `disabled_at`, `created_at`, `updated_at`) VALUES (7,'FINANCE','财务','应收应付、收付款、票据、核销与对账',1,'ENABLED','',NULL,'2026-07-12 23:57:52','2026-07-12 23:57:52');
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `security_role_data_scope` (
  `role_id` bigint unsigned NOT NULL,
  `dimension` varchar(24) NOT NULL,
  `scope_mode` varchar(32) NOT NULL DEFAULT 'ALL',
  `selected_ids_json` json NOT NULL,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`role_id`,`dimension`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色数据范围';
/*!40101 SET character_set_client = @saved_cs_client */;

INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (1,'ACCOUNT','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (1,'DOCUMENT','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (1,'PARTNER','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (1,'PRODUCT','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (1,'WAREHOUSE','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (2,'ACCOUNT','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (2,'DOCUMENT','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (2,'PARTNER','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (2,'PRODUCT','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (2,'WAREHOUSE','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (3,'ACCOUNT','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (3,'DOCUMENT','DEPARTMENT_AND_CHILDREN','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (3,'PARTNER','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (3,'PRODUCT','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (3,'WAREHOUSE','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (4,'ACCOUNT','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (4,'DOCUMENT','SELF','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (4,'PARTNER','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (4,'PRODUCT','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (4,'WAREHOUSE','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (5,'ACCOUNT','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (5,'DOCUMENT','SELF','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (5,'PARTNER','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (5,'PRODUCT','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (5,'WAREHOUSE','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (6,'ACCOUNT','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (6,'DOCUMENT','SELF','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (6,'PARTNER','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (6,'PRODUCT','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (6,'WAREHOUSE','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (7,'ACCOUNT','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (7,'DOCUMENT','SELF','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (7,'PARTNER','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (7,'PRODUCT','ALL','[]','2026-07-12 23:57:52');
INSERT INTO `security_role_data_scope` (`role_id`, `dimension`, `scope_mode`, `selected_ids_json`, `updated_at`) VALUES (7,'WAREHOUSE','ALL','[]','2026-07-12 23:57:52');
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `security_role_permission` (
  `role_id` bigint unsigned NOT NULL,
  `permission_id` bigint unsigned NOT NULL,
  PRIMARY KEY (`role_id`,`permission_id`),
  KEY `idx_role_permission_permission` (`permission_id`,`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色功能权限';
/*!40101 SET character_set_client = @saved_cs_client */;

INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,1);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,2);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,3);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,4);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,5);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,6);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,7);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,8);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,9);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,10);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,11);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,12);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,13);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,14);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,15);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,16);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,17);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,18);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,19);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,20);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,21);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,22);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,23);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,24);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,25);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,26);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,27);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,28);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,29);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,30);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,31);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,32);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,33);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,34);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,35);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,36);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,37);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (1,38);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,1);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,2);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,3);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,4);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,5);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,6);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,7);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,8);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,9);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,10);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,11);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,12);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,13);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,14);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,15);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,16);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,17);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,18);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,19);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,20);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,21);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,22);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,23);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,24);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,25);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,26);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,27);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,28);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,29);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,30);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,31);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,32);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,33);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,34);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,35);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,36);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,37);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (2,38);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,1);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,2);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,3);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,4);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,6);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,7);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,9);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,10);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,11);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,12);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,13);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,14);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,15);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,16);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,18);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,19);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,20);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,21);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,22);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,23);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,24);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,26);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,32);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,34);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (3,36);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (4,1);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (4,2);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (4,3);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (4,4);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (4,7);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (4,16);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (4,19);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (4,20);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (4,21);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (4,23);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (4,24);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (4,26);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (5,1);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (5,2);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (5,3);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (5,4);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (5,6);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (5,7);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (5,9);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (5,10);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (5,11);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (5,12);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (5,13);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (5,14);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (5,16);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (6,1);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (6,4);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (6,7);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (6,12);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (6,13);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (6,14);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (6,16);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (6,17);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (6,23);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (6,24);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (7,1);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (7,2);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (7,4);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (7,6);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (7,7);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (7,9);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (7,12);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (7,15);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (7,16);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (7,18);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (7,19);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (7,22);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (7,23);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (7,26);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (7,27);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (7,28);
INSERT INTO `security_role_permission` (`role_id`, `permission_id`) VALUES (7,29);
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `stock_balance` (
  `product_id` bigint unsigned NOT NULL,
  `warehouse_id` bigint unsigned NOT NULL,
  `quantity_available` decimal(18,3) NOT NULL DEFAULT '0.000',
  `amount_cost` decimal(18,2) NOT NULL DEFAULT '0.00',
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`product_id`,`warehouse_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存余额汇总';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `supplier_payment` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `payment_no` varchar(64) NOT NULL,
  `supplier_id` bigint unsigned NOT NULL,
  `purchase_order_id` bigint unsigned NOT NULL DEFAULT '0',
  `inbound_id` bigint unsigned NOT NULL DEFAULT '0',
  `amount` decimal(18,2) NOT NULL,
  `payment_method` varchar(32) NOT NULL DEFAULT 'BANK',
  `account_type` varchar(32) NOT NULL DEFAULT '',
  `paid_date` date NOT NULL,
  `verification_status` varchar(16) NOT NULL DEFAULT 'VERIFIED',
  `memo` varchar(500) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_supplier_payment_no` (`payment_no`),
  KEY `idx_supplier_payment_supplier` (`supplier_id`),
  KEY `idx_supplier_payment_purchase` (`purchase_order_id`),
  KEY `idx_supplier_payment_inbound` (`inbound_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商付款记录';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `supplier_product_price` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `supplier_id` bigint unsigned NOT NULL,
  `product_id` bigint unsigned NOT NULL,
  `price_unit` varchar(64) NOT NULL DEFAULT '',
  `unit_price` decimal(18,4) NOT NULL DEFAULT '0.0000',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `memo` varchar(255) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_supplier_product_price` (`supplier_id`,`product_id`,`price_unit`),
  KEY `idx_supplier_product_price_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商商品报价';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_user` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `employee_id` bigint unsigned NOT NULL,
  `username` varchar(64) NOT NULL,
  `mobile` varchar(32) NOT NULL DEFAULT '',
  `password_hash` varchar(255) NOT NULL,
  `login_enabled` tinyint unsigned NOT NULL DEFAULT '1',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `password_change_required` tinyint unsigned NOT NULL DEFAULT '1',
  `failed_login_count` int unsigned NOT NULL DEFAULT '0',
  `locked_until` timestamp NULL DEFAULT NULL,
  `last_login_at` timestamp NULL DEFAULT NULL,
  `disabled_reason` varchar(255) NOT NULL DEFAULT '',
  `disabled_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_system_user_employee` (`employee_id`),
  UNIQUE KEY `uk_system_user_username` (`username`),
  KEY `idx_system_user_mobile` (`mobile`),
  KEY `idx_system_user_status` (`status`,`login_enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录用户';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_user_role` (
  `user_id` bigint unsigned NOT NULL,
  `role_id` bigint unsigned NOT NULL,
  `assigned_by` bigint unsigned NOT NULL DEFAULT '0',
  `assigned_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`,`role_id`),
  KEY `idx_user_role_role` (`role_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色关系';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_user_session` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint unsigned NOT NULL,
  `token_hash` char(64) NOT NULL,
  `expires_at` timestamp NOT NULL,
  `last_seen_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `ip_address` varchar(64) NOT NULL DEFAULT '',
  `user_agent` varchar(255) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_session_token` (`token_hash`),
  KEY `idx_user_session_user_expiry` (`user_id`,`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录会话';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `unit` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `unit_category_id` bigint unsigned NOT NULL,
  `name` varchar(64) NOT NULL,
  `abbreviation` varchar(32) NOT NULL DEFAULT '',
  `description` varchar(255) NOT NULL DEFAULT '',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_unit_category` (`unit_category_id`)
) ENGINE=InnoDB AUTO_INCREMENT=208 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='计量单位';
/*!40101 SET character_set_client = @saved_cs_client */;

INSERT INTO `unit` (`id`, `unit_category_id`, `name`, `abbreviation`, `description`, `status`, `created_at`, `updated_at`) VALUES (1,2,'千克','kg','默认重量单位','ENABLED','2026-07-08 10:38:03','2026-07-08 10:38:03');
INSERT INTO `unit` (`id`, `unit_category_id`, `name`, `abbreviation`, `description`, `status`, `created_at`, `updated_at`) VALUES (2,2,'吨','t','大宗铜材常用单位','ENABLED','2026-07-08 10:38:03','2026-07-08 10:38:03');
INSERT INTO `unit` (`id`, `unit_category_id`, `name`, `abbreviation`, `description`, `status`, `created_at`, `updated_at`) VALUES (3,3,'卷','卷','卷装','ENABLED','2026-07-08 10:38:03','2026-07-08 10:38:03');
INSERT INTO `unit` (`id`, `unit_category_id`, `name`, `abbreviation`, `description`, `status`, `created_at`, `updated_at`) VALUES (4,3,'箱','箱','箱装','ENABLED','2026-07-08 10:38:03','2026-07-08 10:38:03');
INSERT INTO `unit` (`id`, `unit_category_id`, `name`, `abbreviation`, `description`, `status`, `created_at`, `updated_at`) VALUES (5,1,'毫米','mm','规格尺寸','ENABLED','2026-07-08 10:38:03','2026-07-08 10:38:03');
INSERT INTO `unit` (`id`, `unit_category_id`, `name`, `abbreviation`, `description`, `status`, `created_at`, `updated_at`) VALUES (201,1,'米','m','长度单位','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `unit` (`id`, `unit_category_id`, `name`, `abbreviation`, `description`, `status`, `created_at`, `updated_at`) VALUES (202,3,'盘','盘','盘装/盘管','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `unit` (`id`, `unit_category_id`, `name`, `abbreviation`, `description`, `status`, `created_at`, `updated_at`) VALUES (203,3,'根','根','直管、棒材等根数','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `unit` (`id`, `unit_category_id`, `name`, `abbreviation`, `description`, `status`, `created_at`, `updated_at`) VALUES (204,3,'件','件','管件、分支器等计件','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `unit` (`id`, `unit_category_id`, `name`, `abbreviation`, `description`, `status`, `created_at`, `updated_at`) VALUES (205,3,'支','支','焊条、支装物料','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `unit` (`id`, `unit_category_id`, `name`, `abbreviation`, `description`, `status`, `created_at`, `updated_at`) VALUES (206,3,'托盘','托','托盘包装','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
INSERT INTO `unit` (`id`, `unit_category_id`, `name`, `abbreviation`, `description`, `status`, `created_at`, `updated_at`) VALUES (207,3,'包','包','袋装或包材包装','ENABLED','2026-07-08 15:19:21','2026-07-08 15:19:21');
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `unit_category` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL,
  `description` varchar(255) NOT NULL DEFAULT '',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_unit_category_name` (`name`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='计量分类';
/*!40101 SET character_set_client = @saved_cs_client */;

INSERT INTO `unit_category` (`id`, `name`, `description`, `status`, `created_at`, `updated_at`) VALUES (1,'长度','规格尺寸','ENABLED','2026-07-08 10:38:03','2026-07-08 10:38:03');
INSERT INTO `unit_category` (`id`, `name`, `description`, `status`, `created_at`, `updated_at`) VALUES (2,'重量','库存和计价重量','ENABLED','2026-07-08 10:38:03','2026-07-08 10:38:03');
INSERT INTO `unit_category` (`id`, `name`, `description`, `status`, `created_at`, `updated_at`) VALUES (3,'包装','箱、卷、包等包装单位','ENABLED','2026-07-08 10:38:03','2026-07-08 10:38:03');
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `warehouse` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(128) NOT NULL,
  `location` varchar(128) NOT NULL DEFAULT '',
  `receiving_addresses` text,
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_warehouse_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='仓库';
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

