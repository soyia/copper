<template>
  <el-dialog v-model="visible" title="编辑包装模板" width="620px" destroy-on-close>
    <el-alert
      title="名称和用途说明不会发布规则新版本；启用或停用属于状态变更，必须填写原因并单独留痕。"
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 14px"
    />
    <el-form label-width="96px">
      <el-form-item label="模板编码">
        <el-input v-model="form.code" disabled />
      </el-form-item>
      <el-form-item label="模板名称" required>
        <el-input v-model="form.name" maxlength="128" />
      </el-form-item>
      <el-form-item label="用途说明">
        <el-input v-model="form.description" type="textarea" :rows="2" maxlength="255" show-word-limit />
      </el-form-item>
      <el-divider content-position="left">使用状态</el-divider>
      <el-form-item label="状态" required>
        <el-select v-model="form.status">
          <el-option label="启用" value="ENABLED" />
          <el-option label="停用" value="DISABLED" />
        </el-select>
      </el-form-item>
      <el-alert
        v-if="statusChanged"
        :title="form.status === 'ENABLED'
          ? '启用后可以重新应用到产品系列并发布规则新版。'
          : '停用后禁止新应用和发布规则新版；已有系列绑定、历史单据和库存批次快照继续有效。'"
        :type="form.status === 'ENABLED' ? 'info' : 'warning'"
        :closable="false"
        show-icon
        style="margin-bottom: 14px"
      />
      <el-form-item v-if="statusChanged" label="变更原因" required>
        <el-input
          v-model="form.changeReason"
          type="textarea"
          :rows="2"
          maxlength="255"
          show-word-limit
          placeholder="说明本次启用或停用原因"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存修改</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, type PackagingTemplate } from '@/api/client'
import { packagingErrorMessage } from '@/utils/packagingValidation'

const props = defineProps<{
  modelValue: boolean
  template?: PackagingTemplate
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  saved: [value: PackagingTemplate]
}>()

const saving = ref(false)
const originalName = ref('')
const originalDescription = ref('')
const originalStatus = ref('ENABLED')
const form = reactive({
  code: '',
  name: '',
  description: '',
  status: 'ENABLED',
  changeReason: '',
})
const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})
const metadataChanged = computed(() =>
  form.name.trim() !== originalName.value
  || form.description.trim() !== originalDescription.value)
const statusChanged = computed(() => form.status !== originalStatus.value)

watch(
  () => [props.modelValue, props.template?.id] as const,
  ([opened]) => {
    if (!opened || !props.template) return
    originalName.value = props.template.name
    originalDescription.value = props.template.description || ''
    originalStatus.value = props.template.status
    Object.assign(form, {
      code: props.template.code,
      name: props.template.name,
      description: props.template.description || '',
      status: props.template.status,
      changeReason: '',
    })
  },
  { immediate: true },
)

async function save() {
  const current = props.template
  if (!current) {
    ElMessage.warning('包装模板尚未加载')
    return
  }
  if (!form.name.trim()) {
    ElMessage.warning('请填写模板名称')
    return
  }
  if (statusChanged.value && !form.changeReason.trim()) {
    ElMessage.warning('状态发生变化时必须填写变更原因')
    return
  }
  if (!metadataChanged.value && !statusChanged.value) {
    ElMessage.info('没有需要保存的修改')
    return
  }

  saving.value = true
  let latest = current
  let metadataSaved = false
  try {
    if (metadataChanged.value) {
      latest = await api.updatePackagingTemplateMetadata(current.id, {
        name: form.name.trim(),
        description: form.description.trim(),
        expectedLockVersion: latest.lockVersion,
      })
      metadataSaved = true
    }
    if (statusChanged.value) {
      latest = await api.updatePackagingTemplateStatus(current.id, {
        status: form.status,
        expectedLockVersion: latest.lockVersion,
        changeReason: form.changeReason.trim(),
      })
    }
    ElMessage.success(statusChanged.value
      ? `包装模板已${form.status === 'ENABLED' ? '启用' : '停用'}，状态原因已留痕`
      : '包装模板信息已保存，规则版本未变化')
    emit('saved', latest)
    visible.value = false
  } catch (error) {
    if (metadataSaved && statusChanged.value) {
      ElMessage.error('基本信息已保存，但状态变更失败；请刷新后重新编辑状态')
      emit('saved', latest)
      visible.value = false
    } else {
      ElMessage.error(packagingErrorMessage(error, '包装模板修改失败'))
    }
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
