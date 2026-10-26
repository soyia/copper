<template>
  <el-dialog v-model="visible" title="应用系列计量包装模板" width="640px" destroy-on-close>
    <el-alert
      title="这是系列级关系：应用后会为本系列全部商品生成新的有效计量包装版本；历史单据和库存批次继续使用原快照。"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom: 14px"
    />
    <el-form label-width="100px">
      <el-form-item label="产品系列">
        <el-input :model-value="seriesLabel" disabled />
        <div class="muted">当前系列包含 {{ binding?.productCount || 0 }} 个商品。</div>
      </el-form-item>
      <el-form-item label="包装模板" required>
        <el-select v-model="templateId" filterable placeholder="选择已启用的包装模板">
          <el-option
            v-for="item in selectableTemplates"
            :key="item.id"
            :label="`${item.code} · ${item.name}（v${item.currentRevisionNo}，主单位 ${item.baseUnit}）`"
            :value="item.id"
            :disabled="item.status !== 'ENABLED'"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="变更原因" required>
        <el-input
          v-model="changeReason"
          maxlength="255"
          show-word-limit
          placeholder="说明应用或更换模板的原因"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">确认应用</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { api, type SeriesPackagingBinding } from '@/api/client'
import { packagingErrorMessage } from '@/utils/packagingValidation'

const props = defineProps<{
  modelValue: boolean
  binding?: SeriesPackagingBinding
  preferredTemplateId?: number
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [value: SeriesPackagingBinding]
}>()

const queryClient = useQueryClient()
const saving = ref(false)
const templateId = ref<number>()
const changeReason = ref('')
const { data: templates } = useQuery({ queryKey: ['packaging-templates'], queryFn: api.packagingTemplates })
const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})
const seriesLabel = computed(() => props.binding
  ? `${props.binding.categoryName || '未分类'} / ${props.binding.productSeriesName}`
  : '未选择产品系列')
const selectableTemplates = computed(() =>
  (templates.value ?? []).filter((item) =>
    item.status === 'ENABLED' || item.id === props.binding?.templateId),
)

watch(
  () => [props.modelValue, props.binding?.productSeriesId, props.preferredTemplateId] as const,
  ([opened]) => {
    if (!opened) return
    templateId.value = props.preferredTemplateId || props.binding?.templateId || undefined
    changeReason.value = ''
  },
  { immediate: true },
)

async function save() {
  const binding = props.binding
  if (!binding || !templateId.value) {
    ElMessage.warning('请选择产品系列和包装模板')
    return
  }
  if (!changeReason.value.trim()) {
    ElMessage.warning('请填写应用或更换模板的原因')
    return
  }
  if (templateId.value === binding.templateId) {
    ElMessage.warning('当前系列已经使用该模板，无需重复应用')
    return
  }
  saving.value = true
  try {
    const result = await api.saveSeriesPackagingBinding(binding.productSeriesId, {
      templateId: templateId.value,
      expectedBindingVersionNo: binding.bindingVersionNo || 0,
      changeReason: changeReason.value.trim(),
    })
    ElMessage.success('系列包装模板已应用，新版本只影响后续业务')
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['series-packaging-bindings'] }),
      queryClient.invalidateQueries({ queryKey: ['product-packaging-summaries'] }),
      queryClient.invalidateQueries({ queryKey: ['products'] }),
    ])
    emit('saved', result)
    visible.value = false
  } catch (error) {
    ElMessage.error(packagingErrorMessage(error, '系列包装模板应用失败'))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
:deep(.el-select) {
  width: 100%;
}
</style>
