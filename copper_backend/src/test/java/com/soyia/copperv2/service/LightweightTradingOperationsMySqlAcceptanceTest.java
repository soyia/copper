package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.InventoryDtos;
import com.soyia.copperv2.dto.EvidenceDtos;
import com.soyia.copperv2.dto.LightweightOperationsDtos;
import com.soyia.copperv2.dto.OperationalLiteDtos;
import com.soyia.copperv2.dto.ProductMasterDataDtos;
import com.soyia.copperv2.security.ForbiddenException;
import com.soyia.copperv2.security.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "COPPER_ERP_LITE_E2E", matches = "true")
@Transactional
class LightweightTradingOperationsMySqlAcceptanceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InventoryService inventoryService;
    @Autowired private EvidenceAttachmentService evidenceAttachmentService;
    @Autowired private LightweightOperationsService operationsService;
    @Autowired private ComplexProductMasterService productMasterService;
    @Autowired private OperationalLiteSuiteService operationalLiteSuiteService;

    private String token;
    private Long partnerId;
    private Long otherPartnerId;
    private Long productId;
    private Long warehouseId;
    private Long batchId;
    private Long purchaseOrderId;
    private Long salesOrderId;
    private Long otherSalesOrderId;
    private Long outboundId;
    private Long expenseId;

    @BeforeEach
    void setUp() {
        token = "LITE" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        SecurityContext.set(owner());
        createFixture();
    }

    @AfterEach
    void clearContext() {
        SecurityContext.clear();
    }

    @Test
    @DisplayName("小微商贸轻量能力复用真实库存销售费用和订单事实且全程可追溯")
    void lightweightPackKeepsFactsSimpleReversibleAndPartnerScoped() {
        Long nonTradeId = createAndConfirmNonTrade(new InventoryDtos.NonTradeCreate(
                batchId, "SAMPLE_OUT", LocalDate.now(), quantity("2"),
                "客户试样", "客户选型试样", "", List.of()));
        assertEquals("CONFIRMED", text(
                "select status from inventory_non_trade_document where id=?", nonTradeId));
        assertQuantity("98", decimal(
                "select quantity_available from inventory_batch where id=?", batchId));
        assertEquals(1L, count("""
                select count(*) from business_posting_batch
                where source_type='INVENTORY_NON_TRADE' and source_id=? and posting_action='POST'
                """, nonTradeId));
        inventoryService.reverseNonTrade(nonTradeId, "客户改用其他规格，退回试样");
        assertEquals("REVERSED", text(
                "select status from inventory_non_trade_document where id=?", nonTradeId));
        assertQuantity("100", decimal(
                "select quantity_available from inventory_batch where id=?", batchId));

        List<LightweightOperationsDtos.ReplenishmentRow> replenishment =
                operationsService.replenishment();
        LightweightOperationsDtos.ReplenishmentRow stock = replenishment.stream()
                .filter(row -> Objects.equals(row.productId(), productId)).findFirst().orElseThrow();
        assertEquals(200, stock.oldestBatchAgeDays());
        assertTrue(stock.explanation().contains("交期"));
        assertFalse(stock.explanation().contains("自动下单"));

        Long quoteId = operationsService.createQuote(purchaseOrderId,
                new LightweightOperationsDtos.QuoteSave(
                        partnerId, LocalDate.now(), true, money("5"), 3,
                        "到货后付款", "电话报价",
                        List.of(new LightweightOperationsDtos.QuoteItemSave(
                                productId, quantity("10"), "kg", price("9")))));
        operationsService.selectQuote(purchaseOrderId, quoteId);
        LightweightOperationsDtos.QuoteOption quote = operationsService.quotes(purchaseOrderId)
                .stream().filter(row -> Objects.equals(row.id(), quoteId)).findFirst().orElseThrow();
        assertEquals("SELECTED", quote.status());
        assertMoney("95", quote.totalAmount());
        assertEquals("DRAFT", text("select status from purchase_order where id=?", purchaseOrderId));

        Long allocationId = operationsService.allocateSalesCost(
                "OUTBOUND", outboundId,
                new LightweightOperationsDtos.SalesCostSave(
                        expenseId, money("20"), "本次送货费用"));
        LightweightOperationsDtos.ContributionRow contribution =
                operationsService.contributions(
                                LocalDate.now().minusDays(1), LocalDate.now(), "OUTBOUND", outboundId)
                        .get(0);
        assertMoney("200", contribution.revenue());
        assertMoney("120", contribution.inventoryCost());
        assertMoney("20", contribution.allocatedExpense());
        assertMoney("60", contribution.contributionMargin());
        assertMoney("20", decimal("select total_amount from treasury_document where id=?", expenseId));
        operationsService.reverseSalesCost(
                "OUTBOUND", outboundId, allocationId, "改由其他出库承担");
        assertEquals("REVERSED", text(
                "select status from sales_cost_allocation where id=?", allocationId));

        LightweightOperationsDtos.Delivery delivery = operationsService.saveDelivery(
                outboundId, new LightweightOperationsDtos.DeliverySave(
                        "OWN_DELIVERY", "", "", "老板本人", "随车送达"));
        assertEquals("PENDING", delivery.status());
        operationsService.actDelivery(outboundId,
                new LightweightOperationsDtos.DeliveryAction("DISPATCH", "", ""));
        delivery = operationsService.actDelivery(outboundId,
                new LightweightOperationsDtos.DeliveryAction("SIGN", "客户仓管", ""));
        assertEquals("SIGNED", delivery.status());
        assertEquals(List.of("CREATE", "DISPATCH", "SIGN"),
                delivery.events().stream().map(LightweightOperationsDtos.DeliveryEvent::action).toList());
        assertMoney("200", decimal(
                "select receivable_amount from outbound_order where id=?", outboundId));
        assertMoney("0", decimal("select paid_amount from outbound_order where id=?", outboundId));

        ProductMasterDataDtos.QualityCertificate certificate =
                productMasterService.saveQualityCertificate(
                        batchId, null, new ProductMasterDataDtos.QualityCertificateSave(
                                token + "-COA", "COA", partnerId, "验收铜厂",
                                token + "-BATCH", "", "", LocalDate.now(),
                                null, null, null, "ENABLED", List.of(), null));
        certificate = productMasterService.reviewQualityCertificate(
                batchId, certificate.id(), new ProductMasterDataDtos.QualityCertificateReviewSave(
                        "VERIFIED", "证号与批次一致", certificate.lockVersion()));
        assertEquals("VERIFIED", certificate.verificationStatus());
        assertEquals(1, productMasterService
                .qualityCertificateReviewHistory(batchId, certificate.id()).size());

        LightweightOperationsDtos.ShareCreated share = operationsService.createShare(
                new LightweightOperationsDtos.ShareCreate(
                        "SALES_ORDER", salesOrderId, LocalDateTime.now().plusDays(7)));
        assertNotEquals(share.token(), text(
                "select token_hash from external_share_link where id=?", share.share().id()));
        LightweightOperationsDtos.PublicOrder publicOrder =
                operationsService.publicSharedOrder(share.token());
        assertEquals(token + "-SO", publicOrder.sourceNo());
        assertEquals("", publicOrder.memo());
        assertEquals(1L, count(
                "select view_count from external_share_link where id=?", share.share().id()));

        LightweightOperationsDtos.ApiKeyCreated access = operationsService.createApiKey(
                new LightweightOperationsDtos.ApiKeyCreate(
                        "客户订单查询", partnerId, LocalDateTime.now().plusDays(30)));
        assertNotEquals(access.token(), text(
                "select token_hash from integration_access_key where id=?", access.key().id()));
        assertEquals(token + "-SO", operationsService.openApiOrder(
                access.token(), "SALES_ORDER", token + "-SO").sourceNo());
        assertThrows(ForbiddenException.class, () -> operationsService.openApiOrder(
                access.token(), "SALES_ORDER", token + "-OTHER-SO"));
        operationsService.revokeApiKey(access.key().id(), "客户停用接口");
        operationsService.revokeShare(share.share().id(), "订单沟通结束");
        assertEquals("REVOKED", text(
                "select status from integration_access_key where id=?", access.key().id()));
        assertEquals("REVOKED", text(
                "select status from external_share_link where id=?", share.share().id()));
    }

    @Test
    @DisplayName("实物发放立即扣库存，签字归档不得重复扣库")
    void nonTradeIssuePostsInventoryBeforeSignedReceiptArchive() {
        InventoryDtos.NonTradeCreate request = new InventoryDtos.NonTradeCreate(
                batchId, "SAMPLE_OUT", LocalDate.now(), quantity("2"),
                "实际领用人", "先拿货后补签字", "", List.of());
        Long documentId = inventoryService.createNonTradeDraft(request);
        assertEquals("DRAFT", text(
                "select status from inventory_non_trade_document where id=?", documentId));
        assertQuantity("100", decimal(
                "select quantity_available from inventory_batch where id=?", batchId));

        inventoryService.issueNonTrade(documentId);
        assertEquals("ISSUED_PENDING_SIGNATURE", text(
                "select status from inventory_non_trade_document where id=?", documentId));
        assertQuantity("98", decimal(
                "select quantity_available from inventory_batch where id=?", batchId));
        assertEquals(1L, count("""
                select count(*) from business_posting_batch
                where source_type='INVENTORY_NON_TRADE' and source_id=? and posting_action='POST'
                """, documentId));

        EvidenceDtos.AttachmentView signedReceipt = uploadSignedReceipt(documentId, request.recipient());
        inventoryService.confirmNonTrade(documentId, new InventoryDtos.NonTradeConfirm(signedReceipt.id()));
        assertEquals("CONFIRMED", text(
                "select status from inventory_non_trade_document where id=?", documentId));
        assertQuantity("98", decimal(
                "select quantity_available from inventory_batch where id=?", batchId));
        assertEquals(1L, count("""
                select count(*) from business_posting_batch
                where source_type='INVENTORY_NON_TRADE' and source_id=? and posting_action='POST'
                """, documentId));
    }

    @Test
    @DisplayName("一张领用单支持多商品逐项扣库并分离领用人经办人和制单人")
    void nonTradeDocumentSupportsMultipleIndependentItemsAndPeople() {
        jdbc.update("""
                insert into product(product_series_id,sku,name,spec,unit,status)
                values (2001,?,?,?,'件','ENABLED')
                """, token + "-SKU-2", token + " 铜接头", "固定件计量");
        Long secondProductId = id("select id from product where sku=?", token + "-SKU-2");
        jdbc.update("""
                insert into inventory_batch
                (product_id,warehouse_id,batch_no,source_type,source_id,base_unit,
                 packaging_profile_snapshot_json,quantity_in,quantity_available,
                 unit_cost,amount_cost,received_at,status)
                values (?,?,?,'OPENING',0,'件','{}',20,20,5,100,?,'AVAILABLE')
                """, secondProductId, warehouseId, token + "-BATCH-2", LocalDate.now());
        Long secondBatchId = id("select id from inventory_batch where batch_no=?", token + "-BATCH-2");
        jdbc.update("""
                insert into stock_balance(product_id,warehouse_id,quantity_available,amount_cost)
                values (?,?,20,100)
                """, secondProductId, warehouseId);

        Long documentId = createAndConfirmNonTrade(new InventoryDtos.NonTradeCreate(
                "INTERNAL_USE", LocalDate.now(), "仓库领用人", "业务经办人",
                "内部安装领用", "",
                List.of(
                        new InventoryDtos.NonTradeItemCreate(batchId, quantity("1.25"), List.of(), List.of()),
                        new InventoryDtos.NonTradeItemCreate(secondBatchId, quantity("2"), List.of(), List.of())
                )));
        assertEquals(2, jdbc.queryForObject(
                "select item_count from inventory_non_trade_document where id=?", Integer.class, documentId));
        assertEquals("仓库领用人", text(
                "select recipient from inventory_non_trade_document where id=?", documentId));
        assertEquals("业务经办人", text(
                "select handler_name from inventory_non_trade_document where id=?", documentId));
        assertEquals("轻量能力验收", text(
                "select created_by_name from inventory_non_trade_document where id=?", documentId));
        assertEquals(2L, count(
                "select count(*) from inventory_non_trade_item where document_id=?", documentId));
        assertQuantity("98.75", decimal(
                "select quantity_available from inventory_batch where id=?", batchId));
        assertQuantity("18", decimal(
                "select quantity_available from inventory_batch where id=?", secondBatchId));

        inventoryService.reverseNonTrade(documentId, "测试多商品冲正");
        assertQuantity("100", decimal(
                "select quantity_available from inventory_batch where id=?", batchId));
        assertQuantity("20", decimal(
                "select quantity_available from inventory_batch where id=?", secondBatchId));
    }

    @Test
    @DisplayName("送样可由用户逐包装分配扣取量并按原包装恢复冲正")
    void nonTradeSampleCanAllocateAcrossActualWeightPackages() {
        String snapshot = """
                {
                  "versionNo": 0,
                  "effectiveRevisionId": 0,
                  "contentHash": "actual-package-sample",
                  "source": {"sourceLevel": "SYSTEM_DEFAULT"},
                  "policy": {
                    "baseUnit": "kg",
                    "minimumSalesUnit": "kg",
                    "minimumOutboundUnit": "kg",
                    "defaultPurchaseUnit": "kg",
                    "defaultSalesUnit": "kg",
                    "roundingPolicy": "REJECT"
                  },
                  "rules": [{
                    "parentUnit": "盘",
                    "childUnit": "kg",
                    "conversionMode": "ACTUAL_WEIGHT",
                    "breakable": false,
                    "outboundAllowed": true,
                    "integerOnly": true,
                    "status": "ENABLED"
                  }]
                }
                """;
        jdbc.update("""
                update inventory_batch
                set quantity_in=54.96,quantity_available=54.96,amount_cost=549.60,
                    packaging_profile_snapshot_json=?,packaging_tracking_mode='ACTUAL_INSTANCE'
                where id=?
                """, snapshot, batchId);
        jdbc.update("""
                update stock_balance set quantity_available=54.96,amount_cost=549.60
                where product_id=? and warehouse_id=?
                """, productId, warehouseId);
        jdbc.update("""
                insert into inventory_package_balance(batch_id,product_id,unit_name,quantity)
                values (?,?, '盘',2),(?,?, 'kg',54.96)
                """, batchId, productId, batchId, productId);
        jdbc.update("""
                insert into inventory_package_instance
                (batch_id,product_id,source_instance_id,package_code,unit_name,
                 actual_base_quantity,remaining_base_quantity,base_unit,status)
                values (?,?,0,?,'盘',30.00,30.00,'kg','AVAILABLE'),
                       (?,?,0,?,'盘',24.96,24.96,'kg','AVAILABLE')
                """, batchId, productId, token + "-P01",
                batchId, productId, token + "-P02");
        Long firstPackageId = id("""
                select id from inventory_package_instance
                where batch_id=? and package_code=?
                """, batchId, token + "-P01");
        Long secondPackageId = id("""
                select id from inventory_package_instance
                where batch_id=? and package_code=?
                """, batchId, token + "-P02");

        Long documentId = createAndConfirmNonTrade(new InventoryDtos.NonTradeCreate(
                batchId, "SAMPLE_OUT", LocalDate.now(), quantity("0.10"),
                "客户试样", "客户选型试样", "", List.of(firstPackageId, secondPackageId),
                List.of(
                        new InventoryDtos.NonTradePackageAllocation(firstPackageId, quantity("0.04")),
                        new InventoryDtos.NonTradePackageAllocation(secondPackageId, quantity("0.06"))
                )));

        Long packagingRefId = id("""
                select packaging_ref_id from inventory_non_trade_item
                where document_id=? and line_no=1
                """, documentId);
        assertQuantity("54.86", decimal(
                "select quantity_available from inventory_batch where id=?", batchId));
        assertQuantity("30.00", decimal(
                "select actual_base_quantity from inventory_package_instance where id=?", firstPackageId));
        assertQuantity("29.96", decimal(
                "select remaining_base_quantity from inventory_package_instance where id=?", firstPackageId));
        assertQuantity("24.90", decimal(
                "select remaining_base_quantity from inventory_package_instance where id=?", secondPackageId));
        assertQuantity("54.86", decimal("""
                select quantity from inventory_package_balance
                where batch_id=? and unit_name='kg'
                """, batchId));
        assertQuantity("2", decimal("""
                select quantity from inventory_package_balance
                where batch_id=? and unit_name='盘'
                """, batchId));
        assertEquals("AVAILABLE", text(
                "select status from inventory_package_instance where id=?", firstPackageId));
        assertQuantity("0.10", decimal("""
                select sum(base_quantity) from inventory_package_instance_usage
                where ref_type='INVENTORY_NON_TRADE' and ref_id=?
                """, packagingRefId));
        assertEquals(2, jdbc.queryForObject("""
                select count(*) from inventory_package_instance_usage
                where ref_type='INVENTORY_NON_TRADE' and ref_id=?
                """, Integer.class, packagingRefId));
        assertTrue(text("""
                select selected_package_allocations_json
                from inventory_non_trade_document where id=?
                """, documentId).contains("\"quantity\":0.04"));

        inventoryService.reverseNonTrade(documentId, "样品按原来源盘退回");

        assertQuantity("54.96", decimal(
                "select quantity_available from inventory_batch where id=?", batchId));
        assertQuantity("30.00", decimal(
                "select remaining_base_quantity from inventory_package_instance where id=?", firstPackageId));
        assertQuantity("24.96", decimal(
                "select remaining_base_quantity from inventory_package_instance where id=?", secondPackageId));
        assertQuantity("54.96", decimal("""
                select quantity from inventory_package_balance
                where batch_id=? and unit_name='kg'
                """, batchId));
        assertEquals(2, jdbc.queryForObject("""
                select count(*) from inventory_package_instance_usage
                where ref_type='INVENTORY_NON_TRADE' and ref_id=? and status='REVERSED'
                """, Integer.class, packagingRefId));
        assertEquals("REVERSED", text("""
                select status from inventory_package_instance_usage
                where ref_type='INVENTORY_NON_TRADE' and ref_id=?
                limit 1
                """, packagingRefId));
    }

    @Test
    @DisplayName("跟催现金窗口外部确认和单据候选不越权改写库存资金与核销事实")
    void operationalLiteSuiteKeepsSuggestionsAndExternalConfirmationOutsideFormalPosting() {
        jdbc.update("update purchase_order set status='ORDERED' where id=?", purchaseOrderId);
        Long purchaseItemId = id("select id from purchase_order_item where purchase_order_id=? limit 1", purchaseOrderId);
        BigDecimal stockBefore = decimal("select quantity_available from stock_balance where product_id=? and warehouse_id=?", productId, warehouseId);
        BigDecimal payableBefore = decimal("select paid_amount from purchase_order where id=?", purchaseOrderId);
        OperationalLiteDtos.FollowupSummary followup = operationalLiteSuiteService.addPurchaseFollowup(
                purchaseOrderId, new OperationalLiteDtos.FollowupEventSave(
                        "SUPPLIER_DISPATCHED", LocalDate.now(), LocalDate.now().plusDays(2),
                        "测试物流", token + "-TRACK", "", "",
                        List.of(new OperationalLiteDtos.FollowupItemSave(purchaseItemId, quantity("4")))));
        assertEquals("IN_TRANSIT", followup.fulfillmentStatus());
        assertQuantity("4", followup.dispatchedQuantity());
        assertEquals(stockBefore, decimal("select quantity_available from stock_balance where product_id=? and warehouse_id=?", productId, warehouseId));
        assertEquals(payableBefore, decimal("select paid_amount from purchase_order where id=?", purchaseOrderId));

        OperationalLiteDtos.CashWindow cash = operationalLiteSuiteService.cashWindow();
        assertEquals(30, cash.days().size());
        assertTrue(cash.formula().contains("应收只是预计流入"));
        assertTrue(cash.sources().stream().anyMatch(row -> Objects.equals(row.sourceId(), salesOrderId)));

        OperationalLiteDtos.ConfirmationPacketCreated packet =
                operationalLiteSuiteService.createConfirmationPacket(
                        new OperationalLiteDtos.ConfirmationPacketCreate(
                                "CUSTOMER_STATEMENT", partnerId, LocalDate.now(),
                                LocalDateTime.now().plusDays(3)));
        assertNotEquals(packet.token(), text(
                "select token_hash from external_confirmation_packet where id=?", packet.packet().id()));
        BigDecimal receivedBefore = decimal("select received_amount from sales_order where id=?", salesOrderId);
        OperationalLiteDtos.PublicConfirmationPacket confirmed =
                operationalLiteSuiteService.confirmPublic(packet.token(),
                        new OperationalLiteDtos.PublicConfirmationSave(
                                "CONFIRMED", "客户财务", "1234", "余额无误"));
        assertEquals("CONFIRMED", confirmed.status());
        assertEquals(receivedBefore, decimal("select received_amount from sales_order where id=?", salesOrderId));
        assertEquals(0L, count("select count(*) from payment_allocation where target_type='SALES_ORDER' and target_id=?", salesOrderId));

        MockMultipartFile quoteFile = new MockMultipartFile(
                "file", token + "-quote.txt", "text/plain",
                ("供应商：" + token + " 往来单位\n报价单号：" + token + "-Q1\n"
                        + "报价日期：" + LocalDate.now() + "\n价税合计：1250.00\n交期：3天").getBytes());
        OperationalLiteDtos.IntakeBatch intake = operationalLiteSuiteService.createIntake(
                "SUPPLIER_QUOTE", "", quoteFile);
        assertEquals("REVIEW_REQUIRED", intake.status());
        assertTrue(intake.candidates().stream().anyMatch(
                row -> "total_amount".equals(row.fieldCode()) && row.candidateValue().contains("1250")));
        intake = operationalLiteSuiteService.reviewIntake(
                intake.id(), new OperationalLiteDtos.IntakeReview(
                        "CONFIRMED", "已对照原件核对，只保留候选",
                        intake.candidates().stream().map(row ->
                                new OperationalLiteDtos.IntakeCandidateReview(
                                        row.fieldCode(), row.candidateValue())).toList()));
        assertEquals("CONFIRMED", intake.status());
        assertEquals(0L, count("select count(*) from purchase_quote_option where source_note like ?", "%" + token + "-Q1%"));
        assertEquals(stockBefore, decimal("select quantity_available from stock_balance where product_id=? and warehouse_id=?", productId, warehouseId));
    }

    private void createFixture() {
        LocalDate today = LocalDate.now();
        LocalDate receivedAt = today.minusDays(200);
        jdbc.update("""
                insert into partner(business_no,type,type_mask,name,status)
                values (?,'CUSTOMER',3,?,'ENABLED')
                """, numericToken(71), token + " 往来单位");
        partnerId = id("select id from partner where name=?", token + " 往来单位");
        jdbc.update("""
                insert into partner_supplier_profile(partner_id,status)
                values (?,'ENABLED')
                """, partnerId);
        jdbc.update("""
                insert into partner(business_no,type,type_mask,name,status)
                values (?,'CUSTOMER',1,?,'ENABLED')
                """, numericToken(83), token + " 其他客户");
        otherPartnerId = id("select id from partner where name=?", token + " 其他客户");
        jdbc.update("""
                insert into warehouse(name,location,status) values (?,?,'ENABLED')
                """, token + " 仓", "隔离验收");
        warehouseId = id("select id from warehouse where name=?", token + " 仓");
        jdbc.update("""
                insert into product(product_series_id,sku,name,spec,unit,status)
                values (2001,?,?,?,'kg','ENABLED')
                """, token + "-SKU", token + " 紫铜管", "TP2 Φ22×1.0");
        productId = id("select id from product where sku=?", token + "-SKU");
        jdbc.update("""
                insert into inventory_batch
                (product_id,warehouse_id,batch_no,source_type,source_id,base_unit,
                 packaging_profile_snapshot_json,quantity_in,quantity_available,
                 unit_cost,amount_cost,received_at,status)
                values (?,?,?,'OPENING',0,'kg','{}',100,100,10,1000,?,'AVAILABLE')
                """, productId, warehouseId, token + "-BATCH", receivedAt);
        batchId = id("select id from inventory_batch where batch_no=?", token + "-BATCH");
        jdbc.update("""
                insert into stock_balance(product_id,warehouse_id,quantity_available,amount_cost)
                values (?,?,100,1000)
                """, productId, warehouseId);

        jdbc.update("""
                insert into purchase_order
                (order_no,supplier_id,business_date,expected_date,expected_warehouse_id,
                 settlement_days_snapshot,due_date,status,total_quantity,total_amount,memo)
                values (?,?,?, ?,?,30,?,'DRAFT',10,110,?)
                """, token + "-PO", partnerId, today, today.plusDays(3), warehouseId,
                today.plusDays(30), token);
        purchaseOrderId = id("select id from purchase_order where order_no=?", token + "-PO");
        jdbc.update("""
                insert into purchase_order_item
                (purchase_order_id,product_id,product_sku_snapshot,product_name_snapshot,
                 product_spec_snapshot,requested_quantity,requested_unit,quantity,base_unit,
                 price_unit,pricing_quantity,packaging_plan_json,unit_price,amount,status)
                values (?,?,?,?,?,10,'kg',10,'kg','kg',10,'{}',11,110,'ENABLED')
                """, purchaseOrderId, productId, token + "-SKU", token + " 紫铜管", "TP2 Φ22×1.0");

        jdbc.update("""
                insert into sales_order
                (order_no,title,customer_id,business_date,price_includes_tax,
                 settlement_days_snapshot,due_date,total_amount,receivable_amount,
                 received_amount,status,memo)
                values (?,?,?, ?,1,0,?,200,200,50,'CONFIRMED',?)
                """, token + "-SO", "隔离销售", partnerId, today, today.minusDays(1), token);
        salesOrderId = id("select id from sales_order where order_no=?", token + "-SO");
        jdbc.update("""
                insert into sales_order_item
                (sales_order_id,product_id,product_sku_snapshot,product_name_snapshot,
                 product_spec_snapshot,requested_quantity,requested_unit,quantity,base_unit,
                 price_unit,pricing_quantity,packaging_plan_json,unit_price,amount,status)
                values (?,?,?,?,?,10,'kg',10,'kg','kg',10,'{}',20,200,'ENABLED')
                """, salesOrderId, productId, token + "-SKU", token + " 紫铜管", "TP2 Φ22×1.0");

        jdbc.update("""
                insert into sales_order
                (order_no,title,customer_id,business_date,price_includes_tax,
                 settlement_days_snapshot,due_date,total_amount,receivable_amount,status)
                values (?,?,?, ?,1,0,?,50,50,'CONFIRMED')
                """, token + "-OTHER-SO", "其他客户销售", otherPartnerId, today, today);
        otherSalesOrderId = id(
                "select id from sales_order where order_no=?", token + "-OTHER-SO");

        jdbc.update("""
                insert into outbound_order
                (order_no,sales_order_id,source_type,customer_id,warehouse_id,business_date,
                 price_includes_tax,settlement_days_snapshot,due_date,status,total_quantity,
                 total_amount,receivable_amount,paid_amount,memo)
                values (?,0,'DIRECT',?,?,?,1,0,?,'SHIPPED',60,200,200,0,?)
                """, token + "-OUT", partnerId, warehouseId, today, today.minusDays(1), token);
        outboundId = id("select id from outbound_order where order_no=?", token + "-OUT");
        jdbc.update("""
                insert into outbound_item
                (outbound_id,product_id,product_sku_snapshot,product_name_snapshot,
                 product_spec_snapshot,requested_quantity,requested_unit,batch_id,quantity,
                 base_unit,packaging_plan_json,price_unit,pricing_quantity,unit_price,
                 inventory_unit_cost,inventory_amount_cost,amount)
                values (?,?,?,?,?,60,'kg',?,60,'kg','{}','kg',60,3.33333333,2,120,200)
                """, outboundId, productId, token + "-SKU", token + " 紫铜管",
                "TP2 Φ22×1.0", batchId);

        jdbc.update("""
                insert into treasury_document
                (document_no,document_type,partner_id,partner_name_snapshot,business_date,due_date,
                 basis_no,summary,offset_account_id,offset_account_code_snapshot,
                 offset_account_name_snapshot,amount_excluding_tax,tax_amount,total_amount,status,
                 confirmed_by,confirmed_by_name,confirmed_at)
                values (?,'CORPORATE_EXPENSE',?,?,?, ?,?,'本次送货费',0,'6601','销售费用',
                        20,0,20,'CONFIRMED',1,'隔离验收',now())
                """, token + "-EXP", partnerId, token + " 往来单位", today, today,
                token + "-BASIS");
        expenseId = id("select id from treasury_document where document_no=?", token + "-EXP");
    }

    private SecurityContext.Principal owner() {
        return new SecurityContext.Principal(
                1L, 1L, "轻量能力验收", "ERP-LITE", 1L, "本企业", token,
                false, List.of("系统管理员"), Set.of(
                "inventory.view", "inventory.cost.view", "inventory.nontrade.view",
                "inventory.nontrade.manage", "operations.insight.view",
                "purchase.view", "purchase.manage", "purchase.quote.manage",
                "sales.view", "sales.manage", "sales.cost.manage",
                "outbound.view", "outbound.manage", "outbound.delivery.manage",
                "inventory.quality.view", "inventory.quality.manage",
                "external.share.manage", "integration.key.manage"));
    }

    private Long createAndConfirmNonTrade(InventoryDtos.NonTradeCreate request) {
        Long documentId = inventoryService.createNonTradeDraft(request);
        inventoryService.issueNonTrade(documentId);
        EvidenceDtos.AttachmentView signedReceipt = uploadSignedReceipt(documentId, request.recipient());
        return inventoryService.confirmNonTrade(documentId,
                new InventoryDtos.NonTradeConfirm(signedReceipt.id()));
    }

    private EvidenceDtos.AttachmentView uploadSignedReceipt(Long documentId, String recipient) {
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2nfsAAAAASUVORK5CYII=");
        return evidenceAttachmentService.upload(
                "INVENTORY_NON_TRADE", documentId, "SIGNED_RECEIPT", "SCAN",
                recipient + "签字的领用单",
                new MockMultipartFile("file", token + "-" + documentId + "-signed.png", "image/png", png));
    }

    private String numericToken(int salt) {
        return String.format("%06d", 100000 + Math.floorMod(token.hashCode() + salt, 899999));
    }
    private Long id(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }
    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }
    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }
    private BigDecimal decimal(String sql, Object... args) {
        return jdbc.queryForObject(sql, BigDecimal.class, args);
    }
    private BigDecimal money(String value) { return new BigDecimal(value).setScale(2); }
    private BigDecimal price(String value) { return new BigDecimal(value).setScale(8); }
    private BigDecimal quantity(String value) { return new BigDecimal(value).setScale(6); }
    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
    private void assertQuantity(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    @AfterTransaction
    void rollbackRemovesFixture() {
        if (token == null) return;
        assertEquals(0L, count("select count(*) from partner where name like ?", token + "%"));
        assertEquals(0L, count("select count(*) from product where sku=?", token + "-SKU"));
        assertEquals(0L, count("select count(*) from warehouse where name=?", token + " 仓"));
        assertEquals(0L, count("select count(*) from external_share_link where share_no like ?", token + "%"));
        assertEquals(0L, count("select count(*) from integration_access_key where key_name='客户订单查询'"));
    }
}
