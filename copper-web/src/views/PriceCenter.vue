<template>
  <div class="page price-center-page">
    <PageHeader title="铜材价格中心" back-to="/dashboard">
      <el-button v-if="canManage" @click="openBenchmark">新增基准价版本</el-button>
      <el-button v-if="canManage" type="primary" @click="openRule">新增价格规则</el-button>
    </PageHeader>

    <div class="summary-grid">
      <div class="summary-card"><span>最新铜基准价</span><strong>{{ latestBenchmark ? `${money4(latestBenchmark.unitPrice)} / ${latestBenchmark.priceUnit}` : '尚未建立' }}</strong><small>{{ latestBenchmark ? `${latestBenchmark.benchmarkName} · ${latestBenchmark.effectiveDate}` : '先建立 CU-SPOT 基准价版本' }}</small></div>
      <div class="summary-card"><span>已发布价格规则</span><strong>{{ rules.length }}</strong><small>标准价、等级价、协议价和特价统一优先级</small></div>
      <div class="summary-card"><span>客户协议 / 特价</span><strong>{{ customerRuleCount }}</strong><small>按客户、商品、数量和生效日唯一命中</small></div>
      <div class="summary-card"><span>供应商协议价</span><strong>{{ supplierRuleCount }}</strong><small>替代无版本的固定进价台账</small></div>
    </div>

    <el-alert class="principle-alert" type="info" :closable="false" show-icon>
      <template #title>取价顺序固定：客户特价 → 客户协议价 → 客户等级价 → 标准铜价公式；采购为供应商协议价 → 标准规则。人工改价不会覆盖规则，会在单据中记录为“人工改价”。</template>
    </el-alert>

    <section class="panel">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="价格规则" name="rules">
          <div class="toolbar">
            <el-segmented v-model="ruleBusinessFilter" :options="businessFilters" />
            <el-input v-model="ruleKeyword" clearable placeholder="搜索规则、商品、客户或代码" class="keyword" />
          </div>
          <el-table :data="filteredRules" border stripe>
            <el-table-column label="规则 / 版本" min-width="190"><template #default="{ row }"><div class="primary-cell">{{ row.ruleName }}</div><div class="muted">{{ row.ruleCode }} · V{{ row.versionNo }}</div></template></el-table-column>
            <el-table-column label="适用范围" min-width="160"><template #default="{ row }"><el-tag size="small" :type="scopeTagType(row.scopeType)">{{ scopeName(row) }}</el-tag><div class="muted rule-kind">{{ agreementName(row.agreementType) }}</div></template></el-table-column>
            <el-table-column label="商品" min-width="210"><template #default="{ row }"><div>{{ row.productSku }} · {{ row.productName }}</div><div class="muted ellipsis">{{ row.productSpec || '无规格' }}</div></template></el-table-column>
            <el-table-column label="数量阶梯" width="145"><template #default="{ row }">{{ quantityRange(row) }} {{ row.priceUnit }}</template></el-table-column>
            <el-table-column label="计价组成" min-width="260"><template #default="{ row }"><div v-if="row.pricingMode === 'FIXED'" class="price-value">固定价 {{ money4(row.fixedUnitPrice) }}</div><div v-else><div class="price-value">{{ row.benchmarkCode }} + {{ money4(ruleFeeTotal(row)) }}</div><div class="muted">加工 {{ money4(row.processingFee) }} · 规格 {{ money4(row.specificationFee) }} · 包装 {{ money4(row.packagingFee) }} · 运费 {{ money4(row.freightFee) }}</div></div></template></el-table-column>
            <el-table-column label="价税口径" width="115"><template #default="{ row }">{{ row.priceIncludesTax ? '含税' : '未税' }} {{ row.taxRate }}%</template></el-table-column>
            <el-table-column label="生效期间" width="190"><template #default="{ row }">{{ row.effectiveFrom }}<br><span class="muted">至 {{ row.effectiveTo || '长期' }}</span></template></el-table-column>
            <el-table-column label="审计" min-width="150"><template #default="{ row }"><div>{{ row.operatorName }}</div><el-tooltip :content="row.changeReason"><div class="muted ellipsis">{{ row.changeReason }}</div></el-tooltip></template></el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="铜基准价" name="benchmarks">
          <el-table :data="benchmarks" border stripe>
            <el-table-column label="基准代码" width="140" prop="benchmarkCode" />
            <el-table-column label="名称" min-width="170" prop="benchmarkName" />
            <el-table-column label="版本" width="80"><template #default="{ row }">V{{ row.versionNo }}</template></el-table-column>
            <el-table-column label="基准价" min-width="160"><template #default="{ row }"><span class="price-value">{{ money4(row.unitPrice) }}</span> / {{ row.priceUnit }}</template></el-table-column>
            <el-table-column label="价税口径" width="120"><template #default="{ row }">{{ row.priceIncludesTax ? '含税' : '未税' }} {{ row.taxRate }}%</template></el-table-column>
            <el-table-column label="生效日期" width="120" prop="effectiveDate" />
            <el-table-column label="来源" min-width="170"><template #default="{ row }">{{ sourceName(row.sourceType) }}<div class="muted ellipsis">{{ row.sourceNote || '—' }}</div></template></el-table-column>
            <el-table-column label="变更审计" min-width="190"><template #default="{ row }">{{ row.operatorName }}<div class="muted ellipsis">{{ row.changeReason }}</div></template></el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="价格试算" name="preview">
          <div class="preview-layout">
            <el-form label-width="90px" class="preview-form">
              <el-form-item label="业务类型"><el-radio-group v-model="previewForm.businessType"><el-radio-button value="SALES">销售报价</el-radio-button><el-radio-button value="PURCHASE">采购询价</el-radio-button></el-radio-group></el-form-item>
              <el-form-item label="往来单位"><PartnerRemoteSelect v-model="previewForm.partnerId" :type="previewForm.businessType === 'SALES' ? 'CUSTOMER' : 'SUPPLIER'" /></el-form-item>
              <el-form-item label="商品"><el-select v-model="previewForm.productId" filterable @change="syncPreviewUnit"><el-option v-for="item in products" :key="item.id" :label="`${item.sku} · ${item.name} · ${item.spec}`" :value="item.id" /></el-select></el-form-item>
              <el-form-item label="数量"><div class="quantity-field"><el-input-number v-model="previewForm.quantity" :min="0.000001" :precision="6" :controls="false" /><el-input v-model="previewForm.priceUnit" placeholder="计价单位" /></div></el-form-item>
              <el-form-item label="业务日期"><el-date-picker v-model="previewForm.businessDate" value-format="YYYY-MM-DD" /></el-form-item>
              <el-form-item><el-button type="primary" :loading="previewing" @click="runPreview">试算并解释</el-button></el-form-item>
            </el-form>
            <div class="preview-result" :class="{ empty: !previewResult }">
              <template v-if="previewResult">
                <div class="preview-source"><el-tag>{{ previewResult.sourceName }}</el-tag><span>{{ previewResult.ruleCode }} · V{{ previewResult.ruleVersionNo }}</span></div>
                <div class="preview-price"><strong>{{ money4(previewResult.unitPrice) }}</strong><span>/ {{ previewResult.priceUnit }} · {{ previewResult.priceIncludesTax ? '含税' : '未税' }} {{ previewResult.taxRate }}%</span></div>
                <p>{{ previewResult.explanation }}</p>
                <div class="component-list"><div v-for="component in previewResult.components" :key="component.code"><span>{{ component.name }}</span><strong>{{ money4(component.unitPriceContribution) }}</strong></div></div>
                <el-alert v-for="warning in previewResult.warnings" :key="warning" type="warning" :closable="false" :title="warning" />
              </template>
              <el-empty v-else description="选择客户、商品、数量和日期后试算" />
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </section>

    <el-dialog v-model="benchmarkDialog" title="新增铜基准价版本" width="620px" destroy-on-close>
      <el-alert type="warning" :closable="false" title="基准价版本保存后不可编辑；同一代码同一生效日只能有一个版本。录入错误请用新日期/新代码纠正并说明原因。" />
      <el-form label-width="105px" class="dialog-form">
        <el-form-item label="基准代码"><el-input v-model="benchmarkForm.benchmarkCode" placeholder="如 CU-SPOT" /></el-form-item>
        <el-form-item label="基准名称"><el-input v-model="benchmarkForm.benchmarkName" /></el-form-item>
        <el-form-item label="价格 / 单位"><div class="price-unit-field"><el-input-number v-model="benchmarkForm.unitPrice" :min="0.0001" :precision="4" :controls="false" /><el-input v-model="benchmarkForm.priceUnit" /></div></el-form-item>
        <el-form-item label="价税口径"><el-switch v-model="benchmarkForm.priceIncludesTax" active-text="含税" inactive-text="未税" /><el-input-number v-model="benchmarkForm.taxRate" :min="0" :max="100" :precision="4" class="tax-rate" :controls="false" /><span>%</span></el-form-item>
        <el-form-item label="生效日期"><el-date-picker v-model="benchmarkForm.effectiveDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="来源"><el-select v-model="benchmarkForm.sourceType"><el-option label="手工录入" value="MANUAL" /><el-option label="市场参考" value="MARKET_REFERENCE" /><el-option label="供应商通知" value="SUPPLIER_NOTICE" /></el-select></el-form-item>
        <el-form-item label="来源说明"><el-input v-model="benchmarkForm.sourceNote" placeholder="可记录市场、通知单或人工依据" /></el-form-item>
        <el-form-item label="变更原因" required><el-input v-model="benchmarkForm.changeReason" type="textarea" :rows="2" placeholder="至少4个字" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="benchmarkDialog = false">取消</el-button><el-button type="primary" :loading="savingBenchmark" @click="saveBenchmark">发布版本</el-button></template>
    </el-dialog>

    <el-dialog v-model="ruleDialog" title="新增价格规则版本" width="760px" destroy-on-close>
      <el-alert type="info" :closable="false" title="不允许同一范围的日期和数量阶梯重叠；同一规则代码用于连续版本，若改变商品、范围或计价方式请使用新代码。" />
      <el-form label-width="110px" class="dialog-form rule-form">
        <div class="form-grid">
          <el-form-item label="业务类型"><el-select v-model="ruleForm.businessType" @change="resetRuleScope"><el-option label="销售" value="SALES" /><el-option label="采购" value="PURCHASE" /></el-select></el-form-item>
          <el-form-item label="规则代码"><el-input v-model="ruleForm.ruleCode" placeholder="留空自动生成；续版填原代码" /></el-form-item>
          <el-form-item label="规则名称"><el-input v-model="ruleForm.ruleName" /></el-form-item>
          <el-form-item label="适用范围"><el-select v-model="ruleForm.scopeType" @change="syncAgreementType"><el-option label="标准规则" value="STANDARD" /><el-option v-if="ruleForm.businessType === 'SALES'" label="客户等级" value="CUSTOMER_LEVEL" /><el-option v-if="ruleForm.businessType === 'SALES'" label="指定客户" value="CUSTOMER" /><el-option v-if="ruleForm.businessType === 'PURCHASE'" label="指定供应商" value="SUPPLIER" /></el-select></el-form-item>
          <el-form-item v-if="['CUSTOMER','SUPPLIER'].includes(ruleForm.scopeType)" label="往来单位"><PartnerRemoteSelect v-model="ruleForm.partnerId" :type="ruleForm.scopeType === 'CUSTOMER' ? 'CUSTOMER' : 'SUPPLIER'" /></el-form-item>
          <el-form-item v-if="ruleForm.scopeType === 'CUSTOMER_LEVEL'" label="客户等级"><el-select v-model="ruleForm.customerLevel"><el-option label="普通客户" value="NORMAL" /><el-option label="重要客户" value="IMPORTANT" /><el-option label="核心客户" value="CORE" /></el-select></el-form-item>
          <el-form-item label="价格类型"><el-select v-model="ruleForm.agreementType"><el-option v-if="['STANDARD','CUSTOMER_LEVEL'].includes(ruleForm.scopeType)" label="标准价" value="STANDARD" /><el-option v-if="['CUSTOMER','SUPPLIER'].includes(ruleForm.scopeType)" label="协议价" value="AGREEMENT" /><el-option v-if="ruleForm.scopeType === 'CUSTOMER'" label="一次性/期间特价" value="SPECIAL" /></el-select></el-form-item>
          <el-form-item label="商品"><el-select v-model="ruleForm.productId" filterable @change="syncRuleUnit"><el-option v-for="item in products" :key="item.id" :label="`${item.sku} · ${item.name} · ${item.spec}`" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="计价单位"><el-input v-model="ruleForm.priceUnit" /></el-form-item>
          <el-form-item label="计价方式"><el-radio-group v-model="ruleForm.pricingMode"><el-radio-button value="FORMULA">铜价公式</el-radio-button><el-radio-button value="FIXED">固定单价</el-radio-button></el-radio-group></el-form-item>
          <el-form-item v-if="ruleForm.pricingMode === 'FORMULA'" label="基准价"><el-select v-model="ruleForm.benchmarkCode"><el-option v-for="code in benchmarkCodes" :key="code" :label="code" :value="code" /></el-select></el-form-item>
          <el-form-item v-else label="固定单价"><el-input-number v-model="ruleForm.fixedUnitPrice" :min="0.0001" :precision="4" :controls="false" /></el-form-item>
          <template v-if="ruleForm.pricingMode === 'FORMULA'">
            <el-form-item label="加工费"><el-input-number v-model="ruleForm.processingFee" :min="0" :precision="4" :controls="false" /></el-form-item>
            <el-form-item label="规格加价"><el-input-number v-model="ruleForm.specificationFee" :min="0" :precision="4" :controls="false" /></el-form-item>
            <el-form-item label="包装费"><el-input-number v-model="ruleForm.packagingFee" :min="0" :precision="4" :controls="false" /></el-form-item>
            <el-form-item label="运费"><el-input-number v-model="ruleForm.freightFee" :min="0" :precision="4" :controls="false" /></el-form-item>
          </template>
          <el-form-item label="数量下限"><el-input-number v-model="ruleForm.minimumQuantity" :min="0" :precision="6" :controls="false" /></el-form-item>
          <el-form-item label="数量上限"><el-input-number v-model="ruleForm.maximumQuantity" :min="0" :precision="6" :controls="false" placeholder="留空不限" /></el-form-item>
          <el-form-item label="生效日期"><el-date-picker v-model="ruleForm.effectiveFrom" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item label="失效日期"><el-date-picker v-model="ruleForm.effectiveTo" clearable value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item label="价税口径"><div><el-switch v-model="ruleForm.priceIncludesTax" active-text="含税" inactive-text="未税" /><el-input-number v-model="ruleForm.taxRate" :min="0" :max="100" :precision="4" class="tax-rate" :controls="false" /><span>%</span></div></el-form-item>
          <el-form-item label="变更原因" class="wide"><el-input v-model="ruleForm.changeReason" type="textarea" :rows="2" placeholder="说明协议、特价或规则调整依据，至少4个字" /></el-form-item>
        </div>
      </el-form>
      <template #footer><el-button @click="ruleDialog = false">取消</el-button><el-button type="primary" :loading="savingRule" @click="saveRule">发布规则版本</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, type PriceBenchmark, type PricePreview, type PriceRule, type Product } from '@/api/client'
