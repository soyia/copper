import assert from 'node:assert/strict'
import { existsSync } from 'node:fs'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const files = {
  api: new URL('../src/api/client.ts', import.meta.url),
  products: new URL('../src/views/Products.vue', import.meta.url),
  detail: new URL('../src/views/ProductMasterDetail.vue', import.meta.url),
  matrix: new URL('../src/views/ProductMatrixCreate.vue', import.meta.url),
  catalog: new URL('../src/views/ProductSeries.vue', import.meta.url),
  mainCss: new URL('../src/assets/main.css', import.meta.url),
  productImport: new URL('../src/views/ProductImport.vue', import.meta.url),
  productDisplay: new URL('../src/utils/productDisplay.ts', import.meta.url),
  productNaming: new URL('../src/utils/productNaming.ts', import.meta.url),
  inbound: new URL('../src/views/InboundCreate.vue', import.meta.url),
  purchaseOrder: new URL('../src/views/PurchaseOrderCreate.vue', import.meta.url),
  salesOrder: new URL('../src/views/SalesOrderCreate.vue', import.meta.url),
  crmQuote: new URL('../src/views/CrmQuoteEdit.vue', import.meta.url),
  mobileQuickSale: new URL('../src/views/mobile/MobileQuickSale.vue', import.meta.url),
  app: new URL('../src/App.vue', import.meta.url),
  router: new URL('../src/router/index.ts', import.meta.url),
  tabs: new URL('../src/stores/workspaceTabs.ts', import.meta.url),
}

const source = (name) => readFile(files[name], 'utf8')

test('product API keeps duplicate packaging supplier and import contracts', async () => {
  const api = await source('api')
  assert.match(api, /checkProductDuplicate[\s\S]*products\/duplicate-check/)
  assert.match(api, /productPackagingVariants[\s\S]*packaging-variants/)
  assert.match(api, /supplierProductOffers[\s\S]*supplier-offers/)
  assert.match(api, /previewProductImport[\s\S]*product-imports\/preview/)
  assert.doesNotMatch(api, /product-source-documents/)
  assert.doesNotMatch(api, /products\/\$\{productId\}\/quality-certificates/)
  assert.match(api, /inventory\/batches\/\$\{batchId\}\/quality-certificates/)
  assert.doesNotMatch(api, /technical-specs/)
  assert.doesNotMatch(api, /\/bom/)
  assert.doesNotMatch(api, /conductor-construction/)
  assert.doesNotMatch(api, /\/ports/)
  assert.doesNotMatch(api, /identity-schema/)
})

test('product import downloads a tailored xlsx or csv template for one series', async () => {
  const api = await source('api')
  const productImport = await source('productImport')
  assert.match(api, /productImportTemplate: \(format: 'xlsx' \| 'csv', seriesId: number\)/)
  assert.match(api, /params: \{ format, seriesId \}/)
  assert.match(productImport, /先选择产品系列，再下载专用模板/)
  assert.match(productImport, /downloadSeriesTemplate\('xlsx'\)/)
  assert.match(productImport, /downloadSeriesTemplate\('csv'\)/)
  for (const removed of [
    '来源URL', '来源文件版本', '来源页码', '默认单价', '启用状态', '供应商原始编号',
  ]) {
    assert.doesNotMatch(productImport, new RegExp(removed), removed)
  }
})

