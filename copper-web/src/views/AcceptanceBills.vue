<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">应收承兑票据</div>
          <div class="muted">收票时核销客户应收，但不计银行现金；贴现手续费单独作为财务损失保留。</div>
        </div>
        <el-button type="primary" @click="openCreate">登记承兑收票</el-button>
      </div>
      <div class="list-tools">
        <PartnerRemoteSelect v-model="filters.customerId" type="CUSTOMER" clearable placeholder="客户" />
        <el-select v-model="filters.status" clearable placeholder="票据状态"><el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select>
        <el-date-picker v-model="filters.dueRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="到期开始" end-placeholder="到期结束" />
        <div class="list-tools-actions"><el-button @click="resetFilters">重置</el-button></div>
      </div>
      <el-table :data="filteredBills" class="data-table" size="small" border show-overflow-tooltip max-height="calc(100vh - 275px)">
        <el-table-column label="票据单号" width="195" fixed="left"><template #default="{ row }"><el-button link type="primary" @click="openHistory(row)">{{ row.billNo }}</el-button></template></el-table-column>
        <el-table-column prop="instrumentNo" label="票号" width="220" />
        <el-table-column label="票据类型" width="120"><template #default="{ row }">{{ billTypeName(row.billType) }}</template></el-table-column>
        <el-table-column prop="salesOrderNo" label="销售订单" width="220"><template #default="{ row }"><router-link class="table-link" :to="relatedDetailRoute(route, `/sales-orders/${row.salesOrderId}`)">{{ row.salesOrderNo }}</router-link></template></el-table-column>
        <el-table-column prop="customerName" label="客户" min-width="160" />
        <el-table-column label="票面金额" width="130" align="right"><template #default="{ row }">{{ money(row.faceAmount) }}</template></el-table-column>
        <el-table-column label="实收资金" width="130" align="right"><template #default="{ row }">{{ money(row.realizedCashAmount) }}</template></el-table-column>
        <el-table-column label="贴现损失" width="120" align="right"><template #default="{ row }">{{ money(row.discountFee) }}</template></el-table-column>
        <el-table-column prop="dueDate" label="到期日期" width="115" :formatter="formatDateColumn" />
        <el-table-column prop="acceptor" label="承兑人" min-width="170" />
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="billStatusType(row.status)" size="small">{{ billStatusName(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="310" fixed="right">
          <template #default="{ row }">
            <el-button text @click="openHistory(row)">流水</el-button>
            <template v-if="row.status === 'HELD'">
              <el-button text type="primary" @click="openAction(row, 'DISCOUNT')">贴现</el-button>
              <el-button text type="success" @click="openAction(row, 'MATURITY')">兑付</el-button>
              <el-button text @click="openAction(row, 'ENDORSE')">背书</el-button>
              <el-button text type="danger" @click="openAction(row, 'BOUNCE')">退票</el-button>
            </template>
            <el-button v-else-if="row.status === 'DISCOUNTED'" text type="danger" @click="openAction(row, 'BOUNCE')">追索退票</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="createVisible" title="登记承兑收票" width="680px">
      <el-alert title="保存即代表票据已经收到：系统将按票面金额核销销售订单应收，但实收资金仍为0。" type="warning" :closable="false" style="margin-bottom:14px" />
      <el-form label-width="102px">
        <el-form-item label="销售订单" required><el-select v-model="createForm.salesOrderId" filterable><el-option v-for="row in receivableOrders" :key="row.id" :label="`${row.orderNo} / ${row.customerName} / 待收 ${money(row.outstandingAmount)}`" :value="row.id" /></el-select></el-form-item>
        <el-form-item label="票据类型" required><el-select v-model="createForm.billType"><el-option label="银行承兑" value="BANK_ACCEPTANCE" /><el-option label="商业承兑" value="COMMERCIAL_ACCEPTANCE" /></el-select></el-form-item>
        <el-form-item label="票号" required><el-input v-model="createForm.instrumentNo" /></el-form-item>
        <el-form-item label="票面金额" required><el-input v-model="createForm.faceAmount" inputmode="decimal" /></el-form-item>
        <el-form-item label="出票日期" required><el-date-picker v-model="createForm.issueDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="到期日期" required><el-date-picker v-model="createForm.dueDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="收票日期" required><el-date-picker v-model="createForm.receivedDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="出票人"><el-input v-model="createForm.drawer" /></el-form-item>
        <el-form-item label="承兑人" required><el-input v-model="createForm.acceptor" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="createForm.memo" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="createVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveBill">确认收票</el-button></template>
    </el-dialog>

    <el-dialog v-model="actionVisible" :title="actionTitle" width="560px">
      <el-alert v-if="actionTarget" :title="`票面金额 ${money(actionTarget.faceAmount)}，票号 ${actionTarget.instrumentNo}`" type="info" :closable="false" style="margin-bottom:14px" />
      <el-form label-width="100px">
        <el-form-item label="业务日期" required><el-date-picker v-model="actionForm.occurredDate" value-format="YYYY-MM-DD" /></el-form-item>
        <template v-if="actionType === 'DISCOUNT'">
          <el-form-item label="贴现银行" required><el-input v-model="actionForm.bank" /></el-form-item>
          <el-form-item label="银行实收" required><el-input v-model="actionForm.netProceeds" inputmode="decimal" /></el-form-item>
          <el-form-item label="贴现损失"><strong class="text-danger">{{ money(discountFeePreview) }}</strong></el-form-item>
        </template>
        <el-form-item v-if="requiresBankAccount" label="企业账户" required><BusinessBankAccountSelect v-model="actionForm.businessBankAccountId" /></el-form-item>
        <el-form-item v-if="actionType === 'ENDORSE'" label="被背书单位" required><el-input v-model="actionForm.counterparty" /></el-form-item>
        <el-form-item v-if="actionType === 'BOUNCE'" label="追索方"><el-input v-model="actionForm.counterparty" placeholder="银行、承兑人等" /></el-form-item>
        <el-form-item :label="actionType === 'BOUNCE' ? '退票原因' : '备注'" :required="actionType === 'BOUNCE'"><el-input v-model="actionForm.memo" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="actionVisible=false">取消</el-button><el-button :type="actionType==='BOUNCE'?'danger':'primary'" :loading="acting" @click="submitAction">确认{{ actionTitle }}</el-button></template>
    </el-dialog>

    <RelatedDetailDrawer
      v-model="historyVisible"
      :title="`承兑票据 ${historyTarget?.billNo || ''}`"
      :subtitle="historyTarget ? `${historyTarget.instrumentNo} · ${billStatusName(historyTarget.status)}` : ''"
      :trail="[{ label: '应收承兑票据' }, { label: historyTarget?.billNo || '票据流水' }]"
      size="900px"
      @breadcrumb="historyVisible = false"
    >
      <el-descriptions v-if="historyTarget" class="fixed-detail-descriptions" :column="2" label-width="140px" border>
        <el-descriptions-item label="票据单号"><span class="related-record-no">{{ historyTarget.billNo }}</span></el-descriptions-item>
        <el-descriptions-item label="票号"><span class="related-record-no">{{ historyTarget.instrumentNo }}</span></el-descriptions-item>
        <el-descriptions-item label="销售订单"><span class="related-record-no"><router-link class="table-link" :to="relatedDetailRoute(route, `/sales-orders/${historyTarget.salesOrderId}`)">{{ historyTarget.salesOrderNo }}</router-link></span></el-descriptions-item>
        <el-descriptions-item label="客户">{{ historyTarget.customerName }}</el-descriptions-item>
        <el-descriptions-item label="票据类型">{{ billTypeName(historyTarget.billType) }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ billStatusName(historyTarget.status) }}</el-descriptions-item>
        <el-descriptions-item label="票面金额">{{ money(historyTarget.faceAmount) }}</el-descriptions-item>
        <el-descriptions-item label="实收资金">{{ money(historyTarget.realizedCashAmount) }}</el-descriptions-item>
        <el-descriptions-item label="出票日期">{{ formatDate(historyTarget.issueDate) }}</el-descriptions-item>
        <el-descriptions-item label="到期日期">{{ formatDate(historyTarget.dueDate) }}</el-descriptions-item>
        <el-descriptions-item label="承兑人" :span="2">{{ historyTarget.acceptor || '-' }}</el-descriptions-item>
      </el-descriptions>
      <div class="embedded-title" style="margin-top: 18px">票据流水</div>
      <el-table :data="events" size="small" border>
        <el-table-column prop="eventNo" label="流水号" width="180" />
        <el-table-column label="事件" width="90"><template #default="{ row }">{{ eventName(row.eventType) }}</template></el-table-column>
        <el-table-column prop="occurredDate" label="日期" width="110" :formatter="formatDateColumn" />
        <el-table-column label="资金变动" width="120" align="right"><template #default="{ row }">{{ signedMoney(row.cashAmount) }}</template></el-table-column>
        <el-table-column label="费用损失" width="110" align="right"><template #default="{ row }">{{ money(row.feeAmount) }}</template></el-table-column>
        <el-table-column prop="businessBankAccountName" label="企业银行账户" min-width="150"><template #default="{ row }">{{ row.businessBankAccountName || '-' }}</template></el-table-column>
        <el-table-column prop="counterparty" label="对方" min-width="140" />
        <el-table-column prop="memo" label="说明" min-width="220" />
      </el-table>
      <template #footer><el-button @click="historyVisible = false">关闭</el-button></template>
    </RelatedDetailDrawer>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { api, type AcceptanceBillRow, type AcceptanceEventRow } from '@/api/client'
import { currentDateText, formatDate, formatDateColumn, money } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import RelatedDetailDrawer from '@/components/RelatedDetailDrawer.vue'
import BusinessBankAccountSelect from '@/components/BusinessBankAccountSelect.vue'
import { relatedDetailRoute } from '@/utils/navigation'

const route = useRoute(); const queryClient = useQueryClient()
const statusOptions = [{label:'持有中',value:'HELD'},{label:'已贴现',value:'DISCOUNTED'},{label:'已兑付',value:'MATURED'},{label:'已背书',value:'ENDORSED'},{label:'已退票',value:'BOUNCED'}]
const filters = reactive({ customerId: undefined as number|undefined, status:'', dueRange: [] as string[] })
const createVisible=ref(false), saving=ref(false), actionVisible=ref(false), acting=ref(false), historyVisible=ref(false)
const actionType=ref<'DISCOUNT'|'MATURITY'|'ENDORSE'|'BOUNCE'>('DISCOUNT'), actionTarget=ref<AcceptanceBillRow>(), events=ref<AcceptanceEventRow[]>([])
const historyTarget=ref<AcceptanceBillRow>()
const createForm=reactive({salesOrderId:undefined as number|undefined,instrumentNo:'',billType:'BANK_ACCEPTANCE',faceAmount:'',issueDate:currentDateText(),dueDate:'',receivedDate:currentDateText(),drawer:'',acceptor:'',memo:''})
const actionForm=reactive({occurredDate:currentDateText(),netProceeds:'',bank:'',counterparty:'',businessBankAccountId:undefined as number|undefined,memo:''})
const {data:bills}=useQuery({queryKey:['acceptance-bills'],queryFn:()=>api.acceptanceBills()})
const {data:salesOrders}=useQuery({queryKey:['sales-orders'],queryFn:api.salesOrders})
const receivableOrders=computed(()=>(salesOrders.value??[]).filter(r=>!['DRAFT','CANCELLED'].includes(r.status)&&Number(r.outstandingAmount)>0))
const filteredBills = computed(() => (bills.value ?? []).filter((row) =>
  (!filters.customerId || row.customerId === filters.customerId)
  && (!filters.status || row.status === filters.status)
  && (!filters.dueRange.length || (row.dueDate >= filters.dueRange[0] && row.dueDate <= filters.dueRange[1])),
))
const actionTitle=computed(()=>({DISCOUNT:'承兑贴现',MATURITY:'到期兑付',ENDORSE:'背书转让',BOUNCE:'退票追索'}[actionType.value]))
const discountFeePreview=computed(()=>Math.max(0,Number(actionTarget.value?.faceAmount||0)-Number(actionForm.netProceeds||0)))
const requiresBankAccount=computed(()=>actionType.value==='DISCOUNT'||actionType.value==='MATURITY'||(actionType.value==='BOUNCE'&&actionTarget.value?.status==='DISCOUNTED'))
function resetFilters(){filters.customerId=undefined;filters.status='';filters.dueRange=[]}
function openCreate(){Object.assign(createForm,{salesOrderId:Number(route.query.salesOrderId||0)||undefined,instrumentNo:'',billType:'BANK_ACCEPTANCE',faceAmount:'',issueDate:currentDateText(),dueDate:'',receivedDate:currentDateText(),drawer:'',acceptor:'',memo:''});createVisible.value=true}
async function refresh(){await Promise.all([queryClient.invalidateQueries({queryKey:['acceptance-bills']}),queryClient.invalidateQueries({queryKey:['sales-orders']}),queryClient.invalidateQueries({queryKey:['payments']}),queryClient.invalidateQueries({queryKey:['receivables']}),queryClient.invalidateQueries({queryKey:['dashboard']}),queryClient.invalidateQueries({queryKey:['partner-summaries']})])}
async function saveBill(){const amount=Number(createForm.faceAmount);if(!createForm.salesOrderId){ElMessage.warning('请选择销售订单');return}if(!createForm.instrumentNo.trim()||!createForm.acceptor.trim()||!createForm.dueDate){ElMessage.warning('请填写票号、承兑人和到期日期');return}if(!Number.isFinite(amount)||amount<=0){ElMessage.warning('请填写正确的票面金额');return}const ok=await ElMessageBox.confirm('收票后将立即按票面金额核销订单应收，但不会计入银行现金。','确认承兑收票',{type:'warning'}).then(()=>true).catch(()=>false);if(!ok)return;saving.value=true;try{await api.createAcceptanceBill({...createForm,faceAmount:amount});ElMessage.success('承兑票据已登记并核销应收');createVisible.value=false;await refresh()}finally{saving.value=false}}
function openAction(row:AcceptanceBillRow,type:typeof actionType.value){actionTarget.value=row;actionType.value=type;Object.assign(actionForm,{occurredDate:currentDateText(),netProceeds:'',bank:'',counterparty:'',businessBankAccountId:undefined,memo:''});actionVisible.value=true}
async function submitAction(){if(!actionTarget.value)return;const data={...actionForm,netProceeds:Number(actionForm.netProceeds||0)};if(actionType.value==='DISCOUNT'&&(!actionForm.bank.trim()||data.netProceeds<=0)){ElMessage.warning('请填写贴现银行和银行实收金额');return}if(requiresBankAccount.value&&!actionForm.businessBankAccountId){ElMessage.warning('请选择资金实际收付的企业银行账户');return}if(actionType.value==='ENDORSE'&&!actionForm.counterparty.trim()){ElMessage.warning('请填写被背书单位');return}if(actionType.value==='BOUNCE'&&!actionForm.memo.trim()){ElMessage.warning('请填写退票原因');return}acting.value=true;try{if(actionType.value==='DISCOUNT')await api.discountAcceptanceBill(actionTarget.value.id,data);if(actionType.value==='MATURITY')await api.matureAcceptanceBill(actionTarget.value.id,data);if(actionType.value==='ENDORSE')await api.endorseAcceptanceBill(actionTarget.value.id,data);if(actionType.value==='BOUNCE')await api.bounceAcceptanceBill(actionTarget.value.id,data);ElMessage.success(`${actionTitle.value}已登记`);actionVisible.value=false;await refresh()}finally{acting.value=false}}
async function openHistory(row:AcceptanceBillRow){historyTarget.value=row;events.value=await api.acceptanceBillEvents(row.id);historyVisible.value=true}
function billTypeName(v:string){return v==='COMMERCIAL_ACCEPTANCE'?'商业承兑':'银行承兑'}
function billStatusName(v:string){return ({HELD:'持有中',DISCOUNTED:'已贴现',MATURED:'已兑付',ENDORSED:'已背书',BOUNCED:'已退票'} as Record<string,string>)[v]??v}
function billStatusType(v:string){return v==='HELD'?'warning':v==='BOUNCED'?'danger':v==='MATURED'?'success':'info'}
function eventName(v:string){return ({RECEIVE:'收票',DISCOUNT:'贴现',MATURITY:'兑付',ENDORSE:'背书',BOUNCE:'退票'} as Record<string,string>)[v]??v}
function signedMoney(v:number){const n=Number(v||0);return `${n>=0?'+':'-'}${money(Math.abs(n))}`}
</script>
