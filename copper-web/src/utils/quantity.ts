/** Business quantities follow the API's six decimal places; money keeps its own scale. */
export type QuantityValue = number | string | null | undefined
const SCALE = 1_000_000n

/** Decimal HALF_UP rounding, including exponent notation returned by Number#toString. */
export function quantityUnits(value: QuantityValue): bigint {
  const text = String(value ?? 0).trim() || '0'
  if (!Number.isFinite(Number(text))) throw new TypeError('数量必须为有限数字')
  const match = text.match(/^([+-]?)(\d*)(?:\.(\d*))?(?:e([+-]?\d+))?$/i)
  if (!match || !(match[2] || match[3])) throw new TypeError('数量格式不正确')
  const digits = BigInt((match[2] || '0') + (match[3] || ''))
  const shift = 6 + Number(match[4] || 0) - (match[3]?.length || 0)
  const divisor = shift < 0 ? 10n ** BigInt(-shift) : 1n
  const units = shift < 0
    ? (digits + divisor / 2n) / divisor
    : digits * 10n ** BigInt(shift)
  return match[1] === '-' ? -units : units
}

export function quantityFromUnits(units: bigint): number {
  return Number(units) / Number(SCALE)
}

export function roundQuantity(value: QuantityValue): number {
  return quantityFromUnits(quantityUnits(value))
}

export function sumQuantities(values: QuantityValue[]): number {
  return quantityFromUnits(values.reduce<bigint>((total, value) => total + quantityUnits(value), 0n))
}

export function sameQuantity(left: QuantityValue, right: QuantityValue): boolean {
  return quantityUnits(left) === quantityUnits(right)
}

export function subtractQuantities(left: QuantityValue, right: QuantityValue): number {
  return quantityFromUnits(quantityUnits(left) - quantityUnits(right))
}

/** OCR labels report kg; only known metric mass units may receive that value. */
export function kilogramsToBaseQuantity(value: QuantityValue, baseUnit: string): number {
  const unit = baseUnit.trim().toLowerCase()
  const factors: Record<string, number> = { kg: 1, 千克: 1, 公斤: 1, t: 0.001, 吨: 0.001, g: 1000, 克: 1000 }
  const factor = factors[unit]
  if (factor == null) throw new TypeError(`标签重量为 kg，不能自动换算到“${baseUnit || '未设置单位'}”，请按库存单位人工录入`)
  return roundQuantity(Number(value || 0) * factor)
}
