<template>
  <el-dialog v-model="visible" :title="templateId ? '发布包装规则新版本' : '新增包装模板'" width="1180px" destroy-on-close>
    <el-alert
      :title="templateId
        ? '这里只修改计量与包装规则并发布新版本。模板名称、说明和启停状态请在列表中单独维护。'
        : '新建模板会同时发布首个规则版本；以后名称、说明和启停状态不会产生规则新版本。'"
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 14px"
    />
    <el-collapse v-if="templateId && revisions.length" class="template-history-collapse">
      <el-collapse-item :title="`版本记录（${revisions.length}）`" name="history">
        <el-table :data="revisions" border size="small" max-height="190">
          <el-table-column label="版本" width="80"><template #default="{ row }">v{{ row.revisionNo }}</template></el-table-column>
          <el-table-column prop="baseUnit" label="库存核算主单位" width="140" />
          <el-table-column prop="changeReason" label="发布原因" min-width="220" show-overflow-tooltip />
          <el-table-column prop="publishedAt" label="发布时间" width="180" />
          <el-table-column label="操作" width="90"><template #default="{ row }"><el-button text type="primary" @click="showRevision(row)">查看</el-button></template></el-table-column>
        </el-table>
      </el-collapse-item>
    </el-collapse>
    <el-form v-loading="loading" label-width="33.333%" class="packaging-policy-form">
      <el-row :gutter="12">
        <el-col :span="8">
          <el-form-item
            label="模板编码"
            required
            :error="form.code && !templateCodeValid ? '只能使用数字、英文字母、短横线和下划线' : ''"
          >
            <el-input
              v-model="form.code"
              :disabled="Boolean(templateId)"
              maxlength="64"
              placeholder="如 FIXED-BOX_01"
              @blur="form.code = form.code.trim()"
            />
          </el-form-item>
        </el-col>
        <el-col :span="8"><el-form-item label="模板名称" required><el-input v-model="form.name" :disabled="Boolean(templateId)" placeholder="如 固定箱件包装" /></el-form-item></el-col>
        <el-col :span="8"><el-form-item label="状态"><el-select v-model="form.status" :disabled="Boolean(templateId)"><el-option label="启用" value="ENABLED" /><el-option label="停用" value="DISABLED" /></el-select></el-form-item></el-col>
        <el-col :span="24"><el-form-item label="用途说明" label-width="11.111%"><el-input v-model="form.description" :disabled="Boolean(templateId)" placeholder="说明适用产品和业务场景" /></el-form-item></el-col>
      </el-row>

      <div class="panel-title" style="margin: 6px 0 10px">基础计量策略</div>
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
            <span>
              采购、销售和计价仍按 {{ actualWeightRule.childUnit }}；包装另记 3{{ actualWeightRule.parentUnit }}、合计 63{{ actualWeightRule.childUnit }}，
              出库选择具体整{{ actualWeightRule.parentUnit }}后按实重扣减。
            </span>
            <el-button type="primary" plain size="small" @click="applyActualWeightPreset">
              按{{ actualWeightRule.childUnit }}计量、{{ actualWeightRule.parentUnit }}包装
            </el-button>
          </div>
        </template>
      </el-alert>
      <el-row :gutter="12">
        <el-col :span="8"><el-form-item label="库存核算主单位" required><unit-select v-model="form.baseUnit" :options="baseUnitOptions" /></el-form-item></el-col>
        <el-col :span="8"><el-form-item label="最小销售单位" required><unit-select v-model="form.minimumSalesUnit" :options="policyUnitOptions" /></el-form-item></el-col>
        <el-col :span="8"><el-form-item label="最小发货单位" required><unit-select v-model="form.minimumOutboundUnit" :options="policyUnitOptions" /></el-form-item></el-col>
        <el-col :span="8"><el-form-item label="默认采购单位" required><unit-select v-model="form.defaultPurchaseUnit" :options="policyUnitOptions" /></el-form-item></el-col>
        <el-col :span="8"><el-form-item label="默认销售单位" required><unit-select v-model="form.defaultSalesUnit" :options="policyUnitOptions" /></el-form-item></el-col>
        <el-col :span="8">
          <el-form-item label="不足最小单位">
            <el-select v-model="form.roundingPolicy">
              <el-option label="不自动调整，直接提示" value="REJECT" />
              <el-option label="向上补足到最小单位" value="ROUND_UP" />
              <el-option label="向下取到最小单位" value="ROUND_DOWN" />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>

    <div class="panel-header" style="padding-left: 0; padding-right: 0">
      <div>
        <div class="panel-title">标准包装层级</div>
        <div class="muted">固定包装填写包含数量；实重包装的参考数只用于订单估算，入库逐件录入实重，后续按原包装整包出库。</div>
      </div>
      <el-button @click="addRule">增加层级</el-button>
    </div>
    <el-alert v-if="hierarchyPaths.length" type="info" :closable="false" style="margin-bottom: 10px">
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
      <el-table-column label="包装" width="125"><template #default="{ row }"><unit-select v-model="row.parentUnit" :options="parentUnitOptions" /></template></el-table-column>
      <el-table-column label="内含包装" width="125"><template #default="{ row }"><unit-select v-model="row.childUnit" :options="childUnitOptions(row)" /></template></el-table-column>
      <el-table-column label="换算方式" width="140"><template #default="{ row }"><el-select v-model="row.conversionMode" @change="handleConversionModeChange(row)"><el-option label="固定数量" value="FIXED" /><el-option label="实际称重" value="ACTUAL_WEIGHT" /><el-option label="理论换算" value="THEORETICAL" /></el-select></template></el-table-column>
      <el-table-column label="包含下级数" width="135"><template #default="{ row }"><el-input-number v-model="row.fixedChildQuantity" :disabled="row.conversionMode !== 'FIXED'" :min="0.000001" :precision="6" :controls="false" /></template></el-table-column>
      <el-table-column label="订单参考重量" width="150"><template #default="{ row }"><el-input-number v-model="row.referenceBaseQuantity" :disabled="row.conversionMode === 'FIXED'" :min="0.000001" :precision="6" :controls="false" /></template></el-table-column>
      <el-table-column label="可拆" width="66" align="center"><template #default="{ row }"><el-switch v-model="row.breakable" :disabled="row.conversionMode !== 'FIXED'" /></template></el-table-column>
      <el-table-column label="采购" width="66" align="center"><template #default="{ row }"><el-switch v-model="row.purchaseAllowed" /></template></el-table-column>
      <el-table-column label="销售" width="66" align="center"><template #default="{ row }"><el-switch v-model="row.salesAllowed" /></template></el-table-column>
      <el-table-column label="发货" width="66" align="center"><template #default="{ row }"><el-switch v-model="row.outboundAllowed" /></template></el-table-column>
      <el-table-column label="包装数须整数" width="104" align="center"><template #default="{ row }"><el-switch v-model="row.integerOnly" /></template></el-table-column>
      <el-table-column label="校验" min-width="190">
        <template #default="{ $index }">
          <span v-if="!validation.rowErrors[$index]?.length" class="text-success">关系有效</span>
          <div v-for="message in validation.rowErrors[$index]" :key="message" class="text-danger">{{ message }}</div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right"><template #default="{ $index }"><el-button text type="danger" @click="form.rules.splice($index, 1)">删除</el-button></template></el-table-column>
    </el-table>

    <el-form label-width="104px" style="margin-top: 16px">
      <el-form-item :label="templateId ? '规则变更原因' : '发布原因'" required><el-input v-model="form.changeReason" maxlength="255" show-word-limit :placeholder="templateId ? '说明本次计量或包装规则的变化' : '说明本次新增原因'" /></el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">{{ templateId ? '发布规则新版' : '创建并发布' }}</el-button>
    </template>
  </el-dialog>

  <RelatedDetailDrawer
    v-model="revisionVisible"
    :title="`包装模板历史版本 v${activeRevisionNo}`"
    :subtitle="`${form.name || '包装模板'} · 历史版本只读且不会被覆盖`"
    :trail="[{ label: `包装模板 ${form.name || ''}` }, { label: `历史版本 v${activeRevisionNo}` }]"
    size="900px"
    @breadcrumb="revisionVisible = false"
  >
    <el-alert title="历史版本只读且不会被覆盖；需要重用时必须复制后发布新版。" type="info" :closable="false" style="margin-bottom: 12px" />
    <el-descriptions :column="3" border size="small">
      <el-descriptions-item label="库存核算主单位">{{ revisionProfile?.baseUnit || '-' }}</el-descriptions-item>
      <el-descriptions-item label="最小销售单位">{{ revisionProfile?.minimumSalesUnit || '-' }}</el-descriptions-item>
      <el-descriptions-item label="最小发货单位">{{ revisionProfile?.minimumOutboundUnit || '-' }}</el-descriptions-item>
      <el-descriptions-item label="默认采购单位">{{ revisionProfile?.defaultPurchaseUnit || '-' }}</el-descriptions-item>
      <el-descriptions-item label="默认销售单位">{{ revisionProfile?.defaultSalesUnit || '-' }}</el-descriptions-item>
      <el-descriptions-item label="规则指纹"><span class="snapshot-hash">{{ revisionProfile?.contentHash || '-' }}</span></el-descriptions-item>
    </el-descriptions>
    <el-table :data="revisionProfile?.rules || []" border size="small" style="margin-top: 12px">
      <el-table-column prop="parentUnit" label="包装" width="130" />
      <el-table-column prop="childUnit" label="内含包装" width="130" />
      <el-table-column prop="conversionMode" label="换算方式" width="140" />
      <el-table-column prop="fixedChildQuantity" label="包含数量" width="120" />
      <el-table-column prop="referenceBaseQuantity" label="参考数量" width="120" />
      <el-table-column label="可拆" width="80"><template #default="{ row }">{{ row.breakable ? '是' : '否' }}</template></el-table-column>
    </el-table>
    <template #footer><el-button @click="revisionVisible = false">关闭</el-button></template>
  </RelatedDetailDrawer>
