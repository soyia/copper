<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">往来单位</div>
          <div class="panel-subtitle">客户、供应商统一主档，经营风险与最近动态集中查看</div>
        </div>
        <el-button type="primary" @click="openCreate">新增往来单位</el-button>
      </div>

      <div class="list-tools partner-tools">
        <el-input v-model="keyword" clearable placeholder="编号 / 名称 / 联系人 / 电话 / 标签" />
        <el-segmented v-model="filterType" :options="typeOptions" />
        <el-select v-model="statusFilter" style="width: 120px">
          <el-option label="启用" value="ENABLED" />
          <el-option label="停用" value="DISABLED" />
          <el-option label="全部状态" value="ALL" />
        </el-select>
        <el-select v-model="riskFilter" style="width: 140px">
          <el-option label="全部单位" value="ALL" />
          <el-option label="有应收应付" value="DEBT" />
          <el-option label="跟进已到期" value="FOLLOWUP" />
          <el-option label="只看置顶" value="PINNED" />
        </el-select>
        <div class="list-tools-actions"><el-button @click="resetFilters">重置</el-button></div>
      </div>

      <el-table
        :data="filteredPartners"
        class="data-table partner-table"
        size="small"
        border
        show-overflow-tooltip
        max-height="calc(100vh - 275px)"
        :row-class-name="partnerRowClassName"
      >
        <el-table-column label="往来单位" min-width="240" fixed="left">
          <template #default="{ row }">
            <div class="name-cell">
              <div class="name-line">
                <span v-if="row.topFixed" class="pin-mark" title="已置顶">★</span>
                <router-link class="table-link partner-name" :to="`/partners/${row.id}`">{{ row.name }}</router-link>
              </div>
              <div class="meta-line">
                <span class="business-no">{{ row.businessNo }}</span>
                <el-tag v-if="isCustomer(row)" :type="row.customerStatus === 'DISABLED' ? 'info' : 'success'" size="small">
                  客户{{ row.customerStatus === 'DISABLED' ? '已停用' : '' }}
                </el-tag>
                <el-tag v-if="isSupplier(row)" :type="row.supplierStatus === 'DISABLED' ? 'info' : 'warning'" size="small">
                  供应商{{ row.supplierStatus === 'DISABLED' ? '已停用' : '' }}
                </el-tag>
                <el-tag v-if="row.status === 'DISABLED'" type="danger" size="small">主体已停用</el-tag>
                <span v-if="row.aliasName">{{ row.aliasName }}</span>
              </div>
              <div v-if="row.tags || row.primaryAddress" class="subtle-line">
                {{ [row.tags, compactAddress(row.primaryAddress)].filter(Boolean).join(' · ') }}
              </div>
              <div v-if="row.primaryContactName || row.primaryContactPhone" class="subtle-line contact-line">
                {{ [row.primaryContactName, row.primaryContactRole, formatPhone(row.primaryContactPhone)].filter(Boolean).join(' · ') }}
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="客户账款" min-width="170" align="right">
          <template #default="{ row }">
            <template v-if="isCustomer(row)">
              <div class="metric-line">
                <span>应收</span><strong>{{ money(row.customerReceivable) }}</strong>
              </div>
              <div v-if="row.customerOverdue > 0" class="metric-line danger">
                <span>逾期</span><strong>{{ money(row.customerOverdue) }}</strong>
              </div>
              <div class="metric-line subtle-line">
                <span>预收</span><span>{{ money(row.customerAdvance) }}</span>
              </div>
              <div class="metric-line subtle-line">
                <span>可用授信</span><span>{{ money(row.customerAvailableCredit) }}</span>
              </div>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="供应商账款" min-width="160" align="right">
          <template #default="{ row }">
            <template v-if="isSupplier(row)">
              <div class="metric-line">
                <span>应付</span><strong>{{ money(row.supplierPayable) }}</strong>
              </div>
              <div class="metric-line subtle-line">
                <span>预付</span><span>{{ money(row.supplierPrepay) }}</span>
              </div>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="业务与跟进" min-width="220">
          <template #default="{ row }">
            <div v-if="isCustomer(row)">最近销售：{{ formatDate(row.customerLastBusinessDate) }}</div>
            <div v-if="isSupplier(row)">最近采购：{{ formatDate(row.supplierLastBusinessDate) }}</div>
            <div v-if="lastPaymentDate(row)" class="subtle-line">最近收付：{{ formatDate(lastPaymentDate(row)) }}</div>
            <div :class="['subtle-line', { danger: isFollowupOverdue(row) }]">
              下次跟进：{{ formatDate(row.nextFollowDate) }}
            </div>
            <div v-if="row.suppliedProducts" class="subtle-line supplied-products">供应：{{ row.suppliedProducts }}</div>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="72" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click="openEdit(row.id)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="860px" top="5vh" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="108px">
        <el-tabs v-model="formTab">
          <el-tab-pane label="基本资料" name="base">
            <el-row :gutter="18">
              <el-col v-if="editingId" :span="12">
                <el-form-item label="业务编号"><el-input v-model="form.businessNo" disabled /></el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="主体类型">
                  <el-select v-model="form.entityType" style="width: 100%" @change="onEntityTypeChange">
                    <el-option label="企业" value="COMPANY" />
                    <el-option label="个体工商户" value="SOLE_PROPRIETOR" />
                    <el-option label="个人" value="INDIVIDUAL" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="24">
                <el-form-item label="业务角色" required>
                  <el-checkbox-group v-model="roleValues">
                    <el-checkbox :value="1" :disabled="roleLocked(1)">客户</el-checkbox>
                    <el-checkbox :value="2" :disabled="roleLocked(2)">供应商</el-checkbox>
                  </el-checkbox-group>
                  <div v-if="editingId" class="role-help">已有身份不能直接删除；不再开展业务时请在对应资料页停用身份。</div>
                </el-form-item>
              </el-col>
              <el-col :span="12"><el-form-item label="法定全称" prop="name"><el-input v-model="form.name" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="简称"><el-input v-model="form.aliasName" /></el-form-item></el-col>
              <el-col :span="12">
                <el-form-item label="统一信用代码" prop="creditCode">
                  <el-input v-model="form.creditCode" :placeholder="requiresCreditCode ? '企业和个体工商户必填' : '个人主体可不填'" />
                  <div class="field-help">个人主体可不填；企业和个体工商户必填。</div>
                </el-form-item>
              </el-col>
              <el-col :span="12"><el-form-item label="来源"><el-input v-model="form.source" placeholder="转介绍、官网、主动开发等" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="开始合作日期"><el-date-picker v-model="form.cooperationStartDate" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="成立日"><el-date-picker v-model="form.establishmentDate" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item></el-col>
              <el-col :span="24"><el-form-item label="标签"><el-input v-model="form.tags" placeholder="如：重点客户、制冷铜管、华东地区" /></el-form-item></el-col>
            </el-row>

            <el-divider content-position="left">联系人（选填）</el-divider>
            <div class="section-help">先记录当前已知联系人即可，销售、采购、收货、财务等都可以；更多联系人及主要联系人可保存后在详情中维护。</div>
            <el-row :gutter="18">
              <el-col :span="12"><el-form-item label="姓名"><el-input v-model="form.contactPerson" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="职务/用途"><el-input v-model="form.primaryContactRole" placeholder="老板、销售、采购、收货、财务等" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="电话"><el-input v-model="form.phone" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="微信"><el-input v-model="form.wechat" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item></el-col>
              <el-col :span="24"><el-form-item label="主要地址"><el-input v-model="form.address" type="textarea" :rows="2" /></el-form-item></el-col>
            </el-row>

            <el-divider content-position="left">状态与备注</el-divider>
            <el-row :gutter="18">
              <el-col :span="8"><el-form-item label="置顶"><el-switch v-model="form.topFixed" /></el-form-item></el-col>
              <el-col :span="8">
                <el-form-item label="状态"><el-select v-model="form.status"><el-option label="启用" value="ENABLED" /><el-option label="停用" value="DISABLED" /></el-select></el-form-item>
              </el-col>
              <el-col v-if="form.status === 'DISABLED'" :span="24"><el-form-item label="停用原因"><el-input v-model="form.disabledReason" /></el-form-item></el-col>
              <el-col :span="24"><el-form-item label="备注"><el-input v-model="form.memo" type="textarea" :rows="3" /></el-form-item></el-col>
            </el-row>
          </el-tab-pane>

          <el-tab-pane v-if="hasRole(1)" :label="form.customerStatus === 'DISABLED' ? '客户资料（已停用）' : '客户资料'" name="customer">
            <el-row :gutter="18">
              <el-col :span="12"><el-form-item label="客户身份"><el-select v-model="form.customerStatus" style="width: 100%"><el-option label="启用" value="ENABLED" /><el-option label="停用" value="DISABLED" /></el-select></el-form-item></el-col>
              <el-col v-if="form.customerStatus === 'DISABLED'" :span="24"><el-form-item label="停用原因" required><el-input v-model="form.customerDisabledReason" placeholder="如：停止合作、只保留供应商业务" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="客户等级"><el-select v-model="form.customerLevel" style="width: 100%"><el-option label="普通" value="NORMAL" /><el-option label="重点" value="KEY" /><el-option label="观察" value="WATCH" /></el-select></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="结算方式"><el-select v-model="form.customerSettlementMethod" style="width: 100%"><el-option label="一单一结" value="PER_ORDER" /><el-option label="账期结算" value="CREDIT_TERM" /><el-option label="现款现货" value="CASH" /></el-select></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="账期(天)"><el-input-number v-model="form.customerSettlementDays" :min="0" :max="365" style="width: 100%" :controls="false" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="授信额度"><el-input-number v-model="form.creditLimit" :min="0" :precision="2" style="width: 100%" :controls="false" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="发票类型"><el-select v-model="form.customerInvoiceType" style="width: 100%"><el-option label="不开票" value="NONE" /><el-option label="增值税普通发票" value="VAT_NORMAL" /><el-option label="增值税专用发票" value="VAT_SPECIAL" /></el-select></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="默认税率(%)"><el-input-number v-model="form.customerTaxRate" :min="0" :max="100" :precision="2" style="width: 100%" :controls="false" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="默认含税"><el-switch v-model="form.customerTaxIncluded" /></el-form-item></el-col>
              <el-col :span="24"><el-form-item label="常购商品"><el-input v-model="form.preferredProducts" type="textarea" placeholder="后续可与商品主数据建立关系" /></el-form-item></el-col>
            </el-row>
          </el-tab-pane>

          <el-tab-pane v-if="hasRole(2)" :label="form.supplierStatus === 'DISABLED' ? '供应商资料（已停用）' : '供应商资料'" name="supplier">
            <el-row :gutter="18">
              <el-col :span="12"><el-form-item label="供应商身份"><el-select v-model="form.supplierStatus" style="width: 100%"><el-option label="启用" value="ENABLED" /><el-option label="停用" value="DISABLED" /></el-select></el-form-item></el-col>
              <el-col v-if="form.supplierStatus === 'DISABLED'" :span="24"><el-form-item label="停用原因" required><el-input v-model="form.supplierDisabledReason" placeholder="如：停止采购、只保留客户业务" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="结算方式"><el-select v-model="form.supplierSettlementMethod" style="width: 100%"><el-option label="一单一结" value="PER_ORDER" /><el-option label="账期结算" value="CREDIT_TERM" /><el-option label="预付款" value="PREPAY" /></el-select></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="账期(天)"><el-input-number v-model="form.supplierSettlementDays" :min="0" :max="365" style="width: 100%" :controls="false" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="交货周期(天)"><el-input-number v-model="form.leadTimeDays" :min="0" :max="365" style="width: 100%" :controls="false" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="最低采购额"><el-input-number v-model="form.minOrderAmount" :min="0" :precision="2" style="width: 100%" :controls="false" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="发票类型"><el-select v-model="form.supplierInvoiceType" style="width: 100%"><el-option label="不开票" value="NONE" /><el-option label="增值税普通发票" value="VAT_NORMAL" /><el-option label="增值税专用发票" value="VAT_SPECIAL" /></el-select></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="默认税率(%)"><el-input-number v-model="form.supplierTaxRate" :min="0" :max="100" :precision="2" style="width: 100%" :controls="false" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="默认含税"><el-switch v-model="form.supplierTaxIncluded" /></el-form-item></el-col>
              <el-col :span="24"><el-form-item label="资质说明"><el-input v-model="form.qualificationMemo" type="textarea" :rows="3" /></el-form-item></el-col>
            </el-row>
          </el-tab-pane>
        </el-tabs>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button v-if="hasPreviousFormStep" :disabled="saving" @click="goPreviousFormStep">上一步</el-button>
        <el-button type="primary" :loading="saving" @click="handlePrimaryAction">{{ primaryActionLabel }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { api, type Partner, type PartnerSummary } from '@/api/client'
import { formatDate, formatPhone, money } from '@/api/format'
import { matchesKeyword } from '@/utils/listFilters'

const queryClient = useQueryClient()
const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
type FormTab = 'base' | 'customer' | 'supplier'

const formTab = ref<FormTab>('base')
const filterType = ref('ALL')
const statusFilter = ref('ENABLED')
const riskFilter = ref('ALL')
const keyword = ref('')
const editingId = ref<number>()
const roleValues = ref<number[]>([1])
const originalRoleMask = ref(0)

const form = reactive<Partial<Partner>>({})
const requiresCreditCode = computed(() => form.entityType !== 'INDIVIDUAL')
const rules = computed<FormRules>(() => ({
  name: [{ required: true, message: '请填写往来单位法定全称', trigger: 'blur' }],
  creditCode: requiresCreditCode.value
    ? [{ required: true, message: '企业和个体工商户必须填写统一社会信用代码', trigger: 'blur' }]
    : [],
}))
const formSteps = computed<FormTab[]>(() => [
  'base',
  ...(roleValues.value.includes(1) ? ['customer' as const] : []),
  ...(roleValues.value.includes(2) ? ['supplier' as const] : []),
])
const currentFormStepIndex = computed(() => Math.max(formSteps.value.indexOf(formTab.value), 0))
const hasPreviousFormStep = computed(() => currentFormStepIndex.value > 0)
const hasNextFormStep = computed(() => currentFormStepIndex.value < formSteps.value.length - 1)
const primaryActionLabel = computed(() =>
  hasNextFormStep.value || (formTab.value === 'base' && roleValues.value.length === 0) ? '下一步' : '保存',
)
const { data: summaries } = useQuery({ queryKey: ['partner-summaries'], queryFn: () => api.partnerSummaries() })

watch(roleValues, () => {
  if (!formSteps.value.includes(formTab.value)) formTab.value = 'base'
}, { deep: true })

const counts = computed(() => {
  const rows = summaries.value ?? []
  return {
    ALL: rows.length,
    CUSTOMER: rows.filter(isCustomer).length,
    SUPPLIER: rows.filter(isSupplier).length,
  }
})
const typeOptions = computed(() => [
  { label: `全部 ${counts.value.ALL}`, value: 'ALL' },
  { label: `客户 ${counts.value.CUSTOMER}`, value: 'CUSTOMER' },
  { label: `供应商 ${counts.value.SUPPLIER}`, value: 'SUPPLIER' },
])
const filteredPartners = computed(() =>
  (summaries.value ?? []).filter((row) => {
    const roleMatch = filterType.value === 'ALL' || (filterType.value === 'CUSTOMER' ? isCustomer(row) : isSupplier(row))
    const statusMatch = statusFilter.value === 'ALL' || row.status === statusFilter.value
    const keywordMatch = matchesKeyword(row, keyword.value, [
      'businessNo', 'name', 'aliasName', 'tags', 'primaryContactName', 'primaryContactRole',
      'primaryContactPhone', 'primaryContactWechat', 'primaryAddress', 'suppliedProducts',
    ])
    const riskMatch =
      riskFilter.value === 'ALL' ||
      (riskFilter.value === 'DEBT' && (Number(row.customerReceivable) > 0 || Number(row.supplierPayable) > 0)) ||
      (riskFilter.value === 'FOLLOWUP' && isFollowupOverdue(row)) ||
      (riskFilter.value === 'PINNED' && row.topFixed)
    return roleMatch && statusMatch && keywordMatch && riskMatch
  }),
)

const createMutation = useMutation({
  mutationFn: api.createPartner,
  onSuccess: async () => {
    ElMessage.success('往来单位已新增')
    dialogVisible.value = false
    await invalidatePartners()
  },
  onError: (error) => ElMessage.error(mutationErrorMessage(error)),
})
const updateMutation = useMutation({
  mutationFn: ({ id, data }: { id: number; data: Partial<Partner> }) => api.updatePartner(id, data),
  onSuccess: async () => {
    ElMessage.success('往来单位已更新')
    dialogVisible.value = false
    await invalidatePartners()
  },
  onError: (error) => ElMessage.error(mutationErrorMessage(error)),
})

function resetForm() {
  editingId.value = undefined
  originalRoleMask.value = 0
  roleValues.value = [1]
  formTab.value = 'base'
  Object.assign(form, {
    businessNo: '', type: 'CUSTOMER', typeMask: 1, entityType: 'COMPANY', name: '', aliasName: '', creditCode: '',
    contactPerson: '', primaryContactRole: '', phone: '', wechat: '', email: '', address: '', creditLimit: 0,
    topFixed: false, cooperationStartDate: null, establishmentDate: null, source: '', tags: '', status: 'ENABLED',
    disabledReason: '', memo: '', customerStatus: 'ENABLED', customerDisabledReason: '', customerDisabledAt: null,
    customerLevel: 'NORMAL', customerSettlementMethod: 'PER_ORDER',
    customerSettlementDays: 0, customerTaxIncluded: true, customerInvoiceType: 'NONE', customerTaxRate: 0,
    preferredProducts: '', supplierStatus: 'ENABLED', supplierDisabledReason: '', supplierDisabledAt: null,
    supplierSettlementMethod: 'PER_ORDER', supplierSettlementDays: 0, leadTimeDays: 0,
    minOrderAmount: 0, supplierTaxIncluded: true, supplierInvoiceType: 'NONE', supplierTaxRate: 0,
    qualificationMemo: '',
  })
  formRef.value?.clearValidate()
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

async function openEdit(id: number) {
  resetForm()
  const row = await api.partner(id)
  editingId.value = id
  originalRoleMask.value = row.typeMask
  Object.assign(form, row)
  roleValues.value = []
  if ((row.typeMask & 1) === 1) roleValues.value.push(1)
  if ((row.typeMask & 2) === 2) roleValues.value.push(2)
  dialogVisible.value = true
}

async function handlePrimaryAction() {
  if (primaryActionLabel.value === '保存') {
    await save()
    return
  }
  if (!(await validateCurrentFormStep())) return
  const nextStep = formSteps.value[currentFormStepIndex.value + 1]
  if (nextStep) formTab.value = nextStep
}

function goPreviousFormStep() {
  const previousStep = formSteps.value[currentFormStepIndex.value - 1]
  if (previousStep) formTab.value = previousStep
}

async function validateCurrentFormStep() {
  if (formTab.value === 'base') {
    const valid = await formRef.value?.validateField(['name', 'creditCode']).then(() => true).catch(() => false)
    if (!valid) return false
    if (!roleValues.value.length) {
      ElMessage.warning('请选择客户或供应商角色')
      return false
    }
    if (form.status === 'DISABLED' && !form.disabledReason?.trim()) {
      ElMessage.warning('请填写停用原因')
      return false
    }
  }
  if (formTab.value === 'customer' && form.customerStatus === 'DISABLED' && !form.customerDisabledReason?.trim()) {
    ElMessage.warning('请填写客户身份停用原因')
    return false
  }
  return true
}

function onEntityTypeChange() {
  formRef.value?.clearValidate('creditCode')
}

async function save() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (!roleValues.value.length) return ElMessage.warning('请选择客户或供应商角色')
  if (form.status === 'DISABLED' && !form.disabledReason?.trim()) return ElMessage.warning('请填写停用原因')
  const typeMask = roleValues.value.reduce((sum, value) => sum + value, 0)
  if ((typeMask & 1) === 1 && (originalRoleMask.value & 1) === 0) form.customerStatus = 'ENABLED'
  if ((typeMask & 2) === 2 && (originalRoleMask.value & 2) === 0) form.supplierStatus = 'ENABLED'
  if ((typeMask & 1) === 1 && form.customerStatus === 'DISABLED' && !form.customerDisabledReason?.trim()) {
    formTab.value = 'customer'
    return ElMessage.warning('请填写客户身份停用原因')
  }
  if ((typeMask & 2) === 2 && form.supplierStatus === 'DISABLED' && !form.supplierDisabledReason?.trim()) {
    formTab.value = 'supplier'
    return ElMessage.warning('请填写供应商身份停用原因')
  }
  const type: Partner['type'] = typeMask === 3 ? 'BOTH' : typeMask === 2 ? 'SUPPLIER' : 'CUSTOMER'
  const payload = { ...form, typeMask, type }
  if (editingId.value) updateMutation.mutate({ id: editingId.value, data: payload })
  else createMutation.mutate(payload)
}

function resetFilters() {
  keyword.value = ''
  filterType.value = 'ALL'
  statusFilter.value = 'ENABLED'
  riskFilter.value = 'ALL'
}

function hasRole(mask: number) {
  return roleValues.value.includes(mask)
}
function roleLocked(mask: number) {
  return Boolean(editingId.value && (originalRoleMask.value & mask) === mask)
}
function isCustomer(row: PartnerSummary) {
  return (row.typeMask & 1) === 1
}
function isSupplier(row: PartnerSummary) {
  return (row.typeMask & 2) === 2
}
function partnerRowClassName({ row }: { row: PartnerSummary }) {
  return row.status === 'DISABLED' ? 'partner-row-disabled' : ''
}
function lastPaymentDate(row: PartnerSummary) {
  return latestDate(row.customerLastPaymentDate, row.supplierLastPaymentDate)
}
function latestDate(...values: Array<string | null>) {
  const sorted = values.filter((value): value is string => Boolean(value)).sort()
  return sorted[sorted.length - 1] ?? null
}
function isFollowupOverdue(row: PartnerSummary) {
  return Boolean(row.nextFollowDate && row.nextFollowDate <= new Date().toISOString().slice(0, 10))
}
function compactAddress(value: string) {
  return value?.length > 18 ? `${value.slice(0, 18)}…` : value
}
async function invalidatePartners() {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['partner-summaries'] }),
    queryClient.invalidateQueries({ queryKey: ['partners'] }),
  ])
}
function mutationErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : '保存失败'
}
const dialogTitle = computed(() => (editingId.value ? '编辑往来单位' : '新增往来单位'))
const saving = computed(() => createMutation.isPending.value || updateMutation.isPending.value)
</script>