import PageHeader from '@/components/PageHeader.vue'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import { currentDateText } from '@/api/format'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
const canManage = computed(() => appStore.hasPermission('pricing.manage'))
const activeTab = ref('rules')
const benchmarks = ref<PriceBenchmark[]>([])
const rules = ref<PriceRule[]>([])
const products = ref<Product[]>([])
const loading = ref(false)
const ruleBusinessFilter = ref('ALL')
const ruleKeyword = ref('')
const businessFilters = [{ label: '全部', value: 'ALL' }, { label: '销售', value: 'SALES' }, { label: '采购', value: 'PURCHASE' }]
const benchmarkDialog = ref(false)
const ruleDialog = ref(false)
const savingBenchmark = ref(false)
const savingRule = ref(false)
const previewing = ref(false)
const previewResult = ref<PricePreview>()
const today = currentDateText()

const benchmarkForm = reactive({ benchmarkCode: 'CU-SPOT', benchmarkName: '铜现货基准价', priceUnit: 'kg', unitPrice: 0, priceIncludesTax: true, taxRate: 13, effectiveDate: today, sourceType: 'MARKET_REFERENCE', sourceNote: '', changeReason: '' })
const ruleForm = reactive({ ruleCode: '', ruleName: '', businessType: 'SALES' as 'SALES' | 'PURCHASE', scopeType: 'STANDARD', partnerId: undefined as number | undefined, customerLevel: 'NORMAL', agreementType: 'STANDARD', productId: undefined as number | undefined, priceUnit: 'kg', minimumQuantity: 0, maximumQuantity: undefined as number | undefined, pricingMode: 'FORMULA', benchmarkCode: 'CU-SPOT', fixedUnitPrice: 0, processingFee: 0, specificationFee: 0, packagingFee: 0, freightFee: 0, priceIncludesTax: true, taxRate: 13, effectiveFrom: today, effectiveTo: '' as string | '', changeReason: '' })
const previewForm = reactive({ businessType: 'SALES' as 'SALES' | 'PURCHASE', partnerId: undefined as number | undefined, productId: undefined as number | undefined, quantity: 1, priceUnit: 'kg', businessDate: today })

