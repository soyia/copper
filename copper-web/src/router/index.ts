import { createRouter, createWebHistory } from 'vue-router'
import Dashboard from '@/views/Dashboard.vue'
import HelpCenter from '@/views/HelpCenter.vue'
import Partners from '@/views/Partners.vue'
import PartnerDetail from '@/views/PartnerDetail.vue'
import Products from '@/views/Products.vue'
import ProductImport from '@/views/ProductImport.vue'
import ProductMatrixCreate from '@/views/ProductMatrixCreate.vue'
import ProductMasterDetail from '@/views/ProductMasterDetail.vue'
import ProductSeries from '@/views/ProductSeries.vue'
import ProductFields from '@/views/ProductFields.vue'
import ProductPackaging from '@/views/ProductPackaging.vue'
import PackagingTemplateDetail from '@/views/PackagingTemplateDetail.vue'
import PriceCenter from '@/views/PriceCenter.vue'
import Units from '@/views/Units.vue'
import Warehouses from '@/views/Warehouses.vue'
import PurchaseOrders from '@/views/PurchaseOrders.vue'
import PurchaseOrderCreate from '@/views/PurchaseOrderCreate.vue'
import PurchaseOrderDetail from '@/views/PurchaseOrderDetail.vue'
import SalesOrders from '@/views/SalesOrders.vue'
import SalesOrderCreate from '@/views/SalesOrderCreate.vue'
import SalesOrderDetail from '@/views/SalesOrderDetail.vue'
import DirectTrades from '@/views/DirectTrades.vue'
import DirectTradeCreate from '@/views/DirectTradeCreate.vue'
import DirectTradeDetail from '@/views/DirectTradeDetail.vue'
import Inventory from '@/views/Inventory.vue'
import InventoryNonTradeDocuments from '@/views/InventoryNonTradeDocuments.vue'
import InventoryNonTradeCreate from '@/views/InventoryNonTradeCreate.vue'
import InventoryNonTradeDetail from '@/views/InventoryNonTradeDetail.vue'
import InventoryDocuments from '@/views/InventoryDocuments.vue'
import InventoryDocumentCreate from '@/views/InventoryDocumentCreate.vue'
import InventoryDocumentDetail from '@/views/InventoryDocumentDetail.vue'
import InventoryMovements from '@/views/InventoryMovements.vue'
import InventoryReconciliation from '@/views/InventoryReconciliation.vue'
import InventoryAtp from '@/views/InventoryAtp.vue'
import LandedCostAllocations from '@/views/LandedCostAllocations.vue'
import LandedCostAllocationEdit from '@/views/LandedCostAllocationEdit.vue'
import LandedCostAllocationDetail from '@/views/LandedCostAllocationDetail.vue'
import Inbounds from '@/views/Inbounds.vue'
import InboundCreate from '@/views/InboundCreate.vue'
import InboundDetail from '@/views/InboundDetail.vue'
import InboundReview from '@/views/InboundReview.vue'
import InboundAuditLogDetail from '@/views/InboundAuditLogDetail.vue'
import InboundAuditLogs from '@/views/InboundAuditLogs.vue'
import Outbounds from '@/views/Outbounds.vue'
import OutboundCreate from '@/views/OutboundCreate.vue'
import OutboundDetail from '@/views/OutboundDetail.vue'
import Payments from '@/views/Payments.vue'
import CustomerAdvances from '@/views/CustomerAdvances.vue'
import ReceivableAdjustments from '@/views/ReceivableAdjustments.vue'
import AcceptanceBills from '@/views/AcceptanceBills.vue'
import PaymentCreate from '@/views/PaymentCreate.vue'
import SupplierPayments from '@/views/SupplierPayments.vue'
import SupplierPaymentCreate from '@/views/SupplierPaymentCreate.vue'
import Payables from '@/views/Payables.vue'
import Receivables from '@/views/Receivables.vue'
import PartnerFundAdjustments from '@/views/PartnerFundAdjustments.vue'
import PartnerFundAdjustmentCreate from '@/views/PartnerFundAdjustmentCreate.vue'
import PartnerFundAdjustmentDetail from '@/views/PartnerFundAdjustmentDetail.vue'
import TreasuryDocuments from '@/views/TreasuryDocuments.vue'
import TreasuryDocumentDetail from '@/views/TreasuryDocumentDetail.vue'
import TreasuryJournal from '@/views/TreasuryJournal.vue'
import PlatformListings from '@/views/PlatformListings.vue'
import Settings from '@/views/Settings.vue'
import Login from '@/views/Login.vue'
import OrganizationPeople from '@/views/OrganizationPeople.vue'
import SystemUsers from '@/views/SystemUsers.vue'
import RolePermissions from '@/views/RolePermissions.vue'
import BusinessExports from '@/views/BusinessExports.vue'
import StorageCapacity from '@/views/StorageCapacity.vue'
import SecurityAudit from '@/views/SecurityAudit.vue'
import BusinessPostings from '@/views/BusinessPostings.vue'
import BusinessPostingDetail from '@/views/BusinessPostingDetail.vue'
import AccountingDashboard from '@/views/AccountingDashboard.vue'
import AccountingVouchers from '@/views/AccountingVouchers.vue'
import AccountingVoucherEdit from '@/views/AccountingVoucherEdit.vue'
import AccountingVoucherDetail from '@/views/AccountingVoucherDetail.vue'
import AccountingMasterData from '@/views/AccountingMasterData.vue'
import AccountingRules from '@/views/AccountingRules.vue'
import AccountingLedger from '@/views/AccountingLedger.vue'
import FinancialReports from '@/views/FinancialReports.vue'
import BankReconciliations from '@/views/BankReconciliations.vue'
import PeriodClose from '@/views/PeriodClose.vue'
import TaxInvoiceDashboard from '@/views/TaxInvoiceDashboard.vue'
import TaxInvoices from '@/views/TaxInvoices.vue'
import TaxInvoiceEdit from '@/views/TaxInvoiceEdit.vue'
import TaxInvoiceDetail from '@/views/TaxInvoiceDetail.vue'
import TaxCompliance from '@/views/TaxCompliance.vue'
import ElectronicCredentialDetail from '@/views/ElectronicCredentialDetail.vue'
import TaxWorkpaperDetail from '@/views/TaxWorkpaperDetail.vue'
import CrmDashboard from '@/views/CrmDashboard.vue'
import CrmLeads from '@/views/CrmLeads.vue'
import CrmLeadDetail from '@/views/CrmLeadDetail.vue'
import CrmCustomers from '@/views/CrmCustomers.vue'
import CrmCustomerDetail from '@/views/CrmCustomerDetail.vue'
import CrmOpportunities from '@/views/CrmOpportunities.vue'
import CrmOpportunityDetail from '@/views/CrmOpportunityDetail.vue'
import CrmQuotes from '@/views/CrmQuotes.vue'
import CrmQuoteEdit from '@/views/CrmQuoteEdit.vue'
import CrmQuoteDetail from '@/views/CrmQuoteDetail.vue'
import CrmActivities from '@/views/CrmActivities.vue'
import RiskExceptions from '@/views/RiskExceptions.vue'
import RiskExceptionDetail from '@/views/RiskExceptionDetail.vue'
import DataMigrations from '@/views/DataMigrations.vue'
import OperationsAssistant from '@/views/OperationsAssistant.vue'
import PublicOrderShare from '@/views/PublicOrderShare.vue'
import PublicConfirmation from '@/views/PublicConfirmation.vue'
import MobileWorkbench from '@/views/mobile/MobileWorkbench.vue'
import MobileStockSearch from '@/views/mobile/MobileStockSearch.vue'
import MobileQuickSale from '@/views/mobile/MobileQuickSale.vue'
import MobilePayment from '@/views/mobile/MobilePayment.vue'
import MobileStocktake from '@/views/mobile/MobileStocktake.vue'
import MobileEvidence from '@/views/mobile/MobileEvidence.vue'
import { resolvePostLoginPath } from '@/utils/mobileNavigation'
import { useAppStore } from '@/stores/app'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/login', component: Login, meta: { title: '登录', public: true } },
    { path: '/public/order-shares/:token', component: PublicOrderShare, meta: { title: '订单分享', public: true } },
    { path: '/public/confirmations/:token', component: PublicConfirmation, meta: { title: '外部业务确认', public: true } },
    { path: '/mobile', component: MobileWorkbench, meta: { title: '手机办事', mobile: true } },
    { path: '/mobile/stock', component: MobileStockSearch, meta: { title: '查货', mobile: true, permission: 'inventory.view' } },
    { path: '/mobile/quote-order', component: MobileQuickSale, meta: { title: '报价 / 下单', mobile: true, permissionsAny: ['crm.record.manage', 'sales.manage'] } },
    { path: '/mobile/payment', component: MobilePayment, meta: { title: '收款', mobile: true, permission: 'finance.view' } },
    { path: '/mobile/stocktake', component: MobileStocktake, meta: { title: '扫码盘点', mobile: true, permission: 'inventory.stocktake' } },
    { path: '/mobile/evidence', component: MobileEvidence, meta: { title: '拍照归单', mobile: true, permission: 'evidence.manage' } },
    { path: '/dashboard', component: Dashboard, meta: { title: '工作台' } },
    { path: '/help', component: HelpCenter, meta: { title: '帮助中心' } },
    { path: '/operations-assistant', component: OperationsAssistant, meta: { title: '经营助手', permission: 'operations.insight.view' } },
    { path: '/risk-exceptions', component: RiskExceptions, meta: { title: '例外审批', permission: 'risk.exception.view' } },
    { path: '/risk-exceptions/:id', component: RiskExceptionDetail, meta: { title: '例外审批详情', titleParam: 'id', permission: 'risk.exception.view' } },
    { path: '/settings', component: Settings, meta: { title: '设置' } },
    { path: '/partners', component: Partners, meta: { title: '往来单位' } },
    { path: '/partners/:id', component: PartnerDetail, meta: { title: '往来单位详情' } },
    { path: '/product-series', redirect: '/product-catalog' },
    { path: '/product-catalog', component: ProductSeries, props: { section: 'catalog' }, meta: { title: '分类与系列' } },
    { path: '/product-fields', component: ProductFields, meta: { title: '规格字段库' } },
    { path: '/product-specs', redirect: '/product-catalog' },
    { path: '/product-dictionaries', redirect: '/product-catalog' },
    { path: '/product-standards', redirect: '/product-catalog' },
    { path: '/product-tempers', redirect: '/product-catalog' },
    { path: '/products', component: Products, meta: { title: '商品资料' } },
    { path: '/products/import', component: ProductImport, meta: { title: '商品批量导入', permissionsAny: ['product.master.complex.view', 'product.master.complex.manage'] } },
    { path: '/products/matrix-create', component: ProductMatrixCreate, meta: { title: '商品矩阵建档', permissionsAll: ['product.manage', 'product.master.complex.manage'] } },
    { path: '/products/:id', component: ProductMasterDetail, meta: { title: '商品主数据', titleParam: 'id', permissionsAll: ['product.view', 'product.master.complex.view'] } },
    { path: '/product-identity-schemas', redirect: '/product-catalog' },
    { path: '/product-packaging', component: ProductPackaging, meta: { title: '计量与包装' } },
    { path: '/product-packaging/templates/:id', component: PackagingTemplateDetail, meta: { title: '包装模板详情', titleParam: 'id', permission: 'product.view' } },
    { path: '/price-center', component: PriceCenter, meta: { title: '铜材价格中心', permission: 'pricing.view' } },
    { path: '/units', component: Units, meta: { title: '计量单位' } },
    { path: '/warehouses', component: Warehouses, meta: { title: '仓库资料' } },
    { path: '/purchase-orders', name: 'purchase-orders', component: PurchaseOrders, meta: { title: '采购订单' } },
    { path: '/purchase-orders/new', name: 'purchase-order-new', component: PurchaseOrderCreate, meta: { title: '新建采购订单' } },
    { path: '/purchase-orders/:id/edit', name: 'purchase-order-edit', component: PurchaseOrderCreate, meta: { title: '编辑采购订单' } },
    { path: '/purchase-orders/:id/amend', name: 'purchase-order-amend', component: PurchaseOrderCreate, meta: { title: '变更采购订单', permission: 'purchase.amend' } },
    { path: '/purchase-orders/:id', name: 'purchase-order-detail', component: PurchaseOrderDetail, meta: { title: '采购订单详情' } },
    { path: '/sales-orders', name: 'sales-orders', component: SalesOrders, meta: { title: '销售订单' } },
    { path: '/sales-orders/new', name: 'sales-order-new', component: SalesOrderCreate, meta: { title: '新建销售订单' } },
    { path: '/sales-orders/:id/edit', name: 'sales-order-edit', component: SalesOrderCreate, meta: { title: '编辑销售订单' } },
    { path: '/sales-orders/:id/amend', name: 'sales-order-amend', component: SalesOrderCreate, meta: { title: '变更销售订单', permission: 'sales.amend' } },
    { path: '/sales-orders/:id', name: 'sales-order-detail', component: SalesOrderDetail, meta: { title: '销售订单详情' } },
    { path: '/direct-trades', name: 'direct-trades', component: DirectTrades, meta: { title: '直运购销', permission: 'direct.trade.view' } },
    { path: '/direct-trades/new', name: 'direct-trade-new', component: DirectTradeCreate, meta: { title: '新建直运购销', permission: 'direct.trade.manage' } },
    { path: '/direct-trades/:id', name: 'direct-trade-detail', component: DirectTradeDetail, meta: { title: '直运购销详情', titleParam: 'id', permission: 'direct.trade.view' } },
    { path: '/inventory', component: Inventory, meta: { title: '仓库库存' } },
    { path: '/inventory/batches/:id/quality-certificates', redirect: '/inventory' },
    { path: '/inventory/non-trade', component: InventoryNonTradeDocuments, meta: { title: '送样 / 领用 / 损耗单', permission: 'inventory.nontrade.view' } },
    { path: '/inventory/non-trade/new', component: InventoryNonTradeCreate, meta: { title: '新建送样 / 领用 / 损耗单', permission: 'inventory.nontrade.manage' } },
    { path: '/inventory/non-trade/:id', component: InventoryNonTradeDetail, meta: { title: '送样 / 领用 / 损耗单', titleParam: 'id', permission: 'inventory.nontrade.view' } },
    { path: '/inventory/atp', component: InventoryAtp, meta: { title: '库存可承诺量' } },
    { path: '/inventory/movements', component: InventoryMovements, meta: { title: '库存流水' } },
    { path: '/inventory/reconciliation', component: InventoryReconciliation, meta: { title: '库存勾稽' } },
    { path: '/inventory/transfers', component: InventoryDocuments, props: { kind: 'transfer' }, meta: { title: '库存调拨' } },
    { path: '/inventory/transfers/new', component: InventoryDocumentCreate, props: { kind: 'transfer' }, meta: { title: '新建库存调拨', permission: 'inventory.transfer' } },
    { path: '/inventory/transfers/:id/edit', component: InventoryDocumentCreate, props: { kind: 'transfer' }, meta: { title: '编辑库存调拨', titleParam: 'id', permission: 'inventory.transfer' } },
    { path: '/inventory/transfers/:id', component: InventoryDocumentDetail, props: { kind: 'transfer' }, meta: { title: '库存调拨单', titleParam: 'id' } },
    { path: '/inventory/stocktakes', component: InventoryDocuments, props: { kind: 'stocktake' }, meta: { title: '库存盘点' } },
    { path: '/inventory/stocktakes/new', component: InventoryDocumentCreate, props: { kind: 'stocktake' }, meta: { title: '新建库存盘点', permission: 'inventory.stocktake' } },
    { path: '/inventory/stocktakes/:id/edit', component: InventoryDocumentCreate, props: { kind: 'stocktake' }, meta: { title: '编辑库存盘点', titleParam: 'id', permission: 'inventory.stocktake' } },
    { path: '/inventory/stocktakes/:id', component: InventoryDocumentDetail, props: { kind: 'stocktake' }, meta: { title: '库存盘点单', titleParam: 'id' } },
    { path: '/inventory/purchase-returns', component: InventoryDocuments, props: { kind: 'purchase-return' }, meta: { title: '采购退货', permission: 'purchase.view' } },
    { path: '/inventory/purchase-returns/new', component: InventoryDocumentCreate, props: { kind: 'purchase-return' }, meta: { title: '新建采购退货', permission: 'purchase.return' } },
    { path: '/inventory/purchase-returns/:id/edit', component: InventoryDocumentCreate, props: { kind: 'purchase-return' }, meta: { title: '编辑采购退货', titleParam: 'id', permission: 'purchase.return' } },
    { path: '/inventory/purchase-returns/:id', component: InventoryDocumentDetail, props: { kind: 'purchase-return' }, meta: { title: '采购退货单', titleParam: 'id', permission: 'purchase.view' } },
    { path: '/inventory/cost-adjustments', component: InventoryDocuments, props: { kind: 'cost-adjustment' }, meta: { title: '成本调整', permission: 'inventory.cost.view' } },
    { path: '/inventory/cost-adjustments/new', component: InventoryDocumentCreate, props: { kind: 'cost-adjustment' }, meta: { title: '新建成本调整', permission: 'inventory.cost.adjust' } },
    { path: '/inventory/cost-adjustments/:id/edit', component: InventoryDocumentCreate, props: { kind: 'cost-adjustment' }, meta: { title: '编辑成本调整', titleParam: 'id', permission: 'inventory.cost.adjust' } },
    { path: '/inventory/cost-adjustments/:id', component: InventoryDocumentDetail, props: { kind: 'cost-adjustment' }, meta: { title: '成本调整单', titleParam: 'id', permission: 'inventory.cost.view' } },
    { path: '/inventory/landed-cost-allocations', component: LandedCostAllocations, meta: { title: '采购费用分摊', permission: 'landed.cost.view' } },
    { path: '/inventory/landed-cost-allocations/new', component: LandedCostAllocationEdit, meta: { title: '新建采购费用分摊', permission: 'landed.cost.manage' } },
    { path: '/inventory/landed-cost-allocations/:id/edit', component: LandedCostAllocationEdit, meta: { title: '编辑采购费用分摊', titleParam: 'id', permission: 'landed.cost.manage' } },
    { path: '/inventory/landed-cost-allocations/:id', component: LandedCostAllocationDetail, meta: { title: '采购费用分摊单', titleParam: 'id', permission: 'landed.cost.view' } },
    { path: '/inbounds', name: 'inbounds', component: Inbounds, meta: { title: '进货入库' } },
    { path: '/inbounds/new', name: 'inbound-new', component: InboundCreate, meta: { title: '新建入库单' } },
    { path: '/inbounds/:id/edit', name: 'inbound-edit', component: InboundCreate, meta: { title: '编辑入库草稿' } },
    { path: '/inbounds/:id/review', name: 'inbound-review', component: InboundReview, meta: { title: '入库审核' } },
    { path: '/inbounds/:id/audit-logs', component: InboundAuditLogs, meta: { title: '审核记录' } },
    { path: '/inbounds/:id/audit-logs/:auditLogId', component: InboundAuditLogDetail, meta: { title: '审核记录详情', titleParam: 'auditLogId' } },
    { path: '/inbounds/:id', name: 'inbound-detail', component: InboundDetail, meta: { title: '入库单详情' } },
    { path: '/outbounds', name: 'outbounds', component: Outbounds, meta: { title: '销售出库' } },
    { path: '/outbounds/new', name: 'outbound-new', component: OutboundCreate, meta: { title: '新建出库单' } },
    { path: '/outbounds/:id/edit', name: 'outbound-edit', component: OutboundCreate, meta: { title: '编辑出库草稿' } },
    { path: '/outbounds/:id', name: 'outbound-detail', component: OutboundDetail, meta: { title: '出库单详情' } },
    { path: '/supplier-payments', component: SupplierPayments, meta: { title: '供应商付款' } },
    { path: '/supplier-payments/new', component: SupplierPaymentCreate, meta: { title: '付款核销' } },
    { path: '/payments', component: Payments, meta: { title: '回款核销' } },
    { path: '/payments/new', component: PaymentCreate, meta: { title: '回款核销' } },
    { path: '/customer-advances', component: CustomerAdvances, meta: { title: '客户预收款' } },
    { path: '/receivable-adjustments', component: ReceivableAdjustments, meta: { title: '应收调整单' } },
    { path: '/acceptance-bills', component: AcceptanceBills, meta: { title: '承兑票据' } },
    { path: '/payables', component: Payables, meta: { title: '应付账款' } },
    { path: '/receivables', component: Receivables, meta: { title: '应收账款' } },
    { path: '/partner-accounts', redirect: '/partner-fund-adjustments' },
    { path: '/partner-fund-adjustments', component: PartnerFundAdjustments, meta: { title: '余额调整单', permission: 'partner.fund.adjust.view' } },
    { path: '/partner-fund-adjustments/new', component: PartnerFundAdjustmentCreate, meta: { title: '新建余额调整单', permission: 'partner.fund.adjust.manage' } },
    { path: '/partner-fund-adjustments/:id', component: PartnerFundAdjustmentDetail, meta: { title: '余额调整单', titleParam: 'id', permission: 'partner.fund.adjust.view' } },
    { path: '/treasury', component: TreasuryDocuments, meta: { title: '费用与出纳', permission: 'treasury.view' } },
    { path: '/treasury/documents/:id', component: TreasuryDocumentDetail, meta: { title: '费用与其他往来源单', titleParam: 'id', permission: 'treasury.view' } },
    { path: '/treasury/journal', component: TreasuryJournal, meta: { title: '统一出纳日记账', permission: 'treasury.view' } },
    { path: '/finance', redirect: '/treasury/journal' },
    { path: '/business-postings', component: BusinessPostings, meta: { title: '业务过账', permission: 'posting.view' } },
    { path: '/business-postings/:id', component: BusinessPostingDetail, meta: { title: '业务过账详情', titleParam: 'id', permission: 'posting.view' } },
    { path: '/tax-invoices', component: TaxInvoiceDashboard, meta: { title: '发票税务', permission: 'invoice.view' } },
    { path: '/tax-invoices/input', component: TaxInvoices, meta: { title: '进项发票', direction: 'INPUT', permission: 'invoice.view' } },
    { path: '/tax-invoices/output', component: TaxInvoices, meta: { title: '销项发票', direction: 'OUTPUT', permission: 'invoice.view' } },
    { path: '/tax-invoices/reconciliation', component: TaxInvoices, meta: { title: '税票勾稽', exceptionOnly: true, permission: 'invoice.view' } },
    { path: '/tax-invoices/new', component: TaxInvoiceEdit, meta: { title: '登记发票', permission: 'invoice.manage' } },
    { path: '/tax-invoices/:id/edit', component: TaxInvoiceEdit, meta: { title: '编辑发票', titleParam: 'id', permission: 'invoice.manage' } },
    { path: '/tax-invoices/:id', component: TaxInvoiceDetail, meta: { title: '发票详情', titleParam: 'id', permission: 'invoice.view' } },
    { path: '/tax-compliance', component: TaxCompliance, meta: { title: '电子凭证与票账税', permission: 'tax.compliance.view' } },
    { path: '/tax-compliance/credentials/:id', component: ElectronicCredentialDetail, meta: { title: '电子凭证详情', titleParam: 'id', permission: 'tax.compliance.view' } },
    { path: '/tax-compliance/workpapers/:id', component: TaxWorkpaperDetail, meta: { title: '增值税工作底稿', titleParam: 'id', permission: 'tax.compliance.view' } },
    { path: '/accounting', component: AccountingDashboard, meta: { title: '会计核算', permission: 'accounting.view' } },
    { path: '/accounting/vouchers', component: AccountingVouchers, meta: { title: '会计凭证', permission: 'accounting.view' } },
    { path: '/accounting/vouchers/new', component: AccountingVoucherEdit, meta: { title: '新增手工凭证', permission: 'accounting.manage' } },
    { path: '/accounting/vouchers/:id/edit', component: AccountingVoucherEdit, meta: { title: '编辑会计凭证', titleParam: 'id', permission: 'accounting.manage' } },
    { path: '/accounting/vouchers/:id', component: AccountingVoucherDetail, meta: { title: '会计凭证详情', titleParam: 'id', permission: 'accounting.view' } },
    { path: '/accounting/ledger', component: AccountingLedger, meta: { title: '总账与明细账', permission: 'accounting.view' } },
    { path: '/accounting/reports', component: FinancialReports, meta: { title: '财务报表', permission: 'financial.report.view' } },
    { path: '/accounting/bank-reconciliation', component: BankReconciliations, meta: { title: '银行对账', permission: 'financial.bank.view' } },
    { path: '/accounting/period-close', component: PeriodClose, meta: { title: '期末结账', permission: 'financial.report.view' } },
    { path: '/accounting/master-data', component: AccountingMasterData, meta: { title: '科目与期间', permission: 'accounting.view' } },
    { path: '/accounting/rules', component: AccountingRules, meta: { title: '自动制证规则', permission: 'accounting.view' } },
    { path: '/crm', component: CrmDashboard, meta: { title: '客户经营 CRM', permission: 'crm.analysis.view' } },
    { path: '/crm/leads', component: CrmLeads, meta: { title: '线索', permission: 'crm.record.view' } },
    { path: '/crm/leads/:id', component: CrmLeadDetail, meta: { title: '线索详情', titleParam: 'id', permission: 'crm.record.view' } },
    { path: '/crm/customers', component: CrmCustomers, meta: { title: '客户经营档案', permission: 'crm.record.view' } },
    { path: '/crm/customers/:id', component: CrmCustomerDetail, meta: { title: '客户经营详情', titleParam: 'id', permission: 'crm.record.view' } },
    { path: '/crm/opportunities', component: CrmOpportunities, meta: { title: '商机', permission: 'crm.record.view' } },
    { path: '/crm/opportunities/:id', component: CrmOpportunityDetail, meta: { title: '商机详情', titleParam: 'id', permission: 'crm.record.view' } },
    { path: '/crm/quotes', component: CrmQuotes, meta: { title: '报价', permission: 'crm.record.view' } },
    { path: '/crm/quotes/new', component: CrmQuoteEdit, meta: { title: '新建报价', permission: 'crm.record.manage' } },
    { path: '/crm/quotes/:id/edit', component: CrmQuoteEdit, meta: { title: '编辑报价', titleParam: 'id', permission: 'crm.record.manage' } },
    { path: '/crm/quotes/:id', component: CrmQuoteDetail, meta: { title: '报价详情', titleParam: 'id', permission: 'crm.record.view' } },
    { path: '/crm/activities', component: CrmActivities, meta: { title: '销售活动与计划', permission: 'crm.record.view' } },
    { path: '/platform', component: PlatformListings, meta: { title: '平台货源' } },
    { path: '/organization', component: OrganizationPeople, meta: { title: '部门人员', permission: 'organization.view' } },
    { path: '/system/users', component: SystemUsers, meta: { title: '用户管理', permission: 'user.view' } },
    { path: '/system/roles', component: RolePermissions, meta: { title: '角色权限', permission: 'role.view' } },
    { path: '/system/security-audit', component: SecurityAudit, meta: { title: '安全审计', permission: 'security.audit.view' } },
    { path: '/data-migrations', component: DataMigrations, meta: { title: '期初数据迁移', permission: 'data.migration.view' } },
    { path: '/data-exports', component: BusinessExports, meta: { title: '完整数据导出', permission: 'data.export' } },
    { path: '/storage-capacity', component: StorageCapacity, meta: { title: '存储与容量', permission: 'storage.capacity.view' } },
  ],
})