</template>

<script lang="ts">
import { defineComponent, h } from 'vue'
import { ElOption, ElSelect } from 'element-plus'

const UnitSelect = defineComponent({
  props: { modelValue: { type: String, default: '' }, options: { type: Array as () => string[], default: () => [] } },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () => h(ElSelect, {
      modelValue: props.modelValue,
      filterable: true,
      style: 'width:100%',
      'onUpdate:modelValue': (value: string) => emit('update:modelValue', value),
    }, () => props.options.map((item) => h(ElOption, { key: item, label: item, value: item })))
  },
})

export default { components: { UnitSelect } }
</script>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useQuery } from '@tanstack/vue-query'
import { api, type PackagingProfile, type PackagingRule, type PackagingTemplate, type PackagingTemplateRevision, type PackagingTemplateSave } from '@/api/client'
import {
  applyActualWeightPackagingPreset,
  packagingErrorMessage,
  packagingHierarchyPaths,
  validatePackagingConfiguration,
} from '@/utils/packagingValidation'
import RelatedDetailDrawer from '@/components/RelatedDetailDrawer.vue'

const props = defineProps<{ modelValue: boolean; templateId?: number }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [value: PackagingTemplate] }>()
const loading = ref(false)
const saving = ref(false)
const revisions = ref<PackagingTemplateRevision[]>([])
const revisionVisible = ref(false)
const revisionProfile = ref<PackagingProfile>()
const activeRevisionNo = ref(0)
const { data: units } = useQuery({ queryKey: ['units'], queryFn: api.units })
const { data: series } = useQuery({ queryKey: ['product-series'], queryFn: api.productSeries })
const visible = computed({ get: () => props.modelValue, set: (value) => emit('update:modelValue', value) })
const form = reactive<PackagingTemplateSave>({
  expectedRevisionNo: 0,
  code: '', name: '', description: '', status: 'ENABLED', changeReason: '',
  baseUnit: 'kg', minimumSalesUnit: 'kg', minimumOutboundUnit: 'kg',
  defaultPurchaseUnit: 'kg', defaultSalesUnit: 'kg', roundingPolicy: 'REJECT', rules: [],
})
const enabledUnits = computed(() => (units.value ?? []).filter((item) => item.status === 'ENABLED'))
const packageCategoryIds = computed(() => new Set((series.value ?? [])
  .filter((item) => item.status === 'ENABLED' && item.packageUnitCategoryId > 0)
  .map((item) => item.packageUnitCategoryId)))
