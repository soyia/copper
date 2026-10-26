<template>
  <div class="page">
    <div class="panel">
      <PageHeader :title="isEdit ? '编辑入库草稿' : '新建入库单'" back-to="/inbounds">
        <el-button :loading="saving" :disabled="saving" @click="saveDraft">保存草稿</el-button>
        <el-button type="primary" :loading="saving" :disabled="saving" @click="openPreview">预览并提交审核</el-button>
      </PageHeader>
      <div class="panel-body">
        <el-form ref="formRef" class="entry-form" :model="form" :rules="rules" label-width="86px">
          <el-row :gutter="16">
            <el-col :span="8">
              <el-form-item label="采购订单">
                <el-select v-model="form.purchaseOrderId" clearable filterable @change="applyPurchaseOrder">
                  <el-option
                    v-for="item in candidatePurchaseOrders"
                    :key="item.id"
                    :label="`${item.orderNo} / ${item.supplierName} / 未到 ${quantity(item.totalQuantity - item.receivedQuantity)}`"
                    :value="item.id"
                  />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="供应商" prop="supplierId">
                <PartnerRemoteSelect v-model="form.supplierId" type="SUPPLIER" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="仓库" prop="warehouseId">
                <el-select v-model="form.warehouseId">
                  <el-option v-for="item in warehouses ?? []" :key="item.id" :label="item.name" :value="item.id" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="入库日期" prop="businessDate">
                <el-date-picker v-model="form.businessDate" value-format="YYYY-MM-DD" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="发货日期" prop="sendDate">
                <el-date-picker v-model="form.sendDate" value-format="YYYY-MM-DD" :disabled-date="disableSendDate" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="发货单号">
                <el-input v-model="form.sendNumber" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="来源">
                <el-select v-model="form.sourceType" :disabled="Boolean(form.purchaseOrderId)">
                  <el-option label="无订单" value="NO_ORDER" />
                  <el-option label="电话" value="PHONE" />
                  <el-option label="微信" value="WECHAT" />
                  <el-option label="邮件" value="EMAIL" />
                  <el-option label="口头" value="ORAL" />
                  <el-option label="现场" value="ONSITE" />
                  <el-option label="其他" value="OTHER" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="折扣%">
                <el-input-number v-model="form.discountRate" :min="0" :max="100" :precision="2" :controls="false" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="调整金额">
                <el-input-number v-model="form.adjustmentAmount" :precision="2" :controls="false" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="来源说明">
                <el-input v-model="form.sourceNote" placeholder="电话、微信、邮件等说明" />
              </el-form-item>
            </el-col>
            <el-col :span="16">
              <el-form-item label="备注">
                <el-input v-model="form.memo" />
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>
        <div class="inbound-attachment-create">
          <div>
            <div class="panel-title">发货单图片</div>
            <div class="muted">可上传多张 JPG、PNG 或 WebP，单张不超过 10MB；保存后仍可在入库单详情继续补录。</div>
          </div>
          <el-upload
            v-model:file-list="deliveryNoteFiles"
            action="#"
            accept="image/jpeg,image/png,image/webp"
            list-type="picture-card"
            :auto-upload="false"
            :limit="12"
            multiple
          >
            <span>添加图片</span>
          </el-upload>
        </div>
        <div v-if="savedAttachments.length" class="inbound-saved-attachments">
          <div class="panel-title">已保存图片（{{ savedAttachments.length }}）</div>
          <div class="inbound-saved-attachment-grid">
            <figure v-for="item in savedAttachments" :key="item.id" class="inbound-saved-attachment-card">
              <el-image :src="savedAttachmentUrl(item.id)" :preview-src-list="savedAttachmentPreviewUrls" fit="cover" preview-teleported />
              <figcaption>{{ item.fileName }}</figcaption>
            </figure>
          </div>
          <div class="muted">这些图片已经保存在服务器；本页新增选择的图片会在再次保存时上传。</div>
        </div>
        <el-alert
          v-if="failedAttachmentCount"
          type="error"
          :closable="false"
          show-icon
          class="inbound-attachment-retry"
        >
          <template #title>
            <span>{{ failedAttachmentCount }} 张图片上传失败，文件和表单仍保留在当前页面。</span>
            <el-button type="danger" plain size="small" :loading="saving" @click="retryFailedAttachments">重试失败图片</el-button>
          </template>
        </el-alert>
      </div>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">入库商品明细</div>
        <el-button @click="addItem">增加明细</el-button>
      </div>
      <BarcodeScannerInput :busy="scannerBusy" @scan="queueContinuousScan" />
      <div v-if="scannerMessage" class="inbound-scanner-message" :class="`is-${scannerMessage.type}`" role="status" aria-live="polite">
        {{ scannerMessage.text }}
      </div>
      <el-table :data="form.items" class="data-table" size="small" border show-overflow-tooltip max-height="calc(100vh - 456px)">
        <el-table-column label="包装条码" width="190" fixed="left">
          <template #default="{ row }">
            <el-input
              v-model="row.packageBarcode"
              clearable
              placeholder="扫描或输入后回车"
              @keyup.enter="resolvePackageBarcode(row)"
              @blur="resolvePackageBarcode(row)"
            />
          </template>
        </el-table-column>
        <el-table-column width="240" fixed="left">
          <template #header>商品 <span class="table-required-mark">*</span></template>
          <template #default="{ row }">
            <el-select v-model="row.productId" filterable @change="handleProductChange(row)">
              <el-option
                v-for="item in transactionProductOptions(products ?? [], row.productId)"
                :key="item.id"
                :label="transactionProductOptionLabel(item)"
                :value="item.id"
                :disabled="!isTransactionSelectableProduct(item)"
              />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="批次号" width="150">
          <template #default="{ row }"><el-input v-model="row.batchNo" placeholder="留空自动生成" /></template>
        </el-table-column>
        <el-table-column label="厂家序列" width="140">
          <template #default="{ row }"><el-input v-model="row.serialNumber" /></template>
        </el-table-column>
        <el-table-column width="155">
          <template #header>入库数量 <span class="table-required-mark">*</span></template>
          <template #default="{ row }"><el-input-number v-model="row.quantity" :min="0.000001" :precision="6" :controls="false" @change="handleQuantityChange(row)" /></template>
        </el-table-column>
        <el-table-column width="120">
          <template #header>数量单位 <span class="table-required-mark">*</span></template>
          <template #default="{ row }">
            <el-select v-model="row.quantityUnit" filterable :disabled="!row.productId" @change="handleQuantityUnitChange(row)">
              <el-option v-for="item in packagingUnits(row.packagingProfile, 'PURCHASE')" :key="item" :label="item" :value="item" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="实际库存数量" width="160">
          <template #default="{ row }">
            <div class="inventory-quantity-field">
              <span class="inventory-quantity-value">{{ quantity(row.packagingPlan?.baseQuantity) }}</span>
              <span class="inventory-quantity-unit">{{ row.packagingPlan?.baseUnit || row.packagingProfile?.baseUnit || '-' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column width="180">
          <template #header>逐包装实重 <span v-if="hasActualWeightLines" class="table-required-mark">*</span></template>
          <template #default="{ row }">
            <el-button
              :disabled="!requiresActualBaseQuantity(row.packagingProfile, row.packageUnit)"
              @click="openPackageInstances(row)"
            >{{ packageInstanceLabel(row) }}</el-button>
          </template>
        </el-table-column>
        <el-table-column width="160">
          <template #header>包装数 <span v-if="hasActualWeightLines" class="table-required-mark">*</span></template>
          <template #default="{ row }"><el-input-number v-model="row.packageQuantity" :min="0" :precision="packageQuantityPrecision(row)" :controls="false" @change="handlePackageQuantityChange(row)" /></template>
        </el-table-column>
        <el-table-column width="120">
          <template #header>包装单位 <span v-if="hasActualWeightLines" class="table-required-mark">*</span></template>
          <template #default="{ row }">
            <el-select v-model="row.packageUnit" clearable filterable @change="handlePackageUnitChange(row)">
              <el-option v-for="item in packageUnits(row.packagingProfile)" :key="item" :label="item" :value="item" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column width="120">
          <template #header>计价单位 <span class="table-required-mark">*</span></template>
          <template #default="{ row }">
            <el-select v-model="row.priceUnit" filterable @change="refreshPackagingPlan(row)">
              <el-option v-for="item in packagingUnits(row.packagingProfile, 'PURCHASE')" :key="item" :label="item" :value="item" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column width="170">
          <template #header>成本单价 <span class="table-required-mark">*</span></template>
          <template #default="{ row }"><el-input-number v-model="row.unitCost" :min="0.0001" :precision="4" :controls="false" /></template>
        </el-table-column>
        <el-table-column width="160">
          <template #header>生产日期 <span v-if="hasShelfLifeLines" class="table-required-mark">*</span></template>
          <template #default="{ row }"><el-date-picker v-model="row.productionDate" value-format="YYYY-MM-DD" :disabled-date="disableProductionDate" /></template>
        </el-table-column>
        <el-table-column label="保质期 / 到期日" width="190">
          <template #default="{ row }">
            <div>{{ shelfLifeText(row) }}</div>
            <div v-if="calculatedExpirationDate(row)" class="muted">到期：{{ calculatedExpirationDate(row) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="140" align="right">
          <template #default="{ row }">{{ money(itemAmount(row)) }}</template>
        </el-table-column>
        <el-table-column label="包装/换算建议" min-width="220">
          <template #default="{ row }"><span :class="row.planError ? 'text-danger' : 'muted'">{{ row.planError || row.packagingPlan?.suggestion || '选择商品后计算' }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ $index }"><el-button text type="danger" @click="removeItem($index)">删除</el-button></template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="packageDialogVisible" title="逐件录入实重包装" width="680px" destroy-on-close>
      <el-alert
        type="info"
        :closable="false"
        title="保存草稿时实重可以留空；提交审核前必须全部录入。包装卷号/序号可手工录入或由标签文字识别，二维码不读取。"
        show-icon
      />
      <div class="package-entry-tools">
        <div class="package-recognition-actions">
          <div>
            <strong>宏泰标签拍照识别</strong>
            <div class="muted">每个实物包装拍一张；只生成待核对候选。</div>
          </div>
          <el-button type="primary" plain @click="openPackageLabelRecognition">拍照识别</el-button>
        </div>
        <el-input
          v-model="packageScanCode"
          maxlength="128"
          clearable
          placeholder="手输或扫描标签卷号/序号（不读取二维码）"
          @keyup.enter="applyPackageCodeScan"
        >
          <template #append><el-button @click="applyPackageCodeScan">录入编号</el-button></template>
        </el-input>
        <div class="package-weight-import">
          <el-input
            v-model="bulkWeightText"
            type="textarea"
            :rows="2"
            placeholder="批量粘贴重量，例如：54.96 55.20 53.80（空格、逗号或换行分隔）"
          />
          <el-button @click="applyBulkWeights">填入空白重量</el-button>
        </div>
        <div class="muted">
          已录重量 {{ completedPackageWeightCount }}/{{ editingPackageInstances.length }}；
          包装编号只用于对应实物包装，重量仍以标签或磅单复核值为准。
        </div>
      </div>
      <el-table :data="editingPackageInstances" border size="small" max-height="460" style="margin-top: 14px">
        <el-table-column type="index" label="#" width="56" />
        <el-table-column label="包装码（盘码/卷码等）">
          <template #default="{ row }"><el-input v-model="row.packageCode" maxlength="128" placeholder="卷号/序号；留空自动生成" /></template>
        </el-table-column>
        <el-table-column width="240">
          <template #header>
            实际{{ editingRow?.packagingProfile?.baseUnit || '库存' }}数量
            <span class="table-required-mark">*</span>
          </template>
          <template #default="{ row }">
            <el-input-number
              v-model="row.actualBaseQuantity"
              :min="0.000001"
              :precision="6"
              :controls="false"
              placeholder="草稿可空"
              style="width: 100%"
            />
          </template>
        </el-table-column>
      </el-table>
      <div class="muted" style="margin-top: 12px">合计：{{ quantity(editingActualTotal) }} {{ editingRow?.packagingProfile?.baseUnit }}</div>
      <template #footer>
        <el-button @click="packageDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmPackageInstances">保存录入</el-button>
      </template>
    </el-dialog>

    <PackageLabelRecognitionDialog
      :model-value="packageLabelRecognitionVisible"
      :expected-product-id="Number(editingRow?.productId || 0)"
      :expected-product-label="editingProductLabel"
      :require-same-production-date="true"
      :existing-production-date="editingRow?.productionDate || ''"
      @update:model-value="handlePackageLabelRecognitionVisible"
      @confirm="applyRecognizedInboundLabels"
    />

    <el-dialog v-model="previewVisible" title="提交前核对入库单" width="980px" destroy-on-close>
      <el-alert type="info" :closable="false" title="提交后进入待审核，不能直接修改；审核通过后才增加库存并形成应付。" style="margin-bottom: 14px" />
      <el-descriptions :column="3" border>
        <el-descriptions-item label="供应商">{{ previewSupplierName }}</el-descriptions-item>
        <el-descriptions-item label="仓库">{{ previewWarehouseName }}</el-descriptions-item>
        <el-descriptions-item label="入库日期">{{ form.businessDate }}</el-descriptions-item>
        <el-descriptions-item label="发货日期">{{ form.sendDate || '-' }}</el-descriptions-item>
        <el-descriptions-item label="发货单号">{{ form.sendNumber || '-' }}</el-descriptions-item>
        <el-descriptions-item label="预计金额">{{ money(previewTotalAmount) }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="form.items" border size="small" style="margin-top: 14px" max-height="360">
        <el-table-column label="商品" min-width="220"><template #default="{ row }">{{ previewProductName(row.productId) }}</template></el-table-column>
        <el-table-column label="入库数量" width="150"><template #default="{ row }">{{ quantity(row.quantity) }} {{ row.quantityUnit }}</template></el-table-column>
        <el-table-column label="库存数量" width="150"><template #default="{ row }">{{ quantity(row.packagingPlan?.baseQuantity) }} {{ row.packagingPlan?.baseUnit }}</template></el-table-column>
        <el-table-column label="成本单价" width="150"><template #default="{ row }">{{ money(row.unitCost, 4) }} / {{ row.priceUnit }}</template></el-table-column>
        <el-table-column label="金额" width="140" align="right"><template #default="{ row }">{{ money(itemAmount(row)) }}</template></el-table-column>
      </el-table>
      <div class="preview-attachment-summary">
        <strong>发货单图片：{{ savedAttachments.length + pendingAttachmentCount }} 张</strong>
        <span v-if="savedAttachments.length" class="muted">已保存 {{ savedAttachments.length }} 张</span>
        <span v-if="pendingAttachmentCount" class="muted">本次待上传 {{ pendingAttachmentCount }} 张</span>
      </div>
      <template #footer>
        <el-button @click="previewVisible = false">返回修改</el-button>
        <el-button type="primary" :loading="saving" :disabled="saving" @click="confirmSubmit">确认提交审核</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { roundQuantity, sumQuantities, sameQuantity, kilogramsToBaseQuantity } from '@/utils/quantity'
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules, type UploadUserFile } from 'element-plus'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import PageHeader from '@/components/PageHeader.vue'
import { api, type InboundAttachment, type PackageBarcodeMatch, type PackageDefinition, type PackagingPlan, type PackagingProfile } from '@/api/client'
import { currentDateText, money, quantity } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import BarcodeScannerInput from '@/components/BarcodeScannerInput.vue'
import PackageLabelRecognitionDialog from '@/components/PackageLabelRecognitionDialog.vue'
import { defaultQuantityUnit, packagingUnits, requiresActualBaseQuantity } from '@/utils/packaging'
import {
  isTransactionSelectableProduct,
  productOptionLabel,
  transactionProductOptionLabel,
  transactionProductOptions,
} from '@/utils/productDisplay'
import { useAppStore } from '@/stores/app'
import { sha256File } from '@/utils/fileHash'
import { useSessionDraft } from '@/composables/useSessionDraft'
import { workspaceRouteKey } from '@/stores/workspaceTabs'
import type { PackageLabelOcrResult } from '@/types/packageLabelOcr'

const router = useRouter()
const route = useRoute()
const queryClient = useQueryClient()
const appStore = useAppStore()
const today = currentDateText()
const formRef = ref<FormInstance>()
const isEdit = computed(() => route.name === 'inbound-edit')
const editId = computed(() => isEdit.value ? Number(route.params.id || 0) : 0)
const loadingDraft = ref(false)
const draftWorkspaceKey = workspaceRouteKey(route)
const draftStorageKey = `copper-session-draft:v1:${appStore.session?.userId || 0}:inbound:${editId.value || 'new'}`
const previewVisible = ref(false)
type InboundItemForm = {
  purchaseOrderItemId?: number
  productId?: number
  batchNo: string
  serialNumber: string
  quantity?: number
  quantityUnit: string
  actualBaseQuantity?: number
  packageQuantity: number
  priceUnit: string
  packageUnit: string
  packageBarcode: string
  unitCost?: number
  productionDate: string
  packageInstances: Array<{ packageCode: string; actualBaseQuantity?: number }>
  packagingProfile?: PackagingProfile
  packagingPlan?: PackagingPlan
  expectedEffectiveRevisionId?: number
  planError: string
  packageDefinitions: PackageDefinition[]
  activePackageDefinition?: PackageDefinition
  lastResolvedBarcode?: string
}

const form = reactive({
  purchaseOrderId: undefined as number | undefined,
  supplierId: undefined as number | undefined,
  warehouseId: undefined as number | undefined,
  sourceType: 'NO_ORDER',
  sourceNote: '',
  businessDate: today,
  sendDate: today,
  sendNumber: '',
  discountRate: 0,
  adjustmentAmount: 0,
  memo: '',
  items: [makeItem()],
})
const packageDialogVisible = ref(false)
const packageLabelRecognitionVisible = ref(false)
type PendingAttachmentFile = UploadUserFile & { uploadError?: string }
const deliveryNoteFiles = ref<PendingAttachmentFile[]>([])
const savedAttachments = ref<InboundAttachment[]>([])
const savedAttachmentPreviewUrls = computed(() => savedAttachments.value.map((item) => savedAttachmentUrl(item.id)))
const pendingAttachmentCount = computed(() => deliveryNoteFiles.value.filter((item) => item.raw && item.status !== 'success').length)
const persistedInboundId = ref(0)
const scannerBusy = ref(false)
const scannerMessage = ref<{ type: 'success' | 'error' | 'info'; text: string }>()
const scanQueue: string[] = []
const editingRow = ref<InboundItemForm>()
const editingPackageInstances = ref<Array<{ packageCode: string; actualBaseQuantity?: number }>>([])
const packageScanCode = ref('')
const bulkWeightText = ref('')
const editingActualTotal = computed(() => sumQuantities(editingPackageInstances.value.map((item) => item.actualBaseQuantity)))
const completedPackageWeightCount = computed(() =>
  editingPackageInstances.value.filter((item) => Number(item.actualBaseQuantity || 0) > 0).length)
const editingProductLabel = computed(() => previewProductName(editingRow.value?.productId))
const hasActualWeightLines = computed(() =>
  form.items.some((item) => requiresActualBaseQuantity(item.packagingProfile, item.packageUnit)))
const hasShelfLifeLines = computed(() =>
  form.items.some((item) => item.activePackageDefinition?.shelfLifeMode === 'PERIOD'))

const { clearDraft } = useSessionDraft({
  storageKey: () => draftStorageKey,
  snapshot: () => ({ form: JSON.parse(JSON.stringify(form)) as typeof form, persistedInboundId: persistedInboundId.value }),
  restore: (draft) => {
    Object.assign(form, draft.form)
    form.items = draft.form.items?.length ? draft.form.items.map((item) => ({ ...makeItem(), ...item })) : [makeItem()]
    persistedInboundId.value = Number(draft.persistedInboundId || 0)
  },
  ready: computed(() => !loadingDraft.value),
  label: '入库单',
  confirmRestore: isEdit.value,
  additionalDirty: () => deliveryNoteFiles.value.some((item) => item.status !== 'success'),
  onRestored: () => ElMessage.info('表单已恢复；浏览器刷新前选择但尚未上传的本地图片需要重新选择。'),
  workspaceKey: draftWorkspaceKey,
})
const rules: FormRules = {
  supplierId: [{ required: true, message: '请选择供应商', trigger: 'change' }],
  warehouseId: [{ required: true, message: '请选择仓库', trigger: 'change' }],
  businessDate: [{ required: true, message: '请选择入库日期', trigger: 'change' }],
  sendDate: [{
    validator: (_rule, value, callback) => {
      if (value && form.businessDate && value > form.businessDate) callback(new Error('发货日期不得晚于入库日期'))
      else callback()
    },
    trigger: 'change',
  }],
}

const { data: warehouses } = useQuery({ queryKey: ['warehouses'], queryFn: api.warehouses })
const { data: products } = useQuery({ queryKey: ['products'], queryFn: api.products })
const { data: suppliers } = useQuery({ queryKey: ['partners', 'SUPPLIER'], queryFn: () => api.partners('SUPPLIER') })
const { data: purchaseOrders } = useQuery({ queryKey: ['purchase-orders'], queryFn: api.purchaseOrders })
const candidatePurchaseOrders = computed(() => (purchaseOrders.value ?? []).filter((item) =>
  !['DRAFT', 'CANCELLED'].includes(item.status) && item.receivedQuantity < item.totalQuantity,
))

const saveMutation = useMutation({
  mutationFn: async ({ payload, submitAfterSave }: { payload: unknown; submitAfterSave: boolean }) => {
    const targetId = editId.value || persistedInboundId.value
    const result = targetId ? await api.updateInbound(targetId, payload) : await api.createInbound(payload)
    persistedInboundId.value = result.id
    const { attachmentFailures, uploadedAttachments } = await uploadPendingAttachments(result.id)
    if (submitAfterSave && attachmentFailures === 0) await api.submitInbound(result.id, appStore.currentUserName)
    return { ...result, attachmentFailures, uploadedAttachments, submitted: submitAfterSave && attachmentFailures === 0 }
  },
  onSuccess: async (result) => {
    if (result.attachmentFailures) {
      ElMessage.warning(`草稿已保存，${result.attachmentFailures} 张发货单图片上传失败；为避免资料缺失，本次未提交审核`)
    } else if (result.submitted) {
      ElMessage.success('入库单已提交审核，审核通过后才生成库存和应付')
    } else {
      ElMessage.success(result.uploadedAttachments ? '入库草稿和发货单图片已保存' : '入库草稿已保存')
    }
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['inbounds'] }),
      queryClient.invalidateQueries({ queryKey: ['inbound-detail', result.id] }),
      queryClient.invalidateQueries({ queryKey: ['inbound-attachments', result.id] }),
      queryClient.invalidateQueries({ queryKey: ['purchase-orders'] }),
      queryClient.invalidateQueries({ queryKey: ['payables'] }),
      queryClient.invalidateQueries({ queryKey: ['inventory-batches'] }),
      queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
    ])
    if (!result.attachmentFailures) {
      clearDraft()
      router.push(`/inbounds/${result.id}`)
    }
  },
  onError: (error) => {
    ElMessage.error(requestErrorMessage(error, '入库单保存失败'))
  },
})
const saving = computed(() => saveMutation.isPending.value || loadingDraft.value)
const failedAttachmentCount = computed(() => deliveryNoteFiles.value.filter((item) => item.status === 'fail').length)
const previewSupplierName = computed(() => (suppliers.value ?? []).find((item) => item.id === form.supplierId)?.name || '已选供应商')
const previewWarehouseName = computed(() => (warehouses.value ?? []).find((item) => item.id === form.warehouseId)?.name || '已选仓库')
const previewTotalAmount = computed(() => form.items.reduce((sum, item) => sum + itemAmount(item), 0)
  * (1 - Number(form.discountRate || 0) / 100) + Number(form.adjustmentAmount || 0))

