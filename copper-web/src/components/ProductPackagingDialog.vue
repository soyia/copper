<template>
  <el-dialog v-model="visible" :title="`商品计量包装特例：${product?.name || ''}`" width="1220px" destroy-on-close>
    <div v-loading="loading">
      <el-alert
        v-if="loadError"
        :title="loadError"
        type="error"
        :closable="false"
        show-icon
      />
      <div v-show="!loadError">
      <el-alert
        :title="sourceDescription"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      />
      <el-alert
        v-for="warning in currentProfile?.warnings ?? []"
        :key="warning"
        :title="warning"
        type="warning"
        :closable="false"
        style="margin-bottom: 8px"
      />

      <div class="packaging-source-chain">
        <el-tag effect="plain">{{ currentProfile?.templateName || '系统默认' }}</el-tag>
        <span>→</span>
        <el-tag effect="plain">{{ currentProfile?.productSeriesName || '未绑定系列' }}</el-tag>
        <span>→</span>
        <el-tag :type="hasOverride ? 'warning' : currentProfile?.sourceLevel === 'LEGACY' ? 'info' : 'success'">
          {{ hasOverride ? `商品特例 ${currentProfile?.overrideFields?.length || 0} 项` : currentProfile?.sourceLevel === 'LEGACY' ? '历史迁移规则' : '无商品特例' }}
        </el-tag>
        <span class="muted">有效版本 v{{ currentProfile?.versionNo || 0 }} · {{ shortHash(currentProfile?.contentHash) }}</span>
        <div class="packaging-source-actions">
          <el-button @click="historyVisible = true">版本记录</el-button>
          <el-button v-if="hasOverride" type="warning" plain @click="restoreInherited">
            恢复系列规则
          </el-button>
        </div>
      </div>

      <div class="panel-title" style="margin: 16px 0 8px">基础计量策略</div>
      <el-alert
        title="库存核算主单位用于库存、成本和金额核算；实重盘、卷、根通常选 kg。盘、卷等是实物包装单位，不要替代 kg 作为库存核算主单位。"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 10px"
      />
      <el-alert
        v-if="actualWeightRule"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom: 10px"
      >
        <template #title>
          <div style="display:flex;align-items:center;justify-content:space-between;gap:16px">
            <span>采购、销售、计价和库存都按 {{ actualWeightRule.childUnit }}；另存“{{ actualWeightRule.parentUnit }}数”，整包限制由实重规则控制。</span>
            <el-button type="primary" plain size="small" @click="applyActualWeightPreset">
              按{{ actualWeightRule.childUnit }}计量、{{ actualWeightRule.parentUnit }}包装
            </el-button>
          </div>
        </template>
      </el-alert>
      <el-table :data="policyRows" border size="small">
        <el-table-column prop="label" label="项目" width="150" />
        <el-table-column label="继承值" min-width="170">
          <template #default="{ row }"><span class="muted">{{ inheritedPolicyValue(row.key) }}</span></template>
        </el-table-column>
        <el-table-column label="商品有效值" min-width="230">
          <template #default="{ row }">
            <el-select
              v-if="row.key !== 'roundingPolicy'"
              :model-value="policyValue(row.key)"
              filterable
              @update:model-value="setPolicyValue(row.key, $event)"
            >
              <el-option v-for="item in policyUnitOptions" :key="item" :label="item" :value="item" />
            </el-select>
            <el-select v-else v-model="form.roundingPolicy">
              <el-option label="不自动调整，直接提示" value="REJECT" />
              <el-option label="向上补足到最小单位" value="ROUND_UP" />
              <el-option label="向下取到最小单位" value="ROUND_DOWN" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="isPolicyOverride(row.key) ? 'warning' : 'success'" size="small">
              {{ isPolicyOverride(row.key) ? '商品特例' : '系列继承' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div class="panel-header" style="padding-left: 0; padding-right: 0">
        <div>
          <div class="panel-title">包装层级</div>
          <div class="muted">这里只维护商品相对系列模板的差异；保存时后台自动提取差异并发布新的有效版本。</div>
        </div>
        <el-button @click="addRule">增加商品特例层级</el-button>
      </div>
      <el-alert
        v-if="hierarchyPaths.length"
        type="info"
        :closable="false"
        style="margin-bottom: 10px"
      >
        <template #title>
          <div>当前层级（表格顺序不影响上下级）</div>
          <div v-for="path in hierarchyPaths" :key="path" class="packaging-path">{{ path }}</div>
        </template>
      </el-alert>
      <el-alert
        v-if="validation.errors.length"
        type="error"
        :closable="false"
        show-icon
        style="margin-bottom: 10px"
      >
        <template #title>
          <div>当前配置有 {{ validation.errors.length }} 项问题，修正后才能发布</div>
          <div v-for="message in validation.policyErrors" :key="message" class="packaging-validation-message">{{ message }}</div>
        </template>
      </el-alert>
      <el-table :data="form.rules" border size="small" max-height="360">
        <el-table-column label="来源" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="ruleState(row).type" size="small">
              {{ ruleState(row).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="包装" width="120">
          <template #default="{ row }">
            <el-select v-model="row.parentUnit" filterable>
              <el-option v-for="item in parentUnitOptions" :key="item" :label="item" :value="item" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="内含包装" width="120">
          <template #default="{ row }">
            <el-select v-model="row.childUnit" filterable>
              <el-option v-for="item in childUnitOptions(row)" :key="item" :label="item" :value="item" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="换算方式" width="130">
          <template #default="{ row }">
            <el-select v-model="row.conversionMode" @change="handleConversionModeChange(row)">
              <el-option label="固定数量" value="FIXED" />
              <el-option label="实际称重" value="ACTUAL_WEIGHT" />
              <el-option label="理论换算" value="THEORETICAL" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="包含下级数" width="125">
          <template #default="{ row }">
            <el-input-number v-model="row.fixedChildQuantity" :disabled="row.conversionMode !== 'FIXED'" :min="0.000001" :precision="6" :controls="false" />
          </template>
        </el-table-column>
        <el-table-column label="订单参考重量" width="140">
          <template #default="{ row }">
            <el-input-number v-model="row.referenceBaseQuantity" :disabled="row.conversionMode === 'FIXED'" :min="0.000001" :precision="6" :controls="false" />
          </template>
        </el-table-column>
        <el-table-column label="可拆" width="62" align="center"><template #default="{ row }"><el-switch v-model="row.breakable" :disabled="row.conversionMode !== 'FIXED'" /></template></el-table-column>
        <el-table-column label="采购" width="62" align="center"><template #default="{ row }"><el-switch v-model="row.purchaseAllowed" /></template></el-table-column>
        <el-table-column label="销售" width="62" align="center"><template #default="{ row }"><el-switch v-model="row.salesAllowed" /></template></el-table-column>
        <el-table-column label="发货" width="62" align="center"><template #default="{ row }"><el-switch v-model="row.outboundAllowed" /></template></el-table-column>
        <el-table-column label="包装数须整数" width="104" align="center"><template #default="{ row }"><el-switch v-model="row.integerOnly" /></template></el-table-column>
        <el-table-column label="校验" min-width="190">
          <template #default="{ $index }">
            <span v-if="!validation.rowErrors[$index]?.length" class="text-success">关系有效</span>
            <div v-for="message in validation.rowErrors[$index]" :key="message" class="text-danger">{{ message }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="72" fixed="right">
          <template #default="{ $index }"><el-button text type="danger" @click="removeRule($index)">移除</el-button></template>
        </el-table-column>
      </el-table>

      <div v-if="removedInheritedRules.length" class="packaging-removed-rules">
        <span class="text-warning">待停用的继承层级：</span>
        <el-tag v-for="rule in removedInheritedRules" :key="rule.ruleKey || rule.parentUnit" type="danger" closable @close="restoreRule(rule)">
          {{ rule.parentUnit }}→{{ rule.childUnit }}（点击 × 恢复）
        </el-tag>
      </div>

      <div class="panel-title" style="margin: 16px 0 8px">包装识别与保质期</div>
      <el-alert
        title="包装条码按商品唯一；扫码入库时自动对应商品和包装。生产日期在每次入库时录入，到期日按生产日期当天起算并固化到库存批次。"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 10px"
      />
      <el-alert
        v-if="!form.rules.length"
        title="当前商品还没有箱、包、盘等包装层级，因此没有可录入的包装条码和保质期。请先点击“增加商品特例层级”，建立包装与下级单位的换算关系。"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom: 10px"
      />
      <el-table :data="form.rules" border size="small" max-height="280">
        <el-table-column prop="parentUnit" label="包装单位" width="130" />
        <el-table-column label="商品包装条码" min-width="220">
          <template #default="{ row }">
            <el-input v-model="packageDefinition(row).barcode" clearable placeholder="没有条码可留空" />
          </template>
        </el-table-column>
        <el-table-column label="保质期" width="150">
          <template #default="{ row }">
            <el-select v-model="packageDefinition(row).shelfLifeMode">
              <el-option label="无保质期" value="NONE" />
              <el-option label="有保质期" value="PERIOD" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="期限" min-width="290">
          <template #default="{ row }">
            <div v-if="packageDefinition(row).shelfLifeMode === 'PERIOD'" class="package-shelf-life-input">
              <el-input-number v-model="packageDefinition(row).shelfLifeValue" :min="1" :precision="0" :controls="false" />
              <el-select v-model="packageDefinition(row).shelfLifeUnit">
                <el-option label="天" value="DAY" />
                <el-option label="月" value="MONTH" />
                <el-option label="年" value="YEAR" />
              </el-select>
            </div>
            <span v-else class="muted">不计算到期日</span>
          </template>
        </el-table-column>
      </el-table>

      <el-form label-width="92px" style="margin-top: 16px">
        <el-form-item label="变更原因" required>
          <el-input v-model="changeReason" maxlength="255" show-word-limit placeholder="例如：此SKU每包20个接头，允许拆包按个出库" />
        </el-form-item>
      </el-form>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="Boolean(loadError) || !currentProfile" @click="save">发布商品特例</el-button>
    </template>
  </el-dialog>

  <RelatedDetailDrawer
    v-model="historyVisible"
    title="计量包装版本记录"
    :subtitle="`${product?.name || '商品'} · 历史版本只读追溯`"
    :trail="[{ label: `商品 ${product?.name || ''}` }, { label: '计量包装版本记录' }]"
    size="820px"
    @breadcrumb="historyVisible = false"
  >
    <el-timeline>
      <el-timeline-item v-for="item in history" :key="item.effectiveRevisionId || item.versionNo" :timestamp="item.createdAt || item.publishedAt">
        <div class="packaging-history-title">
          有效版本 v{{ item.versionNo }}
          <el-tag size="small" effect="plain">{{ sourceLevelName(item.sourceLevel) }}</el-tag>
        </div>
        <div class="muted">{{ item.templateName ? `${item.templateName} v${item.templateRevisionNo || '-'}` : '系统默认' }} · {{ shortHash(item.contentHash) }}</div>
        <div v-if="item.changeReason">原因：{{ item.changeReason }}</div>
      </el-timeline-item>
    </el-timeline>
    <el-empty v-if="!history.length" description="暂无历史版本" />
    <template #footer><el-button @click="historyVisible = false">关闭</el-button></template>
  </RelatedDetailDrawer>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useQuery } from '@tanstack/vue-query'
import {
  api,
  type PackagingHistory,
  type PackageDefinition,
  type PackageDefinitionSave,
  type PackagingProfile,
  type PackagingRule,
  type Product,
} from '@/api/client'
import {
  applyActualWeightPackagingPreset,
  packagingErrorMessage,
  packagingHierarchyPaths,
  validatePackagingConfiguration,
} from '@/utils/packagingValidation'
import RelatedDetailDrawer from '@/components/RelatedDetailDrawer.vue'

type PolicyKey = 'baseUnit' | 'minimumSalesUnit' | 'minimumOutboundUnit' | 'defaultPurchaseUnit' | 'defaultSalesUnit' | 'roundingPolicy'

const props = defineProps<{
  modelValue: boolean
  product?: Pick<Product, 'id' | 'name' | 'unit' | 'productSeriesId'>
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [profile: PackagingProfile]
}>()

const loading = ref(false)
const saving = ref(false)
const currentProfile = ref<PackagingProfile>()
const inheritedProfile = ref<PackagingProfile>()
const history = ref<PackagingHistory[]>([])
const historyVisible = ref(false)
const changeReason = ref('')
const loadError = ref('')
const packageDefinitionDrafts = reactive<Record<string, PackageDefinitionSave>>({})
const form = reactive<Record<PolicyKey, string> & { expectedVersionNo: number; rules: PackagingRule[] }>({
  expectedVersionNo: 0,
  baseUnit: 'kg',
  minimumSalesUnit: 'kg',
  minimumOutboundUnit: 'kg',
  defaultPurchaseUnit: 'kg',
  defaultSalesUnit: 'kg',
  roundingPolicy: 'REJECT',
  rules: [],
})
const policyRows: Array<{ key: PolicyKey; label: string }> = [
  { key: 'baseUnit', label: '库存核算主单位' },
  { key: 'minimumSalesUnit', label: '最小销售单位' },
  { key: 'minimumOutboundUnit', label: '最小发货单位' },
  { key: 'defaultPurchaseUnit', label: '默认采购单位' },
  { key: 'defaultSalesUnit', label: '默认销售单位' },
  { key: 'roundingPolicy', label: '不足最小单位处理' },
]
const { data: units } = useQuery({ queryKey: ['units'], queryFn: api.units })
const { data: series } = useQuery({ queryKey: ['product-series'], queryFn: api.productSeries })
const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})
const activeSeries = computed(() => (series.value ?? []).find((item) => item.id === props.product?.productSeriesId))
const enabledUnits = computed(() => (units.value ?? []).filter((item) => item.status === 'ENABLED'))
const parentUnitOptions = computed(() => uniqueUnits(enabledUnits.value
  .filter((item) => item.unitCategoryId === activeSeries.value?.packageUnitCategoryId)
  .map((item) => item.abbreviation || item.name)))
const baseUnitOptions = computed(() => uniqueUnits([
  props.product?.unit,
  form.baseUnit,
  ...enabledUnits.value
    .filter((item) => item.unitCategoryId === activeSeries.value?.priceUnitCategoryId)
    .map((item) => item.abbreviation || item.name),
]))
const policyUnitOptions = computed(() => uniqueUnits([
  ...baseUnitOptions.value,
  ...parentUnitOptions.value,
  form.minimumSalesUnit,
  form.minimumOutboundUnit,
  form.defaultPurchaseUnit,
  form.defaultSalesUnit,
]))
const validation = computed(() => validatePackagingConfiguration(form, form.rules, parentUnitOptions.value))
const hierarchyPaths = computed(() => packagingHierarchyPaths(form.rules))
const actualWeightRule = computed(() => form.rules.find((rule) =>
  rule.conversionMode === 'ACTUAL_WEIGHT' && rule.parentUnit && rule.childUnit))
const sourceDescription = computed(() => {
  const profile = currentProfile.value
  if (!profile) return '正在读取商品有效计量包装规则。'
  if (profile.sourceLevel === 'LEGACY') {
    return '当前是历史迁移规则。请先将公共规则归并到系列包装模板，历史数据保持原快照不变。'
  }
  if (!hasOverride.value) {
    return `当前完全沿用“${profile.templateName || profile.productSeriesName || '系统默认'}”规则。修改后只保存商品差异，模板公共内容仍可继续升级。`
  }
  return `当前基于“${profile.templateName || profile.productSeriesName || '系统默认'}”并叠加商品特例；历史单据和库存批次继续使用各自不可变快照。`
})
const hasOverride = computed(() => currentProfile.value?.sourceLevel === 'PRODUCT_OVERRIDE' && Boolean(currentProfile.value?.overrideRevisionId))
const removedInheritedRules = computed(() => {
  const currentKeys = new Set(form.rules.map(ruleIdentity))
  return (inheritedProfile.value?.rules ?? []).filter((rule) => !currentKeys.has(ruleIdentity(rule)))
})

watch(
  () => [props.modelValue, props.product?.id] as const,
  async ([opened, productId]) => {
    if (!opened || !productId) return
    loading.value = true
    loadError.value = ''
    changeReason.value = ''
    try {
      const [profile, inherited, rows, definitions] = await Promise.all([
        api.packagingProfile(productId),
        api.inheritedPackagingProfile(productId),
        api.packagingHistory(productId).catch(() => {
          ElMessage.warning('计量包装版本记录暂时无法读取，不影响当前规则编辑')
          return []
        }),
        api.packageDefinitions(productId),
      ])
      currentProfile.value = profile
      inheritedProfile.value = inherited
      history.value = rows
      applyProfile(profile)
      applyPackageDefinitions(definitions)
    } catch (error) {
      const detail = packagingErrorMessage(error, '商品计量包装数据读取失败')
      loadError.value = detail.includes('package-definitions') || detail.includes('No static resource')
        ? '当前后端尚未加载包装条码与保质期功能，请先重启后端服务，再关闭并重新打开本窗口。系统已停止发布，避免计量规则与包装资料只保存一部分。'
        : `商品计量包装数据读取失败：${detail}`
      ElMessage.error(loadError.value)
    } finally {
      loading.value = false
    }
  },
  { immediate: true },
)

function applyProfile(profile: PackagingProfile) {
  form.expectedVersionNo = profile.versionNo
  form.baseUnit = profile.baseUnit
  form.minimumSalesUnit = profile.minimumSalesUnit
  form.minimumOutboundUnit = profile.minimumOutboundUnit
  form.defaultPurchaseUnit = profile.defaultPurchaseUnit
  form.defaultSalesUnit = profile.defaultSalesUnit
  form.roundingPolicy = profile.roundingPolicy
  form.rules = profile.rules.map((item) => ({ ...item }))
}

function applyPackageDefinitions(definitions: PackageDefinition[]) {
  Object.keys(packageDefinitionDrafts).forEach((key) => delete packageDefinitionDrafts[key])
  definitions.forEach((item) => {
    packageDefinitionDrafts[String(item.packageUnit).trim().toLowerCase()] = {
      packageUnit: item.packageUnit,
      barcode: item.barcode || '',
      shelfLifeMode: item.shelfLifeMode || 'NONE',
      shelfLifeValue: item.shelfLifeValue || null,
      shelfLifeUnit: item.shelfLifeUnit || 'DAY',
    }
  })
}

function packageDefinition(rule: PackagingRule): PackageDefinitionSave {
  const key = ruleIdentity(rule)
  if (!packageDefinitionDrafts[key]) {
    packageDefinitionDrafts[key] = {
      packageUnit: rule.parentUnit,
      barcode: '',
      shelfLifeMode: 'NONE',
      shelfLifeValue: null,
      shelfLifeUnit: 'DAY',
    }
  }
  packageDefinitionDrafts[key].packageUnit = rule.parentUnit
  return packageDefinitionDrafts[key]
}

function inheritedPolicyValue(key: PolicyKey) {
  const value = inheritedProfile.value?.[key]
  if (key === 'roundingPolicy') return roundingName(String(value || 'REJECT'))
  return String(value || '-')
}

function policyValue(key: PolicyKey) {
  return form[key]
}

function setPolicyValue(key: PolicyKey, value: string) {
  form[key] = value
}

function childUnitOptions(rule: PackagingRule) {
  return uniqueUnits([...parentUnitOptions.value, ...baseUnitOptions.value])
    .filter((item) => item.toLowerCase() !== String(rule.parentUnit || '').trim().toLowerCase())
}

function isPolicyOverride(key: PolicyKey) {
  return String(form[key] || '') !== String(inheritedProfile.value?.[key] || '')
}

function addRule() {
  form.rules.push({
    ruleKey: `CUSTOM-${Date.now()}`,
    parentUnit: '',
    childUnit: form.baseUnit,
    conversionMode: 'FIXED',
    fixedChildQuantity: 1,
    referenceBaseQuantity: null,
    breakable: true,
    repackable: false,
    purchaseAllowed: true,
    salesAllowed: true,
    outboundAllowed: true,
    integerOnly: true,
    sortNo: (form.rules.length + 1) * 10,
    status: 'ENABLED',
    sourceLevel: 'PRODUCT_OVERRIDE',
    overridden: true,
  })
}

function handleConversionModeChange(rule: PackagingRule) {
  if (rule.conversionMode !== 'FIXED') rule.breakable = false
}

function applyActualWeightPreset() {
  const rule = actualWeightRule.value
  if (!rule || !applyActualWeightPackagingPreset(form, form.rules, rule.parentUnit, rule.childUnit)) return
  ElMessage.success(`已设为采购、销售、计价和库存均按${rule.childUnit}，另记${rule.parentUnit}数；出库按具体整${rule.parentUnit}选择`)
}

async function removeRule(index: number) {
  const rule = form.rules[index]
  const inherited = (inheritedProfile.value?.rules ?? []).some((item) => ruleIdentity(item) === ruleIdentity(rule))
  if (inherited) {
    await ElMessageBox.confirm(
      `移除“${rule.parentUnit}→${rule.childUnit}”将在该商品上停用模板层级，不会修改公共模板。`,
      '确认商品差异',
      { type: 'warning' },
    )
  }
  form.rules.splice(index, 1)
}

function restoreRule(rule: PackagingRule) {
  form.rules.push({ ...rule })
  form.rules.sort((left, right) => Number(left.sortNo || 0) - Number(right.sortNo || 0))
}

function ruleState(rule: PackagingRule): { label: string; type: 'success' | 'warning' } {
  const inherited = (inheritedProfile.value?.rules ?? []).find((item) => ruleIdentity(item) === ruleIdentity(rule))
  if (!inherited) return { label: '商品新增', type: 'warning' }
  if (ruleSignature(inherited) !== ruleSignature(rule)) return { label: '待覆盖', type: 'warning' }
  return { label: '系列继承', type: 'success' }
}

function ruleIdentity(rule: PackagingRule) {
  return String(rule.ruleKey || rule.parentUnit || '').trim().toLowerCase()
}

function ruleSignature(rule: PackagingRule) {
  return JSON.stringify({
    parentUnit: rule.parentUnit,
    childUnit: rule.childUnit,
    conversionMode: rule.conversionMode,
    fixedChildQuantity: rule.fixedChildQuantity,
    referenceBaseQuantity: rule.referenceBaseQuantity,
    breakable: rule.breakable,
    purchaseAllowed: rule.purchaseAllowed,
    salesAllowed: rule.salesAllowed,
    outboundAllowed: rule.outboundAllowed,
    integerOnly: rule.integerOnly,
    sortNo: rule.sortNo,
    status: rule.status,
  })
}

function hasProfileDraftChanges() {
  if (!currentProfile.value) return false
  if (policyRows.some(({ key }) => String(form[key] || '') !== String(currentProfile.value?.[key] || ''))) return true
  if (form.rules.length !== currentProfile.value.rules.length) return true
  const currentRules = new Map(currentProfile.value.rules.map((rule) => [ruleIdentity(rule), ruleSignature(rule)]))
  return form.rules.some((rule) => currentRules.get(ruleIdentity(rule)) !== ruleSignature(rule))
}

async function save() {
  if (!props.product) return
  if (!changeReason.value.trim()) {
    ElMessage.warning('请填写商品特例的变更原因')
    return
  }
  if (!form.baseUnit || !form.minimumSalesUnit || !form.minimumOutboundUnit) {
    ElMessage.warning('请完整填写基础计量策略')
    return
  }
  if (form.rules.some((item) => !item.parentUnit || !item.childUnit)) {
    ElMessage.warning('请完整填写包装层级的上级和下级单位')
    return
  }
  if (!form.rules.length && !hasProfileDraftChanges()) {
    ElMessage.warning('当前没有包装层级，也没有计量策略变更，没有可发布的数据。请先增加包装层级。')
    return
  }
  if (validation.value.errors.length) {
    ElMessage.error(validation.value.errors[0])
    return
  }
  const definitions = form.rules.map((rule) => ({ ...packageDefinition(rule), packageUnit: rule.parentUnit }))
  const barcodes = definitions.map((item) => item.barcode.trim()).filter(Boolean)
  if (new Set(barcodes).size !== barcodes.length) {
    ElMessage.warning('同一商品的包装条码不能重复')
    return
  }
  const incompleteShelfLife = definitions.find((item) => item.shelfLifeMode === 'PERIOD'
    && (!item.shelfLifeValue || !item.shelfLifeUnit))
  if (incompleteShelfLife) {
    ElMessage.warning(`请完整填写“${incompleteShelfLife.packageUnit}”的保质期限`)
    return
  }
  saving.value = true
  try {
    const profile = await api.savePackagingProfile(props.product.id, {
      expectedVersionNo: form.expectedVersionNo,
      baseUnit: form.baseUnit,
      minimumSalesUnit: form.minimumSalesUnit,
      minimumOutboundUnit: form.minimumOutboundUnit,
      defaultPurchaseUnit: form.defaultPurchaseUnit,
      defaultSalesUnit: form.defaultSalesUnit,
      roundingPolicy: form.roundingPolicy as PackagingProfile['roundingPolicy'],
      changeReason: changeReason.value.trim(),
      packageDefinitions: definitions,
      rules: form.rules.map(({ id, productId, versionNo, ...item }) => item),
    })
    applyProfile(profile)
    currentProfile.value = profile
    ElMessage.success(profile.inherited ? '商品已恢复沿用系列规则' : '商品特例已发布，仅影响后续业务')
    emit('saved', profile)
    visible.value = false
  } catch (error) {
    const message = packagingErrorMessage(error, '商品计量包装特例发布失败')
    ElMessage.error(message.includes('已被其他人更新')
      ? '本次发布没有成功，当前窗口中的输入仍然保留。服务端已有更新版本，请先记录当前修改，再关闭并重新打开窗口核对后发布。'
      : message)
  } finally {
    saving.value = false
  }
}

async function restoreInherited() {
  if (!props.product || !currentProfile.value) return
  await ElMessageBox.confirm('恢复后，后续业务将完全沿用系列模板；历史单据和库存批次不变。', '恢复系列规则', { type: 'warning' })
  const reason = changeReason.value.trim() || '恢复沿用系列模板'
  const profile = await api.restorePackagingProfile(props.product.id, {
    expectedVersionNo: currentProfile.value.versionNo,
    changeReason: reason,
  })
  currentProfile.value = profile
  applyProfile(profile)
  ElMessage.success('已恢复系列规则，新版本只影响后续业务')
  emit('saved', profile)
}

function roundingName(value: string) {
  return ({
    REJECT: '不自动调整，直接提示',
    ROUND_UP: '向上补足到最小单位',
    ROUND_DOWN: '向下取到最小单位',
  } as Record<string, string>)[value] || value
}

function sourceLevelName(value?: string) {
  return ({ SYSTEM_DEFAULT: '系统默认', TEMPLATE: '系列模板', PRODUCT_OVERRIDE: '商品特例', LEGACY: '历史迁移' } as Record<string, string>)[value || ''] || value || '未知来源'
}

function shortHash(value?: string) {
  return value ? `校验 ${value.slice(0, 10)}` : '无校验码'
}

function uniqueUnits(values: Array<string | null | undefined>) {
  return [...new Set(values.map((item) => String(item || '').trim()).filter(Boolean))]
}
</script>