const latestBenchmark = computed(() => benchmarks.value[0])
const customerRuleCount = computed(() => rules.value.filter((item) => ['CUSTOMER', 'CUSTOMER_LEVEL'].includes(item.scopeType)).length)
const supplierRuleCount = computed(() => rules.value.filter((item) => item.scopeType === 'SUPPLIER').length)
const benchmarkCodes = computed(() => [...new Set(benchmarks.value.map((item) => item.benchmarkCode))])
const filteredRules = computed(() => {
  const keyword = ruleKeyword.value.trim().toLowerCase()
  return rules.value.filter((item) => (ruleBusinessFilter.value === 'ALL' || item.businessType === ruleBusinessFilter.value)
    && (!keyword || [item.ruleCode, item.ruleName, item.productSku, item.productName, item.partnerName].some((value) => String(value || '').toLowerCase().includes(keyword))))
})

onMounted(load)

async function load() {
  loading.value = true
  try { [benchmarks.value, rules.value, products.value] = await Promise.all([api.priceBenchmarks(), api.priceRules(), api.products()]) } finally { loading.value = false }
}
function openBenchmark() { Object.assign(benchmarkForm, { effectiveDate: today, unitPrice: 0, sourceNote: '', changeReason: '' }); benchmarkDialog.value = true }
function openRule() { Object.assign(ruleForm, { ruleCode: '', ruleName: '', partnerId: undefined, productId: undefined, minimumQuantity: 0, maximumQuantity: undefined, effectiveFrom: today, effectiveTo: '', changeReason: '' }); ruleDialog.value = true }
function resetRuleScope() { ruleForm.scopeType = 'STANDARD'; ruleForm.partnerId = undefined; ruleForm.agreementType = 'STANDARD' }
function syncAgreementType() { ruleForm.partnerId = undefined; ruleForm.agreementType = ['CUSTOMER', 'SUPPLIER'].includes(ruleForm.scopeType) ? 'AGREEMENT' : 'STANDARD' }
function syncRuleUnit() { const product = products.value.find((item) => item.id === ruleForm.productId); if (product) ruleForm.priceUnit = product.unit }
function syncPreviewUnit() { const product = products.value.find((item) => item.id === previewForm.productId); if (product) previewForm.priceUnit = product.unit }