test('single product entry is a two-step small-trader workflow', async () => {
  const products = await source('products')
  assert.match(products, /title="商品与规格"/)
  assert.match(products, /title="单位与供货"/)
  assert.doesNotMatch(products, /title="SKU 身份"/)
  assert.doesNotMatch(products, /activeStep === [23]/)
  assert.match(products, /高级工具/)
  assert.match(products, /handleAdvancedCommand/)
  assert.match(products, /当前系列暂无启用的规格预设/)
  assert.match(products, /规格预设是可选快捷项/)
  assert.match(products, /openSeriesPresetManagement/)
  assert.doesNotMatch(products, /identityLocked \|\| !seriesPresets\.length/)
  assert.match(products, /label="操作" width="128" fixed="right"/)
  assert.match(products, /class="product-table-actions"/)
  assert.doesNotMatch(products, /@click="router\.push\(`\/products\/\$\{row\.id\}`\)">详情/)
  assert.match(products, /@click="openCopy\(row\)">复制/)
  assert.match(products, /<strong>确定主单位和包装<\/strong>/)
  assert.match(products, /按规格生成名称/)
  assert.match(products, /prop="sku" label="SKU（系统生成）"/)
  assert.doesNotMatch(products, /按规格生成 SKU\/名称/)
  assert.doesNotMatch(products, /<el-form-item label="SKU" prop="sku">/)
  assert.doesNotMatch(products, /sku: \[\{ required: true/)
  assert.match(products, /label="系列计量模板"/)
  assert.match(products, /更换模板会影响本系列现有/)
  assert.match(products, /saveSeriesPackagingBinding/)
  assert.doesNotMatch(products, /ProductPackagingDialog/)
  assert.doesNotMatch(products, /openPackaging/)
})

test('manufacturer grade temper and standard accept existing or free text values', async () => {
  const products = await source('products')
  const matrix = await source('matrix')
  assert.match(products, /allow-create/)
  assert.match(products, /未匹配规范库不会阻断采购、销售或库存业务/)
  assert.match(matrix, /allow-create/)
  assert.match(matrix, /可选择已有值，也可直接输入厂家写法/)
})

test('series field checkbox automatically represents product distinction', async () => {
  const catalog = await source('catalog')
  assert.match(catalog, /用于区分商品/)
  assert.match(catalog, /保存后立即用于该系列的新建、编辑和重复检查/)
  assert.match(catalog, /createSeriesField/)
  assert.match(catalog, /skuDimension/)
  assert.match(catalog, /selectSeriesForSpecs/)
  assert.match(catalog, /catalogView === 'series'/)
  assert.match(catalog, /catalogView === 'specs'/)
  assert.match(catalog, /返回系列列表/)
  assert.match(catalog, /建档字段（/)
  assert.match(catalog, /规格预设（/)
  assert.match(catalog, /query: \{ view: 'specs', seriesId: String\(seriesId\), tab \}/)
  assert.match(catalog, /class="series-row-actions"/)
  assert.doesNotMatch(catalog, /scrollIntoView/)
})

test('all table row text and link actions use one compact global spacing rule', async () => {
  const mainCss = await source('mainCss')
  assert.match(mainCss, /\.el-table__body \.el-table__cell \.el-button\.is-text/)
  assert.match(mainCss, /\.el-table__body \.el-table__cell \.el-button\.is-link/)
  assert.match(mainCss, /padding-inline: 4px/)
  assert.match(mainCss, /\.el-button\.is-link \+ \.el-button\.is-link[\s\S]*margin-left: 4px/)
})

test('matrix creation derives distinction fields from series configuration', async () => {
  const matrix = await source('matrix')
  assert.match(matrix, /field\.skuDimension/)
  assert.match(matrix, /每一行仍会执行同规格重复检查/)
  assert.doesNotMatch(matrix, /<el-table-column label="SKU"/)
  assert.doesNotMatch(matrix, /requiredStructuredDimensions/)
  assert.doesNotMatch(matrix, /productIdentitySchema/)
})

test('product forms generate readable names without requiring or exposing SKU input', async () => {
  const products = await source('products')
  const matrix = await source('matrix')
  const catalog = await source('catalog')
  const naming = await source('productNaming')
  assert.match(naming, /generateProductName/)
  assert.match(naming, /grade[\s\S]*temper[\s\S]*numericDimensions/)
  assert.match(naming, /dimensionsText\(numericDimensions\)/)
  assert.doesNotMatch(naming, /sku:/)
  assert.match(products, /generateProductName\(currentSeries/)
  assert.doesNotMatch(products, /v-model="form\.sku"/)
  assert.doesNotMatch(products, /form\.sku = generated/)
  assert.match(matrix, /generateProductName\(selectedSeries\.value/)
  assert.doesNotMatch(matrix, /v-model="row\.sku"/)
  assert.doesNotMatch(matrix, /row\.sku = generated/)
  assert.match(catalog, /label="系列编码" prop="code"/)
  assert.match(catalog, /只能使用大写字母、数字、- 和 _/)
})

test('product detail only exposes operating specifications packaging and supplier terms', async () => {
  const detail = await source('detail')
  assert.match(detail, /基本资料/)
  assert.match(detail, /label="启停状态"/)
  assert.match(detail, /label="经营状态"/)
  assert.match(detail, /productBusinessStatus\.description/)
  assert.match(detail, /经营规格/)
  assert.match(detail, /单位与包装/)
  assert.doesNotMatch(detail, /这里汇总系列模板、商品特例和最终生效规则/)
  assert.match(detail, /当前最终生效结果/)
  assert.match(detail, /当前生效计量策略/)
  assert.match(detail, /计量与包装规则/)
  assert.match(detail, /商品特例覆盖/)
  assert.match(detail, /商品特例停用/)
  assert.match(detail, /api\.packagingProfile\(productId\)/)
  assert.match(detail, /api\.inheritedPackagingProfile\(productId\)/)
  assert.match(detail, /api\.packageDefinitions\(productId\)/)
  assert.match(detail, /class="product-detail-back"[\s\S]*:icon="ArrowLeft"/)
  assert.match(detail, /class="product-detail-header__actions"/)
  assert.match(detail, /@click="openProductForm\('edit'\)">编辑/)
  assert.match(detail, /@click="openProductForm\('copy'\)">复制/)
  assert.match(detail, /@click="refreshDetail">刷新/)
  assert.match(detail, /@click="toggleFullscreen"/)
  assert.match(detail, /panel\.requestFullscreen\(\)/)
  assert.doesNotMatch(detail, /window\.location\.reload|location\.reload/)
  assert.match(detail, /class="compact-empty-table"/)
  assert.match(detail, /<template #empty>[\s\S]*没有额外的商品特殊包装档案/)
  assert.doesNotMatch(detail, /v-if="!packagingVariants\.length" description="没有额外的商品特殊包装档案"/)
  assert.match(detail, /供应商供货/)
  assert.match(detail, /productPackagingVariantRevisions/)
  assert.match(detail, /supplierProductOfferRevisions/)
  const supplierDialog = detail.slice(
    detail.indexOf('<el-dialog v-model="supplierOfferVisible"'),
    detail.indexOf('<el-dialog v-model="historyVisible"'),
  )
  assert.match(supplierDialog, /width="860px"/)
  assert.match(supplierDialog, /:span="12"/)
  assert.doesNotMatch(supplierDialog, /:span="(?:6|8|18)"/)
  assert.match(detail, /function emptyOffer[\s\S]*supplierId: undefined/)
  for (const removed of ['来源资料', '技术规格', '批次质量', 'BOM', '端口结构', '导体结构', '试验条件']) {
    assert.doesNotMatch(detail, new RegExp(removed), removed)
  }
})

test('business product selectors hide unresolved identities while explaining historical selections', async () => {
  const productDisplay = await source('productDisplay')
  assert.match(productDisplay, /isTransactionSelectableProduct\(product\) \|\| product\.id === currentProductId/)
  assert.match(productDisplay, /product\.identityStatus === 'PENDING_REVIEW'[\s\S]*待复核，不可新增业务/)
  assert.match(productDisplay, /product\.identityStatus !== 'PUBLISHED'[\s\S]*身份未完成，不可新增业务/)
  assert.match(productDisplay, /product\.status === 'ENABLED' && product\.identityStatus === 'PUBLISHED'/)

  for (const name of ['inbound', 'purchaseOrder', 'salesOrder', 'crmQuote', 'mobileQuickSale']) {
    const page = await source(name)
    assert.match(page, /:disabled="!isTransactionSelectableProduct\(item\)"/, name)
  }
})

test('product list and detail expose business eligibility without extra row queries', async () => {
  const productDisplay = await source('productDisplay')
  const products = await source('products')
  const detail = await source('detail')
  assert.match(productDisplay, /label: '可用于业务'/)
  assert.match(productDisplay, /label: '待复核，不可开单'/)
  assert.match(productDisplay, /label: '资料未完成，不可开单'/)
  assert.match(products, /label="经营状态"/)
  assert.match(products, /productBusinessStatusPresentation\(row\)/)
  assert.match(products, /label="启停状态"/)
  assert.match(detail, /productBusinessStatusPresentation/)
  assert.match(detail, /v-if="!productBusinessStatus\.available"/)
})

test('product copy opens create mode with reusable relations but no source identity or history', async () => {
  const products = await source('products')
  const detail = await source('detail')
  assert.match(products, /async function openCopy\(row: Product\)/)
  assert.match(products, /copyingSource\.value = row/)
  assert.match(products, /form\.sku = ''/)
  assert.match(products, /form\.barcode = ''/)
  assert.match(products, /form\.originalNumber = ''/)
  assert.match(products, /form\.defaultPrice = 0/)
  assert.match(products, /packagingVariantId\.value = undefined/)
  assert.match(products, /supplierOfferId\.value = undefined/)
  assert.match(products, /sourceDocumentRevisionId: null/)
  assert.match(products, /expectedRevisionNo: 0/)
  assert.match(products, /源商品共 \$\{enabledPackagingCount\} 条，仅带入第一条/)
  assert.match(products, /未复制商品 ID、商品条码、内部原编码、参考价、包装\/供应商专属编码、历史版本和来源附件/)
  assert.match(products, /action === 'copy'[\s\S]*await openCopy\(row\)/)
  assert.match(detail, /query: \{ action, productId: String\(productId\) \}/)
})

test('retired enterprise master pages redirect away from daily ERP', async () => {
  const router = await source('router')
  const app = await source('app')
  const tabs = await source('tabs')
  assert.match(router, /path: '\/product-identity-schemas', redirect: '\/product-catalog'/)
  assert.match(router, /quality-certificates', redirect: '\/inventory'/)
  assert.match(router, /path: '\/product-standards', redirect: '\/product-catalog'/)
  assert.match(router, /path: '\/product-tempers', redirect: '\/product-catalog'/)
  assert.doesNotMatch(app, /label: 'SKU身份规则'/)
  assert.doesNotMatch(app, /label: '规范执行标准'/)
  assert.doesNotMatch(app, /label: '规范材料状态'/)
  assert.match(app, /label: '分类与规格'/)
  assert.match(tabs, /\/product-identity-schemas/)
  assert.match(tabs, /title: '分类与规格'/)
})

test('removed product-governance views are no longer shipped', () => {
  assert.equal(existsSync(new URL('../src/views/ProductIdentitySchemas.vue', import.meta.url)), false)
  assert.equal(existsSync(new URL('../src/views/ProductBatchQualityCertificates.vue', import.meta.url)), false)
})

test('packaging and supplier snapshots remain separate from internal SKU', async () => {
  const products = await source('products')
  const detail = await source('detail')
  assert.match(products, /packagingVariantRevisionId/)
  assert.match(products, /supplierProductCode/)
  assert.match(products, /supplierModel/)
  assert.match(detail, /改变或停用既有包装时请填写变更原因/)
  assert.match(detail, /实际订单价格仍在采购时确认并冻结/)
})

test('product menu keeps only daily entries and advanced import', async () => {
  const app = await source('app')
  assert.match(app, /label: '商品资料'/)
  assert.match(app, /label: '分类与规格'/)
  assert.match(app, /label: '计量与包装'/)
  assert.match(app, /label: '批量导入（高级）'/)
  assert.doesNotMatch(app, /label: '规格字段库'/)
  assert.doesNotMatch(app, /label: '系列规格模板'/)
})
