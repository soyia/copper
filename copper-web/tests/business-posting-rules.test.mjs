import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

function source(path) {
  return readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')
}

const api = source('src/api/client.ts')
const router = source('src/router/index.ts')
const app = source('src/App.vue')
const postings = source('src/views/BusinessPostings.vue')
const postingDetail = source('src/views/BusinessPostingDetail.vue')
const purchaseCreate = source('src/views/PurchaseOrderCreate.vue')
const purchaseDetail = source('src/views/PurchaseOrderDetail.vue')
const salesCreate = source('src/views/SalesOrderCreate.vue')
const salesDetail = source('src/views/SalesOrderDetail.vue')
const packagingTemplate = source('src/components/PackagingTemplateDialog.vue')
const packagingTemplateEdit = source('src/components/PackagingTemplateEditDialog.vue')
const packagingTemplateDetail = source('src/views/PackagingTemplateDetail.vue')
const productPackaging = source('src/components/ProductPackagingDialog.vue')
const productPackagingWorkbench = source('src/views/ProductPackaging.vue')
const seriesPackagingBindingDialog = source('src/components/SeriesPackagingBindingDialog.vue')
const productSeries = source('src/views/ProductSeries.vue')
const packagingValidation = source('src/utils/packagingValidation.ts')
const outbound = source('src/views/OutboundCreate.vue')

test('business posting workbench exposes batches rules entries events and reconciliation', () => {
  for (const name of ['businessPostingRules', 'businessPostingBatches', 'businessPostingDetail', 'businessPostingReconciliation']) {
    assert.match(api, new RegExp(`${name}:`), name)
  }
  assert.match(router, /\/business-postings/)
  assert.match(router, /\/business-postings\/:id/)
  assert.match(app, /业务过账/)
  for (const label of ['过账批次', '勾稽差异', '规则版本']) assert.match(postings, new RegExp(label), label)
  for (const label of ['业务事实分录', '状态事件', '分录摘要']) assert.match(postingDetail, new RegExp(label), label)
})

test('confirmed purchase and sales orders use versioned amendments instead of silent overwrite', () => {
  for (const name of ['amendPurchaseOrder', 'purchaseOrderRevisions', 'purchaseAmendmentPackagingPlan',
    'amendSalesOrder', 'salesOrderRevisions', 'salesAmendmentPackagingPlan']) {
    assert.match(api, new RegExp(`${name}:`), name)
  }
  for (const path of ['/purchase-orders/:id/amend', '/sales-orders/:id/amend']) {
    assert.match(router, new RegExp(path.replaceAll('/', '\\/').replace(':', '\\:')))
  }
  for (const view of [purchaseCreate, salesCreate]) {
    assert.match(view, /变更原因/)
    assert.match(view, /fulfilledQuantity/)
    assert.match(view, /_fulfilledQuantity/)
  }
  assert.match(purchaseDetail, /变更订单/)
  assert.match(purchaseDetail, /版本与承诺过账/)
  assert.match(salesDetail, /变更订单/)
  assert.match(salesDetail, /版本与承诺过账/)
})

test('only fixed-count packaging can be split while actual-weight packages remain whole', () => {
  for (const dialog of [packagingTemplate, productPackaging]) {
    assert.match(dialog, /:disabled="row\.conversionMode !== 'FIXED'"/)
    assert.match(dialog, /if \(rule\.conversionMode !== 'FIXED'\) rule\.breakable = false/)
  }
  assert.match(packagingValidation, /只有固定数量包装可以拆分；实重包装必须整包流转/)
  assert.match(packagingValidation, /applyActualWeightPackagingPreset/)
  assert.match(packagingTemplate, /按\{\{ actualWeightRule\.childUnit \}\}计量、\{\{ actualWeightRule\.parentUnit \}\}包装/)
  assert.match(packagingTemplate, /包装另记 3\{\{ actualWeightRule\.parentUnit \}\}、合计 63/)
  assert.match(packagingValidation, /policy\.defaultPurchaseUnit = normalizedBaseUnit/)
  assert.match(outbound, /逐件实重包装必须选择具体整盘\/整箱/)
  assert.match(outbound, /kg 数量由所选包装实重自动汇总/)
  assert.match(outbound, /所选整包装超过销售订单剩余重量/)
  assert.match(outbound, /row\.quantityUnit = row\.packagingProfile\?\.baseUnit/)
  for (const view of [purchaseCreate, salesCreate]) {
    assert.match(view, /预计包装数/)
    assert.match(view, /packageQuantity/)
  }
})

test('packaging template metadata status and rule publication use separate operations', () => {
  for (const name of [
    'publishPackagingTemplate',
    'updatePackagingTemplateMetadata',
    'updatePackagingTemplateStatus',
  ]) {
    assert.match(api, new RegExp(`${name}:`), name)
  }
  assert.match(packagingTemplate, /发布包装规则新版本/)
  assert.match(packagingTemplate, /api\.publishPackagingTemplate/)
  assert.doesNotMatch(packagingTemplate, /api\.updatePackagingTemplate\(/)
  for (const label of ['详情', '编辑', '发新版']) {
    assert.match(productPackagingWorkbench, new RegExp(label), label)
  }
  assert.doesNotMatch(productPackagingWorkbench, /openStatus/)
  assert.match(packagingTemplateEdit, /label="状态"/)
  assert.match(packagingTemplateEdit, /v-if="statusChanged"/)
  assert.match(packagingTemplateEdit, /状态发生变化时必须填写变更原因/)
  assert.match(packagingTemplateEdit, /api\.updatePackagingTemplateMetadata/)
  assert.match(packagingTemplateEdit, /api\.updatePackagingTemplateStatus/)
  assert.match(packagingTemplateEdit, /规则版本未变化/)
  assert.match(packagingTemplateEdit, /已有系列绑定、历史单据和库存批次快照继续有效/)
})

test('packaging template codes and series relationships use one governed relationship from multiple entries', () => {
  assert.ok(packagingTemplate.includes('^[A-Za-z0-9_-]+$'))
  assert.match(packagingTemplate, /模板编码只能使用数字、英文字母、短横线和下划线/)
  assert.match(router, /\/product-packaging\/templates\/:id/)
  assert.match(productPackagingWorkbench, /openDetail/)
  assert.match(packagingTemplateDetail, /关联产品系列（/)
  assert.match(packagingTemplateDetail, /新增关联/)
  assert.match(packagingTemplateDetail, /编辑关系/)
  assert.match(packagingTemplateDetail, /item\.templateId === templateId\.value/)
  assert.match(packagingTemplateDetail, /SeriesPackagingBindingDialog/)
  assert.match(packagingTemplateDetail, /preferred-template-id/)
  assert.match(seriesPackagingBindingDialog, /api\.saveSeriesPackagingBinding/)
  assert.match(seriesPackagingBindingDialog, /expectedBindingVersionNo: binding\.bindingVersionNo/)
  assert.match(seriesPackagingBindingDialog, /变更原因/)
  assert.match(seriesPackagingBindingDialog, /当前系列已经使用该模板，无需重复应用/)
  assert.match(productSeries, /计量模板/)
  assert.match(productSeries, /SeriesPackagingBindingDialog/)
})
