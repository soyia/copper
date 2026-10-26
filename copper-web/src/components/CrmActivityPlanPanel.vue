<template>
  <section class="panel crm-activity-plan-panel">
    <div class="panel-header">
      <div>
        <div class="panel-title">销售活动与跟进计划</div>
        <div class="muted">已完成活动保留历史；未来安排单独作为计划，完成时自动生成活动记录。</div>
      </div>
      <div class="header-actions">
        <el-button @click="openActivity">记录活动</el-button>
        <el-button type="primary" @click="openPlan">安排跟进</el-button>
      </div>
    </div>
    <el-tabs v-model="activeTab">
      <el-tab-pane label="活动记录" name="activities">
        <el-empty v-if="!activities.length" description="暂无销售活动" />
        <el-timeline v-else>
          <el-timeline-item v-for="item in activities" :key="item.id" :timestamp="dateTimeText(item.occurredAt)" placement="top">
            <div class="timeline-title">{{ crmActivityName(item.activityType) }} · {{ item.subject }}</div>
            <div>{{ item.outcome || '未填写结果' }}</div>
            <div v-if="item.nextAction" class="muted">下一步：{{ item.nextAction }}</div>
            <div class="muted">负责人：{{ item.ownerName || '-' }}</div>
          </el-timeline-item>
        </el-timeline>
      </el-tab-pane>
      <el-tab-pane :label="`跟进计划 ${openPlans.length ? `(${openPlans.length})` : ''}`" name="plans">
        <el-table :data="plans" empty-text="暂无跟进计划">
          <el-table-column prop="subject" label="计划" min-width="180" />
          <el-table-column label="方式" width="90"><template #default="{ row }">{{ crmActivityName(row.planType) }}</template></el-table-column>
          <el-table-column label="计划时间" width="155"><template #default="{ row }">{{ dateTimeText(row.dueAt) }}</template></el-table-column>
          <el-table-column prop="priority" label="优先级" width="90" />
          <el-table-column label="状态" width="95"><template #default="{ row }"><el-tag :type="crmStatusType(row.status)">{{ crmStatusName(row.status) }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <template v-if="row.status === 'OPEN'">
                <el-button link type="success" @click="complete(row)">完成</el-button>
                <el-button link type="danger" @click="cancel(row)">取消</el-button>
              </template>
              <span v-else class="muted">已归档</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="activityVisible" title="记录销售活动" width="620px" destroy-on-close>
      <el-form label-width="92px">
        <el-form-item label="活动方式" required><el-select v-model="activityForm.activityType"><el-option v-for="item in activityOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="主题" required><el-input v-model="activityForm.subject" maxlength="128" /></el-form-item>
        <el-form-item label="发生时间" required><el-date-picker v-model="activityForm.occurredAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item>
        <el-form-item label="沟通结果" required><el-input v-model="activityForm.outcome" type="textarea" :rows="3" maxlength="1000" /></el-form-item>
        <el-form-item label="下一步"><el-input v-model="activityForm.nextAction" maxlength="500" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="activityVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveActivity">保存活动</el-button></template>
    </el-dialog>

    <el-dialog v-model="planVisible" title="安排跟进计划" width="620px" destroy-on-close>
      <el-form label-width="92px">
        <el-form-item label="计划方式" required><el-select v-model="planForm.planType"><el-option v-for="item in activityOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="计划主题" required><el-input v-model="planForm.subject" maxlength="128" /></el-form-item>
        <el-form-item label="计划时间" required><el-date-picker v-model="planForm.dueAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item>
        <el-form-item label="优先级"><el-select v-model="planForm.priority"><el-option label="普通" value="NORMAL" /><el-option label="高" value="HIGH" /><el-option label="紧急" value="URGENT" /><el-option label="低" value="LOW" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="planVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="savePlan">保存计划</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type CrmActivity, type CrmPlan } from '@/api/client'
import { crmActivityName, crmStatusName, crmStatusType, dateTimeText } from '@/utils/crm'

const props = defineProps<{ targetType: string; targetId: number; partnerId?: number | null; activities: CrmActivity[]; plans: CrmPlan[] }>()
const emit = defineEmits<{ refresh: [] }>()
const activeTab = ref('activities')
const activityVisible = ref(false)
const planVisible = ref(false)
const saving = ref(false)
const activityOptions = [
  { label: '电话', value: 'CALL' }, { label: '拜访', value: 'VISIT' }, { label: '微信', value: 'WECHAT' },
  { label: '邮件', value: 'EMAIL' }, { label: '会议', value: 'MEETING' }, { label: '其他', value: 'OTHER' },
]
const openPlans = computed(() => props.plans.filter((item) => item.status === 'OPEN'))
const nowText = () => new Date().toISOString().slice(0, 19)
const activityForm = reactive({ activityType: 'CALL', subject: '', occurredAt: nowText(), outcome: '', nextAction: '' })
const planForm = reactive({ planType: 'CALL', subject: '', dueAt: '', priority: 'NORMAL' })

function openActivity() {
  Object.assign(activityForm, { activityType: 'CALL', subject: '', occurredAt: nowText(), outcome: '', nextAction: '' })
  activityVisible.value = true
}

function openPlan() {
  Object.assign(planForm, { planType: 'CALL', subject: '', dueAt: '', priority: 'NORMAL' })
  planVisible.value = true
}

async function saveActivity() {
  if (!activityForm.subject.trim() || !activityForm.occurredAt || !activityForm.outcome.trim()) { ElMessage.warning('请完整填写活动主题、时间和结果'); return }
  saving.value = true
  try {
    await api.createCrmActivity({ targetType: props.targetType, targetId: props.targetId, partnerId: props.partnerId, ...activityForm, durationMinutes: 0 })
    ElMessage.success('销售活动已记录')
    activityVisible.value = false
    emit('refresh')
  } finally { saving.value = false }
}

async function savePlan() {
  if (!planForm.subject.trim() || !planForm.dueAt) { ElMessage.warning('请填写计划主题和时间'); return }
  saving.value = true
  try {
    await api.createCrmPlan({ targetType: props.targetType, targetId: props.targetId, partnerId: props.partnerId, ...planForm, reminderMinutes: 60 })
    ElMessage.success('跟进计划已安排')
    planVisible.value = false
    emit('refresh')
  } finally { saving.value = false }
}

async function complete(row: CrmPlan) {
  const result = await ElMessageBox.prompt('填写本次跟进结果；完成后会自动生成销售活动记录。', '完成跟进计划', { inputType: 'textarea', inputValidator: (value) => Boolean(value.trim()) || '请填写跟进结果' }).catch(() => null)
  if (!result) return
  await api.completeCrmPlan(row.id, { outcome: result.value, occurredAt: nowText(), durationMinutes: 0, nextAction: '' })
  ElMessage.success('计划已完成并生成活动记录')
  emit('refresh')
}

async function cancel(row: CrmPlan) {
  const result = await ElMessageBox.prompt('取消不会删除计划，请填写原因。', '取消跟进计划', { inputType: 'textarea', inputValidator: (value) => Boolean(value.trim()) || '请填写取消原因' }).catch(() => null)
  if (!result) return
  await api.cancelCrmPlan(row.id, { reasonCode: 'OTHER', reason: result.value })
  ElMessage.success('计划已取消，历史已保留')
  emit('refresh')
}
</script>

<style scoped>
.crm-activity-plan-panel { margin-top: 16px; }
.panel-header, .header-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.timeline-title { font-weight: 600; margin-bottom: 4px; }
</style>