async function saveBenchmark() {
  if (!benchmarkForm.benchmarkName || !benchmarkForm.priceUnit || !benchmarkForm.effectiveDate || benchmarkForm.changeReason.trim().length < 4) { ElMessage.warning('请完整填写基准价版本，变更原因至少4个字'); return }
  if (benchmarkForm.unitPrice <= 0) { ElMessage.warning('基准铜价必须大于0'); return }
  savingBenchmark.value = true
  try { await api.createPriceBenchmark(benchmarkForm); ElMessage.success('铜基准价版本已发布'); benchmarkDialog.value = false; await load() } finally { savingBenchmark.value = false }
}
async function saveRule() {
  if (!ruleForm.ruleName || !ruleForm.productId || !ruleForm.priceUnit || !ruleForm.effectiveFrom || ruleForm.changeReason.trim().length < 4) { ElMessage.warning('请完整填写价格规则，变更原因至少4个字'); return }
  if (['CUSTOMER', 'SUPPLIER'].includes(ruleForm.scopeType) && !ruleForm.partnerId) { ElMessage.warning('请选择适用往来单位'); return }
  if (ruleForm.pricingMode === 'FIXED' && ruleForm.fixedUnitPrice <= 0) { ElMessage.warning('固定单价必须大于0；赠品请在销售单中使用赠品标识'); return }
  savingRule.value = true
  try { await api.createPriceRule({ ...ruleForm, effectiveTo: ruleForm.effectiveTo || null }); ElMessage.success('价格规则版本已发布'); ruleDialog.value = false; await load() } finally { savingRule.value = false }
}
async function runPreview() {
  if (!previewForm.productId || !previewForm.priceUnit || previewForm.quantity <= 0) { ElMessage.warning('请选择商品并填写数量和计价单位'); return }
  previewing.value = true
  try { previewResult.value = await api.previewPrice({ ...previewForm, productId: previewForm.productId }) } catch (error) { previewResult.value = undefined; ElMessage.error(error instanceof Error ? error.message : '价格试算失败') } finally { previewing.value = false }
}

