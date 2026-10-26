import type { ProductPurchaseUnitOption } from '@/api/client'

function unitKey(value?: string | null) {
  return String(value || '').trim().toLowerCase()
}

export function mergeUnitOptions(
  priceUnits: ProductPurchaseUnitOption[],
  packageUnits: ProductPurchaseUnitOption[],
  allowedPackageNames: string[],
) {
  const allowed = new Set(allowedPackageNames.map(unitKey).filter(Boolean))
  const merged = priceUnits.map((item) => ({ ...item }))
  for (const option of packageUnits) {
    if (!allowed.has(unitKey(option.name))) continue
    const existing = merged.find((item) => unitKey(item.name) === unitKey(option.name))
    if (existing) {
      existing.unitId ??= option.unitId
      existing.exchangeRate ??= option.exchangeRate
      continue
    }
    merged.push({ ...option, primaryUnit: false })
  }
  return merged
}

export function convertUnitPrice(
  unitPrice: number,
  fromUnit: string,
  toUnit: string,
  options: ProductPurchaseUnitOption[],
) {
  if (unitKey(fromUnit) === unitKey(toUnit)) return unitPrice
  const fromFactor = options.find((item) => unitKey(item.name) === unitKey(fromUnit)
    && item.exchangeRate != null && item.exchangeRate > 0)?.exchangeRate
  const toFactor = options.find((item) => unitKey(item.name) === unitKey(toUnit)
    && item.exchangeRate != null && item.exchangeRate > 0)?.exchangeRate
  if (fromFactor == null || fromFactor <= 0 || toFactor == null || toFactor <= 0) return 0
  return unitPrice * toFactor / fromFactor
}

export function hasUnitOption(options: ProductPurchaseUnitOption[], unit: string) {
  return options.some((item) => unitKey(item.name) === unitKey(unit))
}
