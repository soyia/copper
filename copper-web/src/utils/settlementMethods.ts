export type SettlementMethod = '' | 'BANK' | 'CASH' | 'OFFSET' | 'ADVANCE' | 'REWARD'

export const cashSettlementMethodOptions = [
  { label: '银行转账', value: 'BANK' },
  { label: '现金', value: 'CASH' },
] as const

export const accountSettlementMethodOptions = [
  { label: '预收/预付余额', value: 'ADVANCE' },
  { label: '奖励/返利余额', value: 'REWARD' },
] as const

export function settlementMethodOptions(hasSource: boolean) {
  return hasSource
    ? [...cashSettlementMethodOptions, ...accountSettlementMethodOptions]
    : [...cashSettlementMethodOptions]
}
