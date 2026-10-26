<template>
  <el-dialog v-model="visible" :title="`批次质保 ${batch?.batchNo || ''}`" width="900px" destroy-on-close>
    <div v-if="batch" class="quality-heading">
      <div class="muted">质保书按批次留存并复核；已复核记录不能覆盖修改，只能作废后新建。</div>
      <el-button v-if="appStore.hasPermission('inventory.quality.manage')" type="primary" plain @click="qualityDialogVisible = true">登记质保书</el-button>
    </div>
    <el-table v-if="batch" :data="qualityQuery.data.value ?? []" border size="small" style="margin-top: 12px">
      <el-table-column prop="certificateNo" label="质保书号" min-width="160" />
      <el-table-column prop="manufacturerName" label="生产厂家" min-width="140" />
      <el-table-column prop="manufacturerBatchNo" label="厂家批次" min-width="130" />
      <el-table-column prop="heatNo" label="炉号" width="110" />
      <el-table-column prop="issueDate" label="签发日期" width="110" />
      <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="qualityStatusType(row.verificationStatus)">{{ qualityStatus(row.verificationStatus) }}</el-tag></template></el-table-column>
      <el-table-column v-if="appStore.hasPermission('inventory.quality.manage')" label="操作" width="150">
        <template #default="{ row }">
          <el-button v-if="row.verificationStatus === 'PENDING'" link type="success" @click="review(row, 'VERIFIED')">通过</el-button>
          <el-button v-if="row.status === 'ENABLED'" link type="danger" @click="review(row, row.verificationStatus === 'PENDING' ? 'REJECTED' : 'VOID')">
            {{ row.verificationStatus === 'PENDING' ? '驳回' : '作废' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="qualityDialogVisible" title="登记批次质保书" width="620px" append-to-body>
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="质保书号" required><el-input v-model="quality.certificateNo" maxlength="128" /></el-form-item>
          <el-form-item label="签发日期"><el-date-picker v-model="quality.issueDate" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
          <el-form-item label="生产厂家"><el-input v-model="quality.manufacturerName" /></el-form-item>
          <el-form-item label="厂家批次"><el-input v-model="quality.manufacturerBatchNo" /></el-form-item>
          <el-form-item label="炉号"><el-input v-model="quality.heatNo" /></el-form-item>
          <el-form-item label="卷号"><el-input v-model="quality.coilNo" /></el-form-item>
        </div>
      </el-form>
      <el-alert title="本次登记基础批次标识；详细检测指标可后续按实际质保书逐项补录，不能编造。" type="info" :closable="false" show-icon />
      <template #footer>
        <el-button @click="qualityDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingQuality" @click="saveQuality">保存</el-button>
      </template>
    </el-dialog>

    <template #footer><el-button @click="visible = false">关闭</el-button></template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type InventoryBatch, type ProductQualityCertificate } from '@/api/client'
import { useAppStore } from '@/stores/app'

const props = defineProps<{ modelValue: boolean; batch?: InventoryBatch }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>()
const appStore = useAppStore()
const visible = computed({ get: () => props.modelValue, set: (value) => emit('update:modelValue', value) })
const qualityDialogVisible = ref(false)
const savingQuality = ref(false)
const quality = reactive({ certificateNo: '', issueDate: today(), manufacturerName: '', manufacturerBatchNo: '', heatNo: '', coilNo: '' })
const qualityQuery = useQuery({
  queryKey: ['inventory-batch-quality', computed(() => props.batch?.id)],
  queryFn: () => api.inventoryBatchQualityCertificates(props.batch!.id),
  enabled: computed(() => Boolean(props.modelValue && props.batch?.id && appStore.hasPermission('inventory.quality.view'))),
})

watch(() => props.modelValue, (value) => {
  if (value) qualityQuery.refetch()
})

async function saveQuality() {
  if (!props.batch || !quality.certificateNo.trim()) return ElMessage.warning('请填写质保书号')
  savingQuality.value = true
  try {
    await api.createInventoryBatchQualityCertificate(props.batch.id, { ...quality, certificateType: 'COA', status: 'ENABLED', results: [] })
    qualityDialogVisible.value = false
    Object.assign(quality, { certificateNo: '', issueDate: today(), manufacturerName: '', manufacturerBatchNo: '', heatNo: '', coilNo: '' })
    ElMessage.success('质保书已登记，等待复核')
    await qualityQuery.refetch()
  } finally {
    savingQuality.value = false
  }
}

async function review(row: ProductQualityCertificate, status: 'VERIFIED' | 'REJECTED' | 'VOID') {
  const message = status === 'VERIFIED' ? '确认质保书批次标识与原件一致？' : '请填写原因。'
  let reason = '与原件核对一致'
  if (status === 'VERIFIED') await ElMessageBox.confirm(message, '通过质保书复核', { type: 'warning' })
  else reason = (await ElMessageBox.prompt(message, status === 'VOID' ? '作废质保书' : '驳回质保书', { inputPattern: /.{2,}/, inputErrorMessage: '至少填写2个字' })).value
  await api.reviewInventoryBatchQualityCertificate(props.batch!.id, row.id, { reviewStatus: status, reviewComment: reason, expectedLockVersion: row.lockVersion })
  ElMessage.success('质保书状态已更新')
  await qualityQuery.refetch()
}

function qualityStatus(value: string) { return ({ PENDING: '待复核', VERIFIED: '已通过', REJECTED: '已驳回', VOID: '已作废' } as Record<string, string>)[value] || value }
function qualityStatusType(value: string) { return ({ PENDING: 'warning', VERIFIED: 'success', REJECTED: 'danger', VOID: 'info' } as Record<string, string>)[value] || 'info' }
function today() { return new Date().toLocaleDateString('sv-SE') }
</script>

<style scoped>
.quality-heading { display: flex; justify-content: space-between; gap: 16px; align-items: center; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 14px; }
</style>
