import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'

export interface RiskExceptionAuthorization {
  allowed: boolean
  reason?: string
  approvalId?: number
}

async function promptReason(title: string, message: string, confirmButtonText: string, minimum = 8) {
  const result = await ElMessageBox.prompt(message, title, {
    type: 'warning',
    inputType: 'textarea',
    confirmButtonText,
    cancelButtonText: '返回检查',
    inputPlaceholder: '说明业务依据、已核实事项和本次例外原因',
    inputValidator: (value) => {
      const reason = String(value || '').trim()
      if (reason.length < minimum) return `原因至少填写${minimum}个字`
      if (reason.length > 500) return '原因不能超过500字'
      return true
    },
  }).catch(() => null)
  return result?.value.trim()
}

export async function resolveRiskException(
  sourceType: string,
  sourceId: number,
  actionCode: string,
  actionLabel: string,
  existingReason?: string,
): Promise<RiskExceptionAuthorization> {
  const status = await api.riskExceptionSourceStatus({ sourceType, sourceId, actionCode })
  if (!status.required) return { allowed: true }

  if (status.activeRequest?.status === 'APPROVED') {
    return { allowed: true, approvalId: status.activeRequest.id }
  }
  if (status.activeRequest?.status === 'PENDING') {
    await ElMessageBox.alert(
      `申请 ${status.activeRequest.requestNo} 正在等待负责人处理。批准只允许本次单据快照执行；单据内容变化后必须重新申请。`,
      `${actionLabel}尚未获批`,
      { type: 'info', confirmButtonText: '我知道了' },
    )
    return { allowed: false }
  }

  if (status.canDirectApprove) {
    const reason = existingReason && existingReason.trim().length >= 8 ? existingReason.trim() : await promptReason(
      `负责人确认${actionLabel}`,
      `${status.reason}\n\n这是单级例外授权，只对当前单据、金额和动作有效，不改变长期策略。`,
      '授权并继续',
    )
    return reason ? { allowed: true, reason } : { allowed: false }
  }

  const reason = existingReason && existingReason.trim().length >= 8 ? existingReason.trim() : await promptReason(
    `申请${actionLabel}例外`,
    `${status.reason}\n\n提交后不会自动${actionLabel}，负责人批准后仍需回到原单据执行。`,
    '提交负责人审批',
  )
  if (!reason) return { allowed: false }
  const request = await api.submitRiskException({ sourceType, sourceId, actionCode, reason })
  ElMessage.success(`已提交 ${request.requestNo}，批准后请回到原单据继续`)
  return { allowed: false }
}

export async function submitSupplierPaymentException(payment: unknown, amount: number) {
  const reason = await promptReason(
    '申请大额付款例外',
    `本次付款 ¥${Number(amount || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2 })} 达到负责人审批阈值。\n\n提交的是不可变付款意向，批准后由具备付款权限的人员在审批详情执行。`,
    '提交负责人审批',
  )
  if (!reason) return null
  const request = await api.submitSupplierPaymentException(payment, reason)
  ElMessage.success(`已提交 ${request.requestNo}，尚未发生实际付款`)
  return request
}
