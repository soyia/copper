import type { Address } from '@/api/client'
import { formatPhone } from '@/api/format'

export function receivingAddressOptions(addresses: Address[] | undefined, selectedAddressId?: number) {
  return (addresses ?? []).filter((item) =>
    item.status === 'ENABLED' && (item.addressType === 'RECEIVING' || item.id === selectedAddressId),
  )
}

export function partnerAddressOptionLabel(item: Address) {
  const type = item.addressType === 'RECEIVING' ? '收货' : addressTypeName(item.addressType)
  return [item.firstChoice ? '默认' : '', type, item.name, item.detail, item.contactPerson, item.phone ? formatPhone(item.phone) : '']
    .filter(Boolean)
    .join(' / ')
}

function addressTypeName(value: string) {
  return ({
    SHIPPING: '送货',
    PICKUP: '自提',
    INVOICE: '开票',
    REGISTERED: '注册',
    OFFICE: '办公',
    OTHER: '其他',
  } as Record<string, string>)[value] || value
}