watch(
  () => [route.name, route.params.id] as const,
  () => {
    if (isEdit.value) void loadDraft(editId.value)
  },
  { immediate: true },
)

function addItem() {
  form.items.push(makeItem())
}

function removeItem(index: number) {
  if (form.items.length > 1) form.items.splice(index, 1)
}

async function buildPayload(requireSubmissionComplete = false) {
  if (saving.value) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (form.items.some((item) => !item.productId || !item.quantityUnit || Number(item.quantity || 0) <= 0 || Number(item.unitCost || 0) <= 0)) {
    ElMessage.warning('请检查明细商品、数量和成本单价')
    return undefined
  }
  if (requireSubmissionComplete && form.items.some((item) => requiresActualBaseQuantity(item.packagingProfile, item.packageUnit)
    && (item.packageInstances.length !== Number(item.packageQuantity)
      || item.packageInstances.some((instance) => Number(instance.actualBaseQuantity) <= 0)
      || !sameQuantity(sumQuantities(item.packageInstances.map((instance) => instance.actualBaseQuantity)), item.quantity)))) {
    ElMessage.warning('实重包装必须逐件录入重量，包装条数须等于包装数，重量合计须等于入库业务数量')
    return undefined
  }
  const missingProductionDate = form.items.find((item) => item.activePackageDefinition?.shelfLifeMode === 'PERIOD' && !item.productionDate)
  if (missingProductionDate) {
    ElMessage.warning(`包装“${missingProductionDate.packageUnit}”设置了保质期，请填写生产日期`)
    return undefined
  }
  await Promise.all(form.items.map((item) => refreshPackagingPlan(item)))
  const invalidPlan = form.items.find((item) => item.planError || !item.packagingPlan)
  if (invalidPlan) {
    ElMessage.warning(invalidPlan.planError || '请完成包装换算')
    return undefined
  }
  return {
    ...form,
    items: form.items.map(({
      packagingProfile, packagingPlan, planError, packageDefinitions, activePackageDefinition,
      lastResolvedBarcode, ...item
    }) => item),
  }
}