<style scoped>
.panel-subtitle { margin-top: 4px; color: #8b949e; font-size: 12px; }
.partner-tools { grid-template-columns: minmax(230px, 1fr) auto 120px 140px auto; }
.name-cell { min-width: 0; line-height: 1.45; }
.name-line, .meta-line, .metric-line { display: flex; align-items: center; gap: 6px; }
.partner-name { font-weight: 600; }
.pin-mark { color: #e6a23c; }
.business-no { color: #606266; font-variant-numeric: tabular-nums; }
.meta-line, .subtle-line, .muted { color: #909399; font-size: 12px; }
.subtle-line { margin-top: 2px; }
.contact-line { color: #606266; }
.metric-line { justify-content: space-between; }
.danger { color: #d33c32 !important; }
.supplied-products { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 210px; }
.role-help, .field-help, .section-help { color: #909399; font-size: 12px; line-height: 1.5; }
.role-help, .field-help { margin-top: 4px; }
.section-help { margin: -8px 0 16px 108px; }
:deep(.partner-table .partner-row-disabled > td.el-table__cell) { background: #f5f7fa; color: #909399; }
:deep(.partner-table .partner-row-disabled .partner-name) { color: #909399; }
:deep(.partner-table .partner-row-disabled .metric-line strong) { color: #909399; }
@media (max-width: 1100px) {
  .partner-tools { grid-template-columns: 1fr 1fr; }
}
</style>