const priceCategoryIds = computed(() => new Set((series.value ?? [])
  .filter((item) => item.status === 'ENABLED' && item.priceUnitCategoryId > 0)
  .map((item) => item.priceUnitCategoryId)))
const parentUnitOptions = computed(() => uniqueUnits(enabledUnits.value
  .filter((item) => packageCategoryIds.value.has(item.unitCategoryId))
  .map((item) => item.abbreviation || item.name)))
const baseUnitOptions = computed(() => uniqueUnits([
  form.baseUnit,
  ...enabledUnits.value
    .filter((item) => priceCategoryIds.value.has(item.unitCategoryId))
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
const templateCodeValid = computed(() => /^[A-Za-z0-9_-]+$/.test(form.code.trim()))

watch(() => [props.modelValue, props.templateId] as const, async ([opened, id]) => {
  if (!opened) return
  reset()
  if (!id) return
  loading.value = true
  try {
    const [item, history] = await Promise.all([api.packagingTemplate(id), api.packagingTemplateHistory(id)])
    revisions.value = history
    form.expectedRevisionNo = item.currentRevisionNo
    form.code = item.code
    form.name = item.name
    form.description = item.description || ''
    form.status = item.status || 'ENABLED'
    form.baseUnit = item.baseUnit || 'kg'
    form.minimumSalesUnit = item.minimumSalesUnit || form.baseUnit
    form.minimumOutboundUnit = item.minimumOutboundUnit || form.minimumSalesUnit
    form.defaultPurchaseUnit = item.defaultPurchaseUnit || form.baseUnit
    form.defaultSalesUnit = item.defaultSalesUnit || form.minimumSalesUnit
    form.roundingPolicy = item.roundingPolicy || 'REJECT'
    form.rules = (item.rules ?? []).map((rule) => ({ ...rule }))
  } finally { loading.value = false }
}, { immediate: true })

function reset() {
  Object.assign(form, {
    expectedRevisionNo: 0, code: '', name: '', description: '', status: 'ENABLED', changeReason: '',
    baseUnit: 'kg', minimumSalesUnit: 'kg', minimumOutboundUnit: 'kg',
    defaultPurchaseUnit: 'kg', defaultSalesUnit: 'kg', roundingPolicy: 'REJECT', rules: [],
  })
  revisions.value = []
  revisionProfile.value = undefined
}

async function showRevision(row: PackagingTemplateRevision) {
  if (!props.templateId) return
  activeRevisionNo.value = row.revisionNo
  revisionProfile.value = await api.packagingTemplateRevision(props.templateId, row.revisionId)
  revisionVisible.value = true
}

function addRule() {
  form.rules.push({
    ruleKey: `RULE-${Date.now()}`, parentUnit: '', childUnit: form.baseUnit, conversionMode: 'FIXED',
    fixedChildQuantity: 1, referenceBaseQuantity: null, breakable: true, repackable: false,
    purchaseAllowed: true, salesAllowed: true, outboundAllowed: true, integerOnly: true,
    sortNo: (form.rules.length + 1) * 10, status: 'ENABLED',
  } as PackagingRule)
}

function handleConversionModeChange(rule: PackagingRule) {
  if (rule.conversionMode !== 'FIXED') rule.breakable = false
}

function applyActualWeightPreset() {
  const rule = actualWeightRule.value
  if (!rule || !applyActualWeightPackagingPreset(form, form.rules, rule.parentUnit, rule.childUnit)) return
  ElMessage.success(`已设为采购、销售、计价和库存均按${rule.childUnit}，另记${rule.parentUnit}数；出库按具体整${rule.parentUnit}选择`)
}

function childUnitOptions(rule: PackagingRule) {
  return uniqueUnits([...parentUnitOptions.value, ...baseUnitOptions.value])
    .filter((item) => item.toLowerCase() !== String(rule.parentUnit || '').trim().toLowerCase())
}

function uniqueUnits(values: Array<string | null | undefined>) {
  return [...new Set(values.map((item) => String(item || '').trim()).filter(Boolean))]
}

async function save() {
  if (!form.code.trim() || !form.name.trim() || !form.changeReason.trim()) {
    ElMessage.warning('请填写模板编码、名称和发布原因')
    return
  }
  if (!templateCodeValid.value) {
    ElMessage.warning('模板编码只能使用数字、英文字母、短横线和下划线')
    return
  }
  if (!form.baseUnit || !form.minimumSalesUnit || !form.minimumOutboundUnit) {
    ElMessage.warning('请完整填写基础计量策略')
    return
  }
  if (form.rules.some((item) => !item.parentUnit || !item.childUnit)) {
    ElMessage.warning('请完整填写包装层级')
    return
  }
  if (validation.value.errors.length) {
    ElMessage.error(validation.value.errors[0])
    return
  }
  saving.value = true
  try {
    const payload = { ...form, rules: form.rules.map(({ id, productId, versionNo, ...rule }) => rule) }
    const result = props.templateId
      ? await api.publishPackagingTemplate(props.templateId, payload)
      : await api.createPackagingTemplate(payload)
    ElMessage.success(props.templateId ? '包装规则新版本已发布' : '包装模板已创建并发布')
    emit('saved', result)
    visible.value = false
  } catch (error) {
    ElMessage.error(packagingErrorMessage(error, '包装模板发布失败'))
  } finally { saving.value = false }
}
</script>