async function saveDraft() {
  const payload = await buildPayload()
  if (!payload) return
  saveMutation.mutate({ payload, submitAfterSave: false })
}

async function retryFailedAttachments() {
  const payload = await buildPayload()
  if (!payload) return
  saveMutation.mutate({ payload, submitAfterSave: false })
}

async function uploadPendingAttachments(inboundId: number) {
  let attachmentFailures = 0
  let uploadedAttachments = 0
  for (const item of deliveryNoteFiles.value) {
    if (!item.raw || item.status === 'success') continue
    item.status = 'uploading'
    item.uploadError = undefined
    try {
      await api.uploadInboundAttachment(inboundId, item.raw)
      item.status = 'success'
      uploadedAttachments += 1
    } catch (error) {
      if (await attachmentExistsAfterUncertainFailure(inboundId, item.raw, error)) {
        item.status = 'success'
        uploadedAttachments += 1
        continue
      }
      item.status = 'fail'
      item.uploadError = requestErrorMessage(error, '图片上传失败')
      attachmentFailures += 1
    }
  }
  return { attachmentFailures, uploadedAttachments }
}

async function attachmentExistsAfterUncertainFailure(inboundId: number, file: File, error: unknown) {
  const message = requestErrorMessage(error, '')
  if (message.includes('已经上传') || message.includes('重复')) return true
  try {
    const [digest, attachments] = await Promise.all([
      sha256File(file),
      api.inboundAttachments(inboundId),
    ])
    return attachments.some((attachment) => attachment.sha256.toLowerCase() === digest)
  } catch {
    return false
  }
}

