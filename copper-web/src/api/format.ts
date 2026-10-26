export function money(value: number | string | null | undefined, digits = 2) {
  const n = Number(value ?? 0)
  return n.toLocaleString('zh-CN', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  })
}

export function quantity(value: number | string | null | undefined, digits = 6) {
  const n = Number(value ?? 0)
  return n.toLocaleString('zh-CN', {
    minimumFractionDigits: 0,
    maximumFractionDigits: digits,
  })
}

export function currentDateText(date = new Date()) {
  return formatDateParts(date)
}

export function currentDateTimeText(date = new Date(), separator: 'T' | ' ' = 'T') {
  return `${formatDateParts(date)}${separator}${formatTimeParts(date)}`
}

export function formatDate(value: string | Date | null | undefined) {
  if (!value) return '-'
  if (value instanceof Date) return formatDateParts(value)

  const text = String(value).trim()
  if (!text) return '-'

  const dateOnly = text.match(/^(\d{4})-(\d{2})-(\d{2})/)
  if (dateOnly) return `${dateOnly[1]}-${dateOnly[2]}-${dateOnly[3]}`

  const parsed = Date.parse(text)
  return Number.isNaN(parsed) ? text : formatDateParts(new Date(parsed))
}

export function formatDateTime(value: string | Date | null | undefined) {
  if (!value) return '-'
  if (value instanceof Date) return currentDateTimeText(value, ' ')

  const text = String(value).trim()
  if (!text) return '-'

  const hasTimezone = /(Z|[+-]\d{2}:?\d{2})$/i.test(text)
  const localDateTime = text.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?/)
  if (localDateTime && !hasTimezone) {
    const seconds = localDateTime[6] ?? '00'
    return `${localDateTime[1]}-${localDateTime[2]}-${localDateTime[3]} ${localDateTime[4]}:${localDateTime[5]}:${seconds}`
  }

  const parsed = Date.parse(text)
  if (!Number.isNaN(parsed)) return currentDateTimeText(new Date(parsed), ' ')

  return text.replace('T', ' ')
}

export function formatDateColumn(row: Record<string, unknown>, column: { property?: string }) {
  return formatDate(column.property ? (row[column.property] as string | Date | null | undefined) : undefined)
}

export function formatDateTimeColumn(row: Record<string, unknown>, column: { property?: string }) {
  return formatDateTime(column.property ? (row[column.property] as string | Date | null | undefined) : undefined)
}

export function formatExpiryDistance(expiresAtValue: string, status?: string) {
  if (status === 'REVOKED') return '已撤销'
  const expiresAt = new Date(expiresAtValue).getTime()
  if (!Number.isFinite(expiresAt)) return '-'
  const now = Date.now()
  if (expiresAt <= now) return '已过期'
  let minutes = Math.floor((expiresAt - now) / 60000)
  const days = Math.floor(minutes / 1440)
  minutes %= 1440
  const hours = Math.floor(minutes / 60)
  minutes %= 60
  const parts = []
  if (days) parts.push(`${days}天`)
  if (hours || days) parts.push(`${hours}小时`)
  parts.push(`${minutes}分钟`)
  return `还有 ${parts.join('')}`
}

export function formatPhone(value: string | null | undefined) {
  const text = String(value ?? '').trim()
  if (!text) return '-'
  const digits = text.replace(/\D/g, '')
  if (digits.length === 11 && /^[+\d\s\-()]+$/.test(text)) {
    return formatMobileDigits(digits)
  }
  return text.replace(/(^|\D)(1\d{10})(?=\D|$)/g, (_match, prefix: string, mobile: string) => `${prefix}${formatMobileDigits(mobile)}`)
}

function formatMobileDigits(value: string) {
  return `${value.slice(0, 3)} ${value.slice(3, 7)} ${value.slice(7)}`
}

function formatDateParts(date: Date) {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
}

function formatTimeParts(date: Date) {
  return `${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`
}

function pad2(value: number) {
  return String(value).padStart(2, '0')
}

export function statusName(status: string | null | undefined) {
  const value = String(status ?? '').trim()
  if (!value) return '-'
  if (value.toUpperCase() === 'ENABLED') return '启用'
  if (value.toUpperCase() === 'DISABLED') return '不可用'
  return value
}

export function paymentStatus(status: string) {
  if (status === 'PAID') return '已结清'
  if (status === 'PARTIAL') return '部分'
  return '未结清'
}

export function paymentStatusType(status: string) {
  if (status === 'PAID') return 'success'
  if (status === 'PARTIAL') return 'warning'
  return 'danger'
}

export function purchaseStatus(status: string) {
  if (status === 'DRAFT') return '暂存'
  if (status === 'RECEIVED') return '已到齐'
  if (status === 'PARTIAL_RECEIVED') return '部分到货'
  if (status === 'CANCELLED') return '已作废'
  return '已订货'
}

export function purchaseStatusType(status: string) {
  if (status === 'DRAFT') return 'info'
  if (status === 'RECEIVED') return 'success'
  if (status === 'PARTIAL_RECEIVED') return 'warning'
  if (status === 'CANCELLED') return 'info'
  return 'primary'
}

export function salesOrderStatus(status: string) {
  if (status === 'DRAFT') return '暂存'
  if (status === 'OPEN') return '已确认'
  if (status === 'PARTIAL_SHIPPED') return '部分出库'
  if (status === 'SHIPPED') return '已出库'
  if (status === 'CANCELLED') return '已作废'
  return statusName(status)
}

export function salesOrderStatusType(status: string) {
  if (status === 'DRAFT' || status === 'CANCELLED') return 'info'
  if (status === 'SHIPPED') return 'success'
  if (status === 'PARTIAL_SHIPPED') return 'warning'
  return 'primary'
}

export function purchaseSource(sourceType: string) {
  const names: Record<string, string> = {
    FORMAL: '正式订单',
    PURCHASE_ORDER: '采购订单',
    NO_ORDER: '无订单',
    PHONE: '电话',
    WECHAT: '微信',
    EMAIL: '邮件',
    ORAL: '口头',
    ONSITE: '现场',
    OTHER: '其他',
  }
  return names[sourceType] ?? sourceType
}

export function paymentMethodName(method: string) {
  const names: Record<string, string> = {
    BANK: '银行转账',
    CASH: '现金',
    OFFSET: '往来抵消',
    ADVANCE: '预收/预付余额',
    REWARD: '奖励/返利余额',
    ACCEPTANCE: '承兑票据',
  }
  return names[method] ?? method
}

export function accountTypeName(type: string) {
  const names: Record<string, string> = {
    CUSTOMER_ADVANCE: '客户预收款',
    CUSTOMER_REWARD: '客户奖励金',
    SUPPLIER_PREPAY: '供应商预付款',
    SUPPLIER_REWARD: '供应商返利金',
  }
  return names[type] ?? (type || '-')
}

export function accountActionName(action: string) {
  return action === 'DECREASE' ? '扣减' : '增加'
}
