<template>
  <div class="crm-activity-page">
    <section class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">跟进计划</div>
          <div class="muted">未来安排与已完成活动分开管理；逾期不会自动改为完成。</div>
        </div>
        <el-radio-group v-model="scope" @change="loadPlans">
          <el-radio-button value="ALL">全部待办</el-radio-button>
          <el-radio-button value="TODAY">今日</el-radio-button>
          <el-radio-button value="OVERDUE">已逾期</el-radio-button>
        </el-radio-group>
      </div>
      <el-table v-loading="loadingPlans" :data="plans">
        <el-table-column label="关联对象" min-width="190">
          <template #default="{ row }">
            <el-button link type="primary" @click="openTarget(row.targetType, row.targetId, row.partnerId)">
              {{ targetName(row.targetType) }} · {{ row.partnerName || `#${row.targetId}` }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column prop="subject" label="计划" min-width="190"/>
        <el-table-column label="方式" width="90">
          <template #default="{ row }">{{ crmActivityName(row.planType) }}</template>
        </el-table-column>
        <el-table-column label="计划时间" width="180">
          <template #default="{ row }"><span
              :class="{ overdue: row.status === 'OPEN' && new Date(row.dueAt) < new Date() }">{{
              dateTimeText(row.dueAt)
            }}</span></template>
        </el-table-column>
        <el-table-column prop="priority" label="优先级" width="100"/>
        <el-table-column prop="ownerName" label="负责人" width="110"/>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button link type="success" @click="complete(row)">完成</el-button>
            <el-button link type="danger" @click="cancel(row)">取消</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
    <section class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">销售活动历史</div>
          <div class="muted">电话、拜访、微信、邮件和会议结果按发生时间留档，不覆盖原记录。</div>
        </div>
      </div>
      <el-table v-loading="loadingActivities" :data="activities">
        <el-table-column label="发生时间" width="180">
          <template #default="{ row }">{{ dateTimeText(row.occurredAt) }}</template>
        </el-table-column>
        <el-table-column label="方式" width="90">
          <template #default="{ row }">{{ crmActivityName(row.activityType) }}</template>
        </el-table-column>
        <el-table-column prop="partnerName" label="客户" min-width="150"/>
        <el-table-column prop="subject" label="主题" min-width="180"/>
        <el-table-column prop="outcome" label="结果" min-width="220" show-overflow-tooltip/>
        <el-table-column prop="nextAction" label="下一步" min-width="180" show-overflow-tooltip/>
        <el-table-column prop="ownerName" label="负责人" width="110"/>
      </el-table>
    </section>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {ElMessage, ElMessageBox} from 'element-plus'
import {api, type CrmActivity, type CrmPlan} from '@/api/client'
import {crmActivityName, dateTimeText} from '@/utils/crm'

const route = useRoute();
const router = useRouter();
const scope = ref(String(route.query.scope || 'ALL'));
const plans = ref<CrmPlan[]>([]);
const activities = ref<CrmActivity[]>([]);
const loadingPlans = ref(false);
const loadingActivities = ref(false)
onMounted(() => {
  loadPlans();
  loadActivities()
})

async function loadPlans() {
  loadingPlans.value = true;
  try {
    plans.value = await api.crmPlans({
      status: 'OPEN',
      dueScope: scope.value === 'ALL' ? undefined : scope.value,
      overdueFirst: true
    })
  } finally {
    loadingPlans.value = false
  }
}

async function loadActivities() {
  loadingActivities.value = true;
  try {
    activities.value = await api.crmActivities()
  } finally {
    loadingActivities.value = false
  }
}

async function complete(row: CrmPlan) {
  const result = await ElMessageBox.prompt('填写本次跟进结果，完成后自动生成销售活动记录。', '完成跟进计划', {
    inputType: 'textarea',
    inputValidator: (value) => Boolean(value.trim()) || '请填写结果'
  }).catch(() => null);
  if (!result) return;
  await api.completeCrmPlan(row.id, {
    outcome: result.value,
    occurredAt: new Date().toISOString().slice(0, 19),
    durationMinutes: 0,
    nextAction: ''
  });
  ElMessage.success('计划已完成');
  await Promise.all([loadPlans(), loadActivities()])
}

async function cancel(row: CrmPlan) {
  const result = await ElMessageBox.prompt('取消计划会保留原记录，请填写原因。', '取消计划', {
    inputType: 'textarea',
    inputValidator: (value) => Boolean(value.trim()) || '请填写原因'
  }).catch(() => null);
  if (!result) return;
  await api.cancelCrmPlan(row.id, {reasonCode: 'OTHER', reason: result.value});
  ElMessage.success('计划已取消');
  loadPlans()
}

function targetName(value: string) {
  return ({
    LEAD: '线索',
    CUSTOMER: '客户',
    OPPORTUNITY: '商机',
    QUOTE: '报价'
  } as Record<string, string>)[value] || value
}

async function openTarget(type: string, id: number, partnerId?: number | null) {
  if (type === 'LEAD') router.push(`/crm/leads/${id}`); else if (type === 'OPPORTUNITY') router.push(`/crm/opportunities/${id}`); else if (type === 'QUOTE') router.push(`/crm/quotes/${id}`); else if (partnerId) {
    const rows = await api.crmCustomers();
    const customer = rows.find((item) => item.partnerId === partnerId);
    if (customer) router.push(`/crm/customers/${customer.id}`)
  }
}
</script>

<style scoped>.crm-activity-page {
  display: grid;
  gap: 16px
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center
}

.overdue {
  color: var(--el-color-danger);
  font-weight: 600
}</style>
