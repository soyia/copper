export function roundMoney(value: number) {
  return Math.round((Number(value || 0) + Number.EPSILON) * 100) / 100
}

export function grossFromEntered(amount: number, priceIncludesTax: boolean, taxRate: number) {
  const entered = roundMoney(amount)
  return priceIncludesTax ? entered : roundMoney(entered + entered * Number(taxRate || 0) / 100)
}

export function splitGross(total: number, taxRate: number) {
  const gross = roundMoney(total)
  const rate = Number(taxRate || 0)
  const net = rate > 0 ? roundMoney(gross / (1 + rate / 100)) : gross
  return { amountExcludingTax: net, taxAmount: roundMoney(gross - net), totalAmount: gross }
}