async function openPreview() {
  const payload = await buildPayload(true)
  if (!payload) return
  previewVisible.value = true
}

async function confirmSubmit() {
  const payload = await buildPayload(true)
  if (!payload) return
  saveMutation.mutate({ payload, submitAfterSave: true })
}

async function loadDraft(id: number) {
  loadingDraft.value = true
  try {
    const [detail, attachments] = await Promise.all([api.inboundDetail(id), api.inboundAttachments(id)])
    if (!['DRAFT', 'REJECTED'].includes(detail.status)) {
      ElMessage.warning('只有草稿或已拒绝入库单可以修改')
      await router.replace(`/inbounds/${id}`)
      return
    }
    savedAttachments.value = attachments
    form.purchaseOrderId = detail.purchaseOrderId || undefined
    form.supplierId = detail.supplierId
    form.warehouseId = detail.warehouseId
    form.sourceType = detail.sourceType
    form.sourceNote = detail.sourceNote || ''
    form.businessDate = detail.businessDate
    form.sendDate = detail.sendDate || ''
    form.sendNumber = detail.sendNumber || ''
    form.discountRate = Number(detail.discountRate || 0)
    form.adjustmentAmount = Number(detail.adjustmentAmount || 0)
    form.memo = detail.memo || ''
    form.items = detail.items.map((item) => ({
      ...makeItem(),
      purchaseOrderItemId: item.purchaseOrderItemId || undefined,
      productId: item.productId,
      batchNo: item.batchNo || '',
      serialNumber: item.serialNumber || '',
      quantity: Number(item.requestedQuantity ?? item.quantity),
      quantityUnit: item.requestedUnit || item.unit,
      actualBaseQuantity: Number(item.quantity),
      packageQuantity: Number(item.packageQuantity || 0),
      priceUnit: item.priceUnit,
      packageUnit: item.packageUnit || '',
      packageBarcode: item.packageBarcode || '',
      unitCost: Number(item.unitCost),
      productionDate: item.productionDate || '',
      packageInstances: (item.packageInstances ?? []).map((instance) => ({
        packageCode: instance.packageCode || '',
        actualBaseQuantity: Number(instance.actualBaseQuantity) || undefined,
      })),
    }))
    for (const item of form.items) await loadPackagingProfile(item)
  } catch (error) {
    ElMessage.error(requestErrorMessage(error, '入库草稿加载失败'))
  } finally {
    loadingDraft.value = false
  }
}

