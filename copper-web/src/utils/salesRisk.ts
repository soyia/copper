import { ElMessageBox } from 'element-plus'
import type { SalesRiskEvaluation } from '@/api/client'
import { resolveRiskException } from '@/utils/riskException'

export async function resolveSalesRiskOverride(
  evaluation: SalesRiskEvaluation,
  actionLabel: string,
): Promise<{ allowed: boolean; reason?: string; approvalId?: number }> {
  if (!evaluation.blocked) return { allowed: true }
  return resolveRiskException(
    evaluation.documentType,
    evaluation.documentId,
    evaluation.action,
    actionLabel,
  )
}

export async function promptSalesRiskOverrideFromBlockedMessage(
  message: string,
  actionLabel: string,
): Promise<string | undefined> {
  const result = await ElMessageBox.prompt(
    `${message}\n\n本次授权只对当前变更版本有效，不会修改客户长期策略。请填写明确原因。`,
    `老板授权${actionLabel}例外`,
    {
      type: 'warning',
      confirmButtonText: '授权并重试',
      cancelButtonText: '返回调整',
      inputType: 'textarea',
      inputPlaceholder: '至少8个字，说明为何本次可以例外放行',
      inputValidator: (value) => {
        const reason = String(value || '').trim()
        if (reason.length < 8) return '授权原因至少填写8个字'
        if (reason.length > 500) return '授权原因不能超过500字'
        return true
      },
    },
  ).catch(() => null)
  return result?.value.trim()
}