function scopeName(row: PriceRule) { if (row.scopeType === 'CUSTOMER') return row.partnerName || '指定客户'; if (row.scopeType === 'SUPPLIER') return row.partnerName || '指定供应商'; if (row.scopeType === 'CUSTOMER_LEVEL') return `${row.customerLevel} 等级`; return '标准规则' }
function scopeTagType(scope: string) { return scope === 'CUSTOMER' ? 'success' : scope === 'SUPPLIER' ? 'warning' : scope === 'CUSTOMER_LEVEL' ? 'primary' : 'info' }
function agreementName(value: string) { return value === 'SPECIAL' ? '客户特价' : value === 'AGREEMENT' ? '协议价' : '标准价' }
function sourceName(value: string) { return value === 'MARKET_REFERENCE' ? '市场参考' : value === 'SUPPLIER_NOTICE' ? '供应商通知' : '手工录入' }
function quantityRange(row: PriceRule) { return row.maximumQuantity == null ? `${row.minimumQuantity} 起` : `${row.minimumQuantity}–${row.maximumQuantity}` }
function ruleFeeTotal(row: PriceRule) { return Number(row.processingFee) + Number(row.specificationFee) + Number(row.packagingFee) + Number(row.freightFee) }
function money4(value?: number | null) { return Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 }) }
</script>