function savedAttachmentUrl(attachmentId: number) {
  return api.inboundAttachmentUrl(editId.value, attachmentId)
}

function previewProductName(productId?: number) {
  const product = (products.value ?? []).find((item) => item.id === productId)
  return product ? productOptionLabel(product) : '-'
}

async function applyPurchaseOrder(id?: number) {
  if (!id) {
    form.sourceType = 'NO_ORDER'
    form.sourceNote = ''
    for (const item of form.items) {
      item.purchaseOrderItemId = undefined
      item.expectedEffectiveRevisionId = undefined
      item.packagingPlan = undefined
      item.planError = ''
    }
    await Promise.all(form.items.map((item) => refreshPackagingPlan(item)))
    ElMessage.info('已取消采购订单关联，当前商品明细保留为无订单入库')
    return
  }
  const detail = await api.purchaseOrderDetail(id)
  if (form.purchaseOrderId !== id) return
  form.supplierId = detail.supplierId
  form.warehouseId = detail.expectedWarehouseId || undefined
  form.sourceType = 'PURCHASE_ORDER'
  form.sourceNote = detail.orderNo
  form.items = detail.items
    .map((item) => {
      const remainingBase = Math.max(Number(item.quantity) - Number(item.receivedQuantity), 0)
      const requestedQuantity = Number(item.requestedQuantity || item.quantity)
      const baseQuantity = Number(item.quantity || 0)
      const remainingRequested = baseQuantity > 0 ? requestedQuantity * remainingBase / baseQuantity : remainingBase
      return {
      purchaseOrderItemId: item.id,
      productId: item.productId,
      batchNo: '',
      serialNumber: '',
      quantity: remainingRequested,
      quantityUnit: item.requestedUnit || item.unit || 'kg',
      actualBaseQuantity: undefined,
      packageQuantity: remainingBase === baseQuantity ? Number(item.packageQuantity || 0) : 0,
      priceUnit: item.priceUnit || 'kg',
      packageUnit: item.packageUnit || '',
      packageBarcode: '',
      unitCost: Number(item.unitPrice),
      productionDate: '',
      packageInstances: [],
      packagingProfile: undefined,
      packagingPlan: undefined,
      expectedEffectiveRevisionId: item.packagingRevisionId || undefined,
      planError: '',
      packageDefinitions: [],
    }
    })
    .filter((item) => item.quantity > 0)
  if (form.items.length === 0) {
    form.items = [makeItem()]
  }
  await Promise.all(form.items.filter((item) => item.productId).map((item) => loadPackagingProfile(item)))
}

