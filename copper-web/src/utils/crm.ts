export const crmStatusName = (value?: string | null) => ({
  OPEN: '待跟进', QUALIFIED: '已转潜在客户', DISQUALIFIED: '已判无效',
  PROSPECT: '潜在客户', ACTIVE: '活跃客户', DORMANT: '沉睡客户', LOST: '已流失',
  QUALIFY: '资格确认', NEEDS: '需求分析', PROPOSAL: '方案报价', NEGOTIATION: '商务谈判', WON: '已成交',
  DRAFT: '草稿', ACCEPTED: '客户接受', REJECTED: '客户拒绝', CANCELLED: '已作废', REVISED: '已修订',
  COMPLETED: '已完成',
} as Record<string, string>)[value || ''] || value || '-'

export const crmStatusType = (value?: string | null) => {
  if (['WON', 'QUALIFIED', 'ACCEPTED', 'COMPLETED', 'ACTIVE'].includes(value || '')) return 'success'
  if (['LOST', 'DISQUALIFIED', 'REJECTED', 'CANCELLED'].includes(value || '')) return 'danger'
  if (['NEGOTIATION', 'ACTIVE', 'OPEN'].includes(value || '')) return 'warning'
  return 'info'
}

export const crmActivityName = (value?: string | null) => ({
  CALL: '电话', VISIT: '拜访', WECHAT: '微信', EMAIL: '邮件', MEETING: '会议', OTHER: '其他',
} as Record<string, string>)[value || ''] || value || '-'

export const crmSourceName = (value?: string | null) => ({
  REFERRAL: '客户转介绍', PHONE: '电话咨询', WECHAT: '微信咨询', STORE: '门店来访', PLATFORM: '平台获客', OTHER: '其他',
} as Record<string, string>)[value || ''] || value || '-'

export const crmTierName = (value?: string | null) => ({ KEY: '重点客户', GROWTH: '成长客户', NORMAL: '普通客户', OBSERVE: '观察客户' } as Record<string, string>)[value || ''] || value || '-'

export const crmEventName = (value?: string | null) => ({
  LEAD_CREATED: '创建线索', LEAD_UPDATED: '更新线索', LEAD_QUALIFIED: '转为潜在客户', LEAD_DISQUALIFIED: '判定无效', LEAD_REACTIVATED: '重新激活线索',
  CUSTOMER_PROFILE_CREATED: '建立客户经营档案', CUSTOMER_PROFILE_UPDATED: '更新客户经营档案',
  OPPORTUNITY_CREATED: '创建商机', OPPORTUNITY_UPDATED: '更新商机', OPPORTUNITY_STAGE_CHANGED: '推进商机阶段', OPPORTUNITY_REACTIVATED: '重新激活商机',
  QUOTE_CREATED: '创建报价', QUOTE_UPDATED: '更新报价草稿', QUOTE_ACTIVATED: '报价生效', QUOTE_REVISED: '修订原报价', QUOTE_REVISION_CREATED: '创建报价新版本',
  QUOTE_ACCEPTED: '客户接受报价', QUOTE_REJECTED: '客户拒绝报价', QUOTE_CANCELLED: '报价作废', QUOTE_CONVERTED_TO_SALES_ORDER: '转为销售订单草稿',
  ACTIVITY_RECORDED: '记录销售活动', FOLLOWUP_PLAN_CREATED: '创建跟进计划', FOLLOWUP_PLAN_UPDATED: '更新跟进计划', FOLLOWUP_PLAN_COMPLETED: '完成跟进计划', FOLLOWUP_PLAN_CANCELLED: '取消跟进计划',
} as Record<string, string>)[value || ''] || value || '-'

export const moneyText = (value?: number | null) => Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

export const dateTimeText = (value?: string | null) => value ? value.replace('T', ' ').slice(0, 16) : '-'