router.beforeEach(async (to) => {
  const appStore = useAppStore()
  await appStore.restoreSession()
  if (to.meta.public) {
    if (!appStore.isAuthenticated || to.path !== '/login') return true
    return appStore.session?.passwordChangeRequired
      ? '/settings?section=password'
      : resolvePostLoginPath(to.query.redirect)
  }
  if (!appStore.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (appStore.session?.passwordChangeRequired && to.path !== '/settings') {
    return '/settings?section=password'
  }
  const permissionsAny = Array.isArray(to.meta.permissionsAny)
    ? to.meta.permissionsAny.filter((item): item is string => typeof item === 'string')
    : []
  const permissionsAll = Array.isArray(to.meta.permissionsAll)
    ? to.meta.permissionsAll.filter((item): item is string => typeof item === 'string')
    : []
  if (permissionsAny.length && !permissionsAny.some((item) => appStore.hasPermission(item))) return '/dashboard'
  if (permissionsAll.length && !permissionsAll.every((item) => appStore.hasPermission(item))) return '/dashboard'
  const permission = typeof to.meta.permission === 'string'
    ? to.meta.permission
    : permissionsAny.length || permissionsAll.length ? undefined : routePermission(to.path)
  if (permission && !appStore.hasPermission(permission)) return '/dashboard'
  return true
})

function routePermission(path: string) {
  if (path.startsWith('/partners')) return 'partner.view'
  if (path.startsWith('/product') || path === '/products' || path === '/units') return 'product.view'
  if (path === '/warehouses') return 'warehouse.view'
  if (path.startsWith('/purchase-orders')) return path.endsWith('/new') || path.endsWith('/edit') ? 'purchase.manage' : 'purchase.view'
  if (path.startsWith('/inbounds')) {
    if (path.endsWith('/new') || path.endsWith('/edit')) return 'inbound.manage'
    if (path.endsWith('/review')) return 'inbound.audit'
    return 'inbound.view'
  }
  if (path.startsWith('/inventory/transfers/new')) return 'inventory.transfer'
  if (path.startsWith('/inventory/stocktakes/new')) return 'inventory.stocktake'
  if (path.startsWith('/inventory/purchase-returns/new')) return 'purchase.return'
  if (path.startsWith('/inventory/purchase-returns')) return 'purchase.view'
  if (path.startsWith('/inventory/cost-adjustments/new')) return 'inventory.cost.adjust'
  if (path.startsWith('/inventory/cost-adjustments')) return 'inventory.cost.view'
  if (path.startsWith('/inventory/landed-cost-allocations/new') || (path.includes('/inventory/landed-cost-allocations/') && path.endsWith('/edit'))) return 'landed.cost.manage'
  if (path.startsWith('/inventory/landed-cost-allocations')) return 'landed.cost.view'
  if (path.startsWith('/inventory')) return 'inventory.view'
  if (path.startsWith('/sales-orders')) return path.endsWith('/new') || path.endsWith('/edit') ? 'sales.manage' : 'sales.view'
  if (path.startsWith('/outbounds')) return path.endsWith('/new') || path.endsWith('/edit') ? 'outbound.manage' : 'outbound.view'
  if (path.startsWith('/partner-fund-adjustments/new')) return 'partner.fund.adjust.manage'
  if (path.startsWith('/partner-fund-adjustments')) return 'partner.fund.adjust.view'
  if (['/supplier-payments', '/payments', '/customer-advances', '/receivable-adjustments', '/acceptance-bills', '/payables', '/partner-accounts', '/finance'].some((prefix) => path.startsWith(prefix))) return 'finance.view'
  if (path.startsWith('/business-postings')) return 'posting.view'
  if (path.startsWith('/tax-invoices/new') || (path.startsWith('/tax-invoices') && path.endsWith('/edit'))) return 'invoice.manage'
  if (path.startsWith('/tax-invoices')) return 'invoice.view'
  if (path.startsWith('/tax-compliance')) return 'tax.compliance.view'
  if (path.startsWith('/accounting/reports') || path.startsWith('/accounting/period-close')) return 'financial.report.view'
  if (path.startsWith('/accounting/bank-reconciliation')) return 'financial.bank.view'
  if (path.startsWith('/accounting/vouchers/new') || (path.startsWith('/accounting') && path.endsWith('/edit'))) return 'accounting.manage'
  if (path.startsWith('/accounting')) return 'accounting.view'
  if (path.startsWith('/crm')) return path === '/crm' ? 'crm.analysis.view' : 'crm.record.view'
  if (path.startsWith('/platform')) return 'platform.view'
  if (path.startsWith('/operations-assistant')) return 'operations.insight.view'
  if (path.startsWith('/data-migrations')) return 'data.migration.view'
  if (path.startsWith('/data-exports')) return 'data.export'
  if (path.startsWith('/storage-capacity')) return 'storage.capacity.view'
  if (path.startsWith('/system/security-audit')) return 'security.audit.view'
  return undefined
}

export default router
