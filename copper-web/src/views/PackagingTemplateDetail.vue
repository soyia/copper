<template>
  <div class="page">
    <div class="panel">
      <PageHeader :title="pageTitle" :back-to="backTo">
        <el-button v-if="templateDetail" @click="openEdit">编辑</el-button>
        <el-button
          v-if="templateDetail"
          type="primary"
          :disabled="templateDetail.status !== 'ENABLED'"
          @click="publishDialogVisible = true"
        >
          发布规则新版
        </el-button>
      </PageHeader>

      <el-skeleton v-if="templatePending" :rows="8" animated />
      <el-result
        v-else-if="templateError || !templateDetail"
        icon="warning"
        title="包装模板不存在或无权查看"
      >
        <template #extra><el-button @click="router.push(backTo)">返回计量与包装</el-button></template>
      </el-result>

      <div v-else class="template-detail-content">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="模板编码">{{ templateDetail.code }}</el-descriptions-item>
          <el-descriptions-item label="模板名称">{{ templateDetail.name }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="templateDetail.status === 'ENABLED' ? 'success' : 'info'">
              {{ templateDetail.status === 'ENABLED' ? '启用' : '停用' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="当前版本">v{{ templateDetail.currentRevisionNo }}</el-descriptions-item>
          <el-descriptions-item label="主库存单位">{{ templateDetail.baseUnit }}</el-descriptions-item>
          <el-descriptions-item label="应用范围">
            {{ associatedBindings.length }} 个系列 / {{ templateDetail.productCount || 0 }} 个商品
          </el-descriptions-item>
          <el-descriptions-item label="用途说明" :span="3">
            {{ templateDetail.description || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="最后更新" :span="3">
            {{ formatDateTime(templateDetail.updatedAt) }}
          </el-descriptions-item>
        </el-descriptions>

        <section class="template-detail-section">
          <div class="template-detail-section-header">
            <div>
              <h3>当前计量与包装规则</h3>
              <p>这是后续业务使用的当前有效版本；历史单据和库存批次继续引用各自保存的不可变快照。</p>
            </div>
          </div>
          <el-descriptions :column="3" border>
            <el-descriptions-item label="最小销售单位">{{ templateDetail.minimumSalesUnit }}</el-descriptions-item>
            <el-descriptions-item label="最小发货单位">{{ templateDetail.minimumOutboundUnit }}</el-descriptions-item>
            <el-descriptions-item label="不足最小单位">{{ roundingName(templateDetail.roundingPolicy) }}</el-descriptions-item>
            <el-descriptions-item label="默认采购单位">{{ templateDetail.defaultPurchaseUnit }}</el-descriptions-item>
            <el-descriptions-item label="默认销售单位">{{ templateDetail.defaultSalesUnit }}</el-descriptions-item>
            <el-descriptions-item label="内容摘要">{{ templateDetail.contentHash || '—' }}</el-descriptions-item>
          </el-descriptions>
          <el-table
            :data="templateDetail.rules || []"
            border
            size="small"
            class="packaging-rules-table"
            style="margin-top: 12px"
          >
            <el-table-column prop="parentUnit" label="包装单位" min-width="130" />
            <el-table-column prop="childUnit" label="包含单位" min-width="130" />
            <el-table-column label="换算方式" min-width="130">
              <template #default="{ row }">{{ conversionModeName(row.conversionMode) }}</template>
            </el-table-column>
            <el-table-column label="包含数量/参考值" min-width="160">
              <template #default="{ row }">{{ ruleQuantity(row) }}</template>
            </el-table-column>
            <el-table-column label="可拆" width="80">
              <template #default="{ row }">{{ row.breakable ? '是' : '否' }}</template>
            </el-table-column>
            <el-table-column label="业务用途" min-width="180">
              <template #default="{ row }">{{ ruleContexts(row) }}</template>
            </el-table-column>
            <template #empty>
              <div class="compact-table-empty">当前模板仅使用主库存单位，没有包装层次</div>
            </template>
          </el-table>
        </section>

        <section class="template-detail-section">
          <div class="template-detail-section-header">
            <div>
              <h3>规则版本记录</h3>
              <p>每次发布规则新版都会新增记录，不覆盖历史版本。</p>
            </div>
          </div>
          <el-table :data="revisionHistory || []" border size="small">
            <el-table-column label="版本" width="90"><template #default="{ row }">v{{ row.revisionNo }}</template></el-table-column>
            <el-table-column prop="baseUnit" label="主库存单位" width="120" />
            <el-table-column prop="changeReason" label="发布原因" min-width="220" />
            <el-table-column label="发布时间" width="180"><template #default="{ row }">{{ formatDateTime(row.publishedAt) }}</template></el-table-column>
            <el-table-column label="当前" width="80">
              <template #default="{ row }">
                <el-tag v-if="row.revisionNo === templateDetail.currentRevisionNo" size="small" type="success">当前</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <section class="template-detail-section">
          <div class="template-detail-section-header">
            <div>
              <h3>关联产品系列（{{ associatedBindings.length }}）</h3>
              <p>一个系列只能使用一个当前模板；新增或更换关系会为该系列商品生成新的后续业务有效版本。</p>
            </div>
            <el-button
              type="primary"
              :disabled="templateDetail.status !== 'ENABLED' || !candidateBindings.length"
              @click="openAddAssociation"
            >
              新增关联
            </el-button>
          </div>
          <el-alert
            v-if="templateDetail.status !== 'ENABLED'"
            title="当前模板已停用，不能新增系列关联；已有系列可通过“编辑关系”更换为其他启用模板。"
            type="warning"
            :closable="false"
            show-icon
            style="margin-bottom: 12px"
          />
          <el-table :data="associatedBindings" border size="small">
            <el-table-column prop="categoryName" label="产品分类" min-width="150" />
            <el-table-column prop="productSeriesName" label="产品系列" min-width="190" />
            <el-table-column prop="productCount" label="商品数" width="90" align="right" />
            <el-table-column label="使用版本" width="110">
              <template #default="{ row }">v{{ row.templateRevisionNo || templateDetail.currentRevisionNo }}</template>
            </el-table-column>
            <el-table-column label="商品特例" width="110" align="right">
              <template #default="{ row }">{{ row.overrideProductCount || 0 }} 个</template>
            </el-table-column>
            <el-table-column label="风险提示" min-width="220">
              <template #default="{ row }">
                <span :class="row.warnings?.length ? 'text-warning' : 'muted'">
                  {{ row.warnings?.join('；') || '配置一致' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button text type="primary" @click="openEditAssociation(row)">编辑关系</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!associatedBindings.length" description="还没有产品系列关联此模板" />
        </section>
      </div>
    </div>

    <PackagingTemplateEditDialog
      v-model="editDialogVisible"
      :template="templateDetail"
      @saved="handleTemplateSaved"
    />
    <PackagingTemplateDialog
      v-model="publishDialogVisible"
      :template-id="templateId"
      @saved="handleTemplateSaved"
    />
    <SeriesPackagingBindingDialog
      v-model="bindingDialogVisible"
      :binding="activeBinding"
      :preferred-template-id="preferredTemplateId"
      @saved="handleBindingSaved"
    />

    <el-dialog v-model="seriesSelectVisible" title="新增系列关联" width="600px">
      <el-alert
        title="请选择要应用当前模板的产品系列。若该系列已有其他模板，确认后将变更原关系并生成新的后续业务有效版本。"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom: 14px"
      />
      <el-form label-width="100px">
        <el-form-item label="包装模板">
          <el-input :model-value="`${templateDetail?.code || ''} · ${templateDetail?.name || ''}`" disabled />
        </el-form-item>
        <el-form-item label="产品系列" required>
          <el-select v-model="selectedSeriesId" filterable placeholder="选择产品系列">
            <el-option
              v-for="item in candidateBindings"
              :key="item.productSeriesId"
              :label="`${item.categoryName || '未分类'} / ${item.productSeriesName}${item.templateName ? `（当前：${item.templateName}）` : '（未应用模板）'}`"
              :value="item.productSeriesId"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="seriesSelectVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmSeriesSelection">下一步</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRoute, useRouter } from 'vue-router'
import {
  api,
  type PackagingRule,
  type PackagingTemplate,
  type SeriesPackagingBinding,
} from '@/api/client'
import { formatDateTime } from '@/api/format'
import PageHeader from '@/components/PageHeader.vue'
import PackagingTemplateDialog from '@/components/PackagingTemplateDialog.vue'
import PackagingTemplateEditDialog from '@/components/PackagingTemplateEditDialog.vue'
import SeriesPackagingBindingDialog from '@/components/SeriesPackagingBindingDialog.vue'
import { returnPath } from '@/utils/navigation'
import { useWorkspaceTabsStore, workspaceRouteKey } from '@/stores/workspaceTabs'

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const workspaceTabs = useWorkspaceTabsStore()
const templateId = computed(() => Number(route.params.id))
const backTo = computed(() => returnPath(route, '/product-packaging'))
const editDialogVisible = ref(false)
const publishDialogVisible = ref(false)
const bindingDialogVisible = ref(false)
const seriesSelectVisible = ref(false)
const activeBinding = ref<SeriesPackagingBinding>()
const preferredTemplateId = ref<number>()
const selectedSeriesId = ref<number>()

const {
  data: templateDetail,
  isPending: templatePending,
  isError: templateError,
  refetch: refetchTemplate,
} = useQuery({
  queryKey: computed(() => ['packaging-template', templateId.value]),
  queryFn: () => api.packagingTemplate(templateId.value),
})
const {
  data: revisionHistory,
  refetch: refetchHistory,
} = useQuery({
  queryKey: computed(() => ['packaging-template-history', templateId.value]),
  queryFn: () => api.packagingTemplateHistory(templateId.value),
})
const {
  data: bindings,
  refetch: refetchBindings,
} = useQuery({
  queryKey: ['series-packaging-bindings'],
  queryFn: api.seriesPackagingBindings,
})

const pageTitle = computed(() => templateDetail.value
  ? `包装模板 ${templateDetail.value.code} · ${templateDetail.value.name}`
  : `包装模板 #${templateId.value}`)
const associatedBindings = computed(() =>
  (bindings.value ?? []).filter((item) => item.templateId === templateId.value))
const candidateBindings = computed(() =>
  (bindings.value ?? []).filter((item) => item.templateId !== templateId.value))

watch(
  () => templateDetail.value?.code,
  (code) => {
    if (code) workspaceTabs.setTitle(workspaceRouteKey(route), `包装模板 ${code}`)
  },
  { immediate: true },
)

function openEdit() {
  editDialogVisible.value = true
}

function openAddAssociation() {
  selectedSeriesId.value = undefined
  seriesSelectVisible.value = true
}

async function confirmSeriesSelection() {
  const binding = candidateBindings.value.find((item) => item.productSeriesId === selectedSeriesId.value)
  if (!binding) {
    ElMessage.warning('请选择产品系列')
    return
  }
  activeBinding.value = binding
  preferredTemplateId.value = templateId.value
  seriesSelectVisible.value = false
  await nextTick()
  bindingDialogVisible.value = true
}

function openEditAssociation(binding: SeriesPackagingBinding) {
  activeBinding.value = binding
  preferredTemplateId.value = undefined
  bindingDialogVisible.value = true
}

async function handleTemplateSaved(value?: PackagingTemplate) {
  if (value) workspaceTabs.setTitle(workspaceRouteKey(route), `包装模板 ${value.code}`)
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['packaging-templates'] }),
    refetchTemplate(),
    refetchHistory(),
  ])
}

async function handleBindingSaved() {
  await Promise.all([
    refetchBindings(),
    refetchTemplate(),
    queryClient.invalidateQueries({ queryKey: ['product-packaging-summaries'] }),
    queryClient.invalidateQueries({ queryKey: ['products'] }),
  ])
}

function roundingName(value?: string) {
  return ({
    REJECT: '不自动调整，直接提示',
    ROUND_UP: '向上调整到最小单位',
    ROUND_DOWN: '向下调整到最小单位',
  } as Record<string, string>)[value || ''] || value || '—'
}

function conversionModeName(value?: string) {
  return ({
    FIXED: '固定换算',
    ACTUAL_WEIGHT: '实际称重',
    THEORETICAL: '理论换算',
  } as Record<string, string>)[value || ''] || value || '—'
}

function ruleQuantity(rule: PackagingRule) {
  if (rule.conversionMode === 'FIXED') return rule.fixedChildQuantity ?? '—'
  if (rule.conversionMode === 'ACTUAL_WEIGHT') return rule.referenceBaseQuantity ?? '逐件录入'
  return rule.referenceBaseQuantity ?? '—'
}

function ruleContexts(rule: PackagingRule) {
  const contexts = []
  if (rule.purchaseAllowed) contexts.push('采购')
  if (rule.salesAllowed) contexts.push('销售')
  if (rule.outboundAllowed) contexts.push('发货')
  return contexts.join('、') || '未启用'
}
</script>

<style scoped>
.template-detail-content {
  padding: 16px;
}

.template-detail-section {
  margin-top: 22px;
}

.template-detail-section-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.template-detail-section-header h3 {
  margin: 0 0 4px;
}

.template-detail-section-header p {
  margin: 0;
  color: var(--text-muted);
}

.compact-table-empty {
  color: var(--text-muted);
}

:deep(.packaging-rules-table .el-table__empty-block) {
  min-height: 56px;
}

:deep(.packaging-rules-table .el-table__empty-text) {
  line-height: 56px;
}

:deep(.el-select) {
  width: 100%;
}
</style>