<style scoped>
.price-center-page{display:grid;gap:16px}.summary-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}.summary-card{background:var(--el-bg-color);border:1px solid var(--el-border-color-lighter);border-radius:10px;padding:16px;display:grid;gap:7px}.summary-card span{color:var(--el-text-color-secondary);font-size:13px}.summary-card strong{font-size:22px;color:var(--el-text-color-primary)}.summary-card small{color:var(--el-text-color-secondary);line-height:1.4}.principle-alert{margin:0}.toolbar{display:flex;justify-content:space-between;gap:16px;margin:0 0 14px}.keyword{width:320px}.primary-cell,.price-value{font-weight:650;color:var(--el-text-color-primary)}.muted{color:var(--el-text-color-secondary);font-size:12px}.ellipsis{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.rule-kind{margin-top:5px}.preview-layout{display:grid;grid-template-columns:390px 1fr;gap:28px;padding:16px 8px}.preview-form{border-right:1px solid var(--el-border-color-lighter);padding-right:28px}.quantity-field,.price-unit-field{display:grid;grid-template-columns:1fr 110px;gap:8px;width:100%}.preview-result{border:1px solid var(--el-border-color-lighter);border-radius:10px;padding:22px;min-height:300px}.preview-result.empty{display:grid;place-items:center}.preview-source{display:flex;gap:10px;align-items:center}.preview-price{display:flex;gap:10px;align-items:baseline;margin:20px 0 10px}.preview-price strong{font-size:34px;color:var(--el-color-primary)}.component-list{display:grid;gap:8px;margin:20px 0}.component-list>div{display:flex;justify-content:space-between;padding:9px 12px;background:var(--el-fill-color-light);border-radius:6px}.dialog-form{margin-top:18px}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 14px}.wide{grid-column:1/-1}.tax-rate{width:120px;margin:0 6px 0 16px}@media(max-width:1200px){.summary-grid{grid-template-columns:repeat(2,1fr)}.preview-layout{grid-template-columns:1fr}.preview-form{border-right:0;border-bottom:1px solid var(--el-border-color-lighter)}}
</style>