function makeItem(): InboundItemForm {
  return {
    purchaseOrderItemId: undefined,
    productId: undefined,
    batchNo: '',
    serialNumber: '',
    quantity: undefined,
    quantityUnit: '',
    actualBaseQuantity: undefined,
    packageQuantity: 0,
    priceUnit: '',
    packageUnit: '',
    packageBarcode: '',
    unitCost: undefined,
    productionDate: '',
    packageInstances: [],
    packagingProfile: undefined,
    packagingPlan: undefined,
    expectedEffectiveRevisionId: undefined,
    planError: '',
    packageDefinitions: [],
  }
}

function packageUnits(profile?: PackagingProfile) {
  if (!profile) return []
  return [...new Set(profile.rules.map((item) => item.parentUnit))]
}

async function handleProductChange(row: InboundItemForm) {
  if (!row.productId) return
  row.quantityUnit = ''
  row.priceUnit = ''
  row.actualBaseQuantity = undefined
  row.packageQuantity = 0
  row.packageUnit = ''
  row.packageBarcode = ''
  row.packageInstances = []
  row.packagingProfile = undefined
  row.packagingPlan = undefined
  row.packageDefinitions = []
  row.activePackageDefinition = undefined
  await loadPackagingProfile(row)
}

async function loadPackagingProfile(row: InboundItemForm) {
  if (!row.productId) return
  const productId = row.productId
  const [profile, definitions] = await Promise.all([
    api.packagingProfile(productId),
    api.packageDefinitions(productId),
  ])
  if (row.productId !== productId) return
  row.packagingProfile = profile
  row.packageDefinitions = definitions
  if (!row.quantityUnit) row.quantityUnit = defaultQuantityUnit(profile, 'PURCHASE')
  if (!row.priceUnit) row.priceUnit = profile.baseUnit
  if (row.packageUnit) handlePackageUnitChange(row)
  await refreshPackagingPlan(row)
}

function handlePackageUnitChange(row: InboundItemForm) {
  const definition = row.packageDefinitions.find((item) => item.packageUnit === row.packageUnit)
  row.activePackageDefinition = definition
  if (definition?.barcode) row.packageBarcode = definition.barcode
  if (requiresActualBaseQuantity(row.packagingProfile, row.packageUnit)) {
    syncPackageInstanceCount(row)
  } else {
    row.packageInstances = []
  }
}

async function resolvePackageBarcode(row: InboundItemForm) {
  const barcode = row.packageBarcode.trim()
  if (!barcode || row.lastResolvedBarcode === barcode) return
  try {
    const match = await api.packageBarcode(barcode)
    await applyBarcodeMatch(row, match, barcode)
    ElMessage.success(`已识别：${match.productName} / ${match.packageUnit}`)
  } catch (error) {
    row.lastResolvedBarcode = undefined
    ElMessage.error(requestErrorMessage(error))
  }
}

function queueContinuousScan(barcode: string) {
  scanQueue.push(barcode)
  if (!scannerBusy.value) void processScanQueue()
}

async function processScanQueue() {
  scannerBusy.value = true
  while (scanQueue.length) {
    const barcode = scanQueue.shift()
    if (!barcode) continue
    try {
      const match = await api.packageBarcode(barcode)
      const existing = form.items.find((item) => item.productId === match.productId && item.packageBarcode === match.barcode)
      if (existing) {
        existing.packageQuantity = Number(existing.packageQuantity || 0) + 1
        if (requiresActualBaseQuantity(existing.packagingProfile, existing.packageUnit)) {
          syncPackageInstanceCount(existing)
        }
        await refreshPackagingPlan(existing)
        scannerMessage.value = {
          type: 'info',
          text: `重复扫描 ${match.barcode}：${match.productName} / ${match.packageUnit} 包装数已累加为 ${quantity(existing.packageQuantity)}。`,
        }
        continue
      }
      const target = form.items.find((item) => !item.productId && !item.quantity) ?? makeAndAppendItem()
      await applyBarcodeMatch(target, match, barcode)
      scannerMessage.value = {
        type: 'success',
        text: `已识别 ${match.barcode}：${match.productName} / ${match.packageUnit}，已加入入库明细。`,
      }
    } catch (error) {
      scannerMessage.value = { type: 'error', text: `${barcode}：${requestErrorMessage(error, '包装条码识别失败')}` }
    }
  }
  scannerBusy.value = false
}

function makeAndAppendItem() {
  const item = makeItem()
  form.items.push(item)
  return item
}

async function applyBarcodeMatch(row: InboundItemForm, match: PackageBarcodeMatch, scannedBarcode: string) {
  row.productId = match.productId
  row.packageUnit = match.packageUnit
  row.packageBarcode = match.barcode
  row.lastResolvedBarcode = scannedBarcode
  row.packagingProfile = undefined
  row.packagingPlan = undefined
  row.packageQuantity = Number(row.packageQuantity || 0) || 1
  await loadPackagingProfile(row)
  row.activePackageDefinition = row.packageDefinitions.find((item) => item.packageUnit === match.packageUnit) || match
}

function handleQuantityUnitChange(row: InboundItemForm) {
  row.actualBaseQuantity = undefined
  void refreshPackagingPlan(row)
}

function handleQuantityChange(row: InboundItemForm) {
  void refreshPackagingPlan(row)
}

function handlePackageQuantityChange(row: InboundItemForm) {
  if (requiresActualBaseQuantity(row.packagingProfile, row.packageUnit)) syncPackageInstanceCount(row)
}

function syncPackageInstanceCount(row: InboundItemForm) {
  const count = Number.isInteger(Number(row.packageQuantity)) ? Math.max(Number(row.packageQuantity), 0) : 0
  row.packageInstances = Array.from({ length: count }, (_, index) => row.packageInstances[index] || {
    packageCode: '',
    actualBaseQuantity: undefined,
  })
}

function applyPackageInstanceTotal(row: InboundItemForm) {
  const total = sumQuantities(row.packageInstances.map((item) => item.actualBaseQuantity))
  const complete = row.packageInstances.length > 0
    && row.packageInstances.every((item) => Number(item.actualBaseQuantity || 0) > 0)
  row.actualBaseQuantity = complete ? total : undefined
  row.packageQuantity = row.packageInstances.length
  if (complete && row.packagingProfile && row.quantityUnit === row.packagingProfile.baseUnit) {
    row.quantity = roundQuantity(total)
  }
}

function openPackageInstances(row: InboundItemForm) {
  syncPackageInstanceCount(row)
  editingRow.value = row
  editingPackageInstances.value = row.packageInstances.map((item) => ({ ...item }))
  packageScanCode.value = ''
  bulkWeightText.value = ''
  packageDialogVisible.value = true
}

function confirmPackageInstances() {
  const row = editingRow.value
  if (!row) return
  const codes = editingPackageInstances.value.map((item) => item.packageCode.trim()).filter(Boolean)
  if (new Set(codes).size !== codes.length) {
    ElMessage.warning('包装码不能重复')
    return
  }
  row.packageInstances = editingPackageInstances.value.map((item) => ({ ...item, packageCode: item.packageCode.trim() }))
  applyPackageInstanceTotal(row)
  packageDialogVisible.value = false
  if (row.packageInstances.some((item) => Number(item.actualBaseQuantity || 0) <= 0)) {
    ElMessage.info('逐包装实重录入进度已保留；保存草稿不受影响，提交审核前需补齐')
  }
  void refreshPackagingPlan(row)
}

function openPackageLabelRecognition() {
  if (!editingRow.value?.productId) {
    ElMessage.warning('请先选择商品')
    return
  }
  packageDialogVisible.value = false
  void nextTick(() => {
    packageLabelRecognitionVisible.value = true
  })
}

function handlePackageLabelRecognitionVisible(value: boolean) {
  packageLabelRecognitionVisible.value = value
  if (!value && editingRow.value) {
    void nextTick(() => {
      packageDialogVisible.value = true
    })
  }
}

function applyRecognizedInboundLabels(results: PackageLabelOcrResult[]) {
  const existingCodes = new Set(
    editingPackageInstances.value.map((item) => item.packageCode.trim()).filter(Boolean),
  )
  const duplicate = results.find((result) => existingCodes.has(result.packageCode.trim()))
  if (duplicate) {
    ElMessage.warning(`包装编号 ${duplicate.packageCode} 已在当前明细中，未重复套用`)
    return
  }
  try {
    results.forEach((result) => kilogramsToBaseQuantity(result.actualWeightKg, editingRow.value?.packagingProfile?.baseUnit || ''))
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : '标签重量单位无法换算，请人工录入')
    return
  }
  const updated = editingPackageInstances.value.map((item) => ({ ...item }))
  for (const result of results) {
    const emptyIndex = updated.findIndex((item) =>
      !item.packageCode.trim() && Number(item.actualBaseQuantity || 0) <= 0)
    const next = {
      packageCode: result.packageCode.trim(),
      actualBaseQuantity: kilogramsToBaseQuantity(result.actualWeightKg, editingRow.value?.packagingProfile?.baseUnit || ''),
    }
    if (emptyIndex >= 0) updated[emptyIndex] = next
    else updated.push(next)
  }
  editingPackageInstances.value = updated
  const recognizedDate = results.map((item) => item.productionDate).find(Boolean)
  if (editingRow.value && recognizedDate && !editingRow.value.productionDate) {
    editingRow.value.productionDate = recognizedDate
  }
  ElMessage.success(`已套用 ${results.length} 个经人工确认的包装标签；请继续核对后保存录入`)
}

function applyPackageCodeScan() {
  const code = packageScanCode.value.trim()
  if (!code) return
  if (editingPackageInstances.value.some((item) => item.packageCode.trim() === code)) {
    ElMessage.warning(`包装码 ${code} 已经录入`)
    packageScanCode.value = ''
    return
  }
  const index = editingPackageInstances.value.findIndex((item) => !item.packageCode.trim())
  if (index < 0) {
    ElMessage.warning('当前包装数对应的包装码已经全部录入；如实物更多，请先调整包装数')
    return
  }
  editingPackageInstances.value[index].packageCode = code
  packageScanCode.value = ''
  ElMessage.success(`已录入第 ${index + 1} 个包装标签`)
}

function applyBulkWeights() {
  const tokens = bulkWeightText.value.trim().split(/[\s,，;；]+/).filter(Boolean)
  if (!tokens.length) return
  const invalid = tokens.find((token) => !/^\d+(?:\.\d{1,6})?$/.test(token) || Number(token) <= 0)
  if (invalid) {
    ElMessage.warning(`重量“${invalid}”格式不正确，只允许正数且最多六位小数`)
    return
  }
  const targets = editingPackageInstances.value
    .map((item, index) => ({ item, index }))
    .filter(({ item }) => Number(item.actualBaseQuantity || 0) <= 0)
  if (tokens.length > targets.length) {
    ElMessage.warning(`粘贴了 ${tokens.length} 个重量，但当前只有 ${targets.length} 个空白位置`)
    return
  }
  tokens.forEach((token, index) => {
    targets[index].item.actualBaseQuantity = roundQuantity(token)
  })
  bulkWeightText.value = ''
  ElMessage.success(`已批量填入 ${tokens.length} 个重量`)
}

function packageInstanceLabel(row: InboundItemForm) {
  if (!requiresActualBaseQuantity(row.packagingProfile, row.packageUnit)) return '无需录入'
  const total = sumQuantities(row.packageInstances.map((item) => item.actualBaseQuantity))
  const completed = row.packageInstances.filter((item) => Number(item.actualBaseQuantity || 0) > 0).length
  return row.packageInstances.length
    ? `${row.packageInstances.length}${row.packageUnit} · 已录${completed}/${row.packageInstances.length} · ${quantity(total)}${row.packagingProfile?.baseUnit || ''}`
    : '逐件录入'
}

function packageQuantityPrecision(row: InboundItemForm) {
  return row.packagingProfile?.rules.find((rule) => rule.parentUnit === row.packageUnit)?.integerOnly ? 0 : 6
}

async function refreshPackagingPlan(row: InboundItemForm) {
  if (!row.productId || !row.quantityUnit || Number(row.quantity || 0) <= 0) return
  const productId = row.productId
  try {
    const plan = await api.packagingPlan(productId, {
      quantity: Number(row.quantity),
      quantityUnit: row.quantityUnit,
      actualBaseQuantity: row.actualBaseQuantity,
      priceUnit: row.priceUnit || row.packagingProfile?.baseUnit,
      purpose: 'INBOUND',
    })
    if (row.productId !== productId) return
    row.packagingPlan = plan
    row.expectedEffectiveRevisionId = plan.effectiveRevisionId ?? undefined
    row.planError = ''
  } catch (error) {
    row.packagingPlan = undefined
    row.expectedEffectiveRevisionId = undefined
    row.planError = requestErrorMessage(error)
  }
}

function itemAmount(row: InboundItemForm) {
  return Number(row.packagingPlan?.pricingQuantity || row.quantity || 0) * Number(row.unitCost || 0)
}

function shelfLifeText(row: InboundItemForm) {
  const definition = row.activePackageDefinition
  if (!definition || definition.shelfLifeMode !== 'PERIOD') return '无保质期'
  const unit = ({ DAY: '天', MONTH: '月', YEAR: '年' } as Record<string, string>)[definition.shelfLifeUnit || ''] || ''
  return `保质 ${definition.shelfLifeValue || '-'} ${unit}`
}

function calculatedExpirationDate(row: InboundItemForm) {
  const definition = row.activePackageDefinition
  if (!row.productionDate || definition?.shelfLifeMode !== 'PERIOD' || !definition.shelfLifeValue || !definition.shelfLifeUnit) return ''
  return addShelfLifeInclusive(row.productionDate, definition.shelfLifeValue, definition.shelfLifeUnit)
}

function addShelfLifeInclusive(dateText: string, value: number, unit: string) {
  const [year, month, day] = dateText.split('-').map(Number)
  let targetYear = year
  let targetMonth = month
  if (unit === 'MONTH') {
    const index = year * 12 + (month - 1) + value
    targetYear = Math.floor(index / 12)
    targetMonth = index % 12 + 1
  } else if (unit === 'YEAR') {
    targetYear += value
  }
  let target: Date
  if (unit === 'DAY') {
    target = new Date(Date.UTC(year, month - 1, day + value - 1))
  } else {
    const lastDay = new Date(Date.UTC(targetYear, targetMonth, 0)).getUTCDate()
    target = new Date(Date.UTC(targetYear, targetMonth - 1, Math.min(day, lastDay) - 1))
  }
  return `${target.getUTCFullYear()}-${String(target.getUTCMonth() + 1).padStart(2, '0')}-${String(target.getUTCDate()).padStart(2, '0')}`
}

function disableSendDate(date: Date) {
  return Boolean(form.businessDate && localDateText(date) > form.businessDate)
}

function disableProductionDate(date: Date) {
  return Boolean(form.businessDate && localDateText(date) > form.businessDate)
}

function localDateText(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

function requestErrorMessage(error: unknown, fallback = '包装换算失败') {
  const responseMessage = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  return responseMessage || (error instanceof Error ? error.message : fallback)
}
</script>

<style scoped>
.inbound-scanner-message {
  padding: 7px 14px;
  color: var(--el-color-primary-dark-2);
  background: var(--el-color-primary-light-9);
  border-bottom: 1px solid var(--el-border-color-light);
  font-size: 13px;
}

.inbound-scanner-message.is-error {
  color: var(--el-color-danger-dark-2);
  background: var(--el-color-danger-light-9);
}

.inbound-scanner-message.is-info {
  color: var(--el-color-warning-dark-2);
  background: var(--el-color-warning-light-9);
}
</style>

<style scoped>
.inbound-attachment-create {
  display: grid;
  grid-template-columns: minmax(260px, 360px) 1fr;
  gap: 18px;
  align-items: start;
  padding-top: 14px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.inbound-attachment-retry {
  margin-top: 12px;
}

.inbound-attachment-retry :deep(.el-alert__title) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

.inbound-saved-attachments {
  margin-top: 14px;
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
}

.inbound-saved-attachment-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(132px, 1fr));
  gap: 12px;
  margin: 10px 0;
}

.inbound-saved-attachment-card {
  min-width: 0;
  margin: 0;
}

.inbound-saved-attachment-card :deep(.el-image) {
  width: 100%;
  height: 104px;
  border-radius: 6px;
  cursor: zoom-in;
}

.inbound-saved-attachment-card figcaption {
  margin-top: 5px;
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inventory-quantity-field {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  min-height: 32px;
  padding: 0 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  background: var(--el-disabled-bg-color);
}

.inventory-quantity-value {
  color: var(--el-text-color-regular);
  font-variant-numeric: tabular-nums;
}

.inventory-quantity-unit {
  color: var(--el-text-color-secondary);
}

.preview-attachment-summary {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-top: 12px;
}

.package-entry-tools {
  display: grid;
  gap: 10px;
  margin-top: 14px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-extra-light);
}

.package-weight-import {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  align-items: end;
}

.package-recognition-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

@media (max-width: 960px) {
  .inbound-attachment-create { grid-template-columns: 1fr; }
  .package-weight-import { grid-template-columns: 1fr; }
}
</style>
