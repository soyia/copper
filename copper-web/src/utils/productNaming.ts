import type { ProductSeries, SeriesField } from '@/api/client'

export type ProductNamingValue = string | number | null | undefined

export interface ProductNamingFallback {
  nameText?: string
}

type ProductNamingPart = {
  field: SeriesField
  value: ProductNamingValue
}

function referenceTypeForField(field: SeriesField) {
  const names = [field.fieldName, field.displayName].map((item) => String(item || '').replace(/\s/g, ''))
  if (names.some((name) => ['牌号', '材质牌号', '牌号/材质', '磁材牌号'].includes(name))) return 'GRADE'
  if (names.some((name) => ['材料状态', '状态', '软硬状态'].includes(name))) return 'TEMPER'
  if (names.some((name) => ['执行标准', '标准'].includes(name))) return 'STANDARD'
  return undefined
}

function isBlank(value: ProductNamingValue) {
  return value === undefined || value === null || String(value).trim() === ''
}

function numberText(value: ProductNamingValue, field: SeriesField) {
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return String(value ?? '').trim()
  const scale = Math.max(2, field.decimalScale ?? 2)
  return numeric.toFixed(scale).replace(/(\.\d{2}.*?)0+$/, '$1')
}

function valueText(part: ProductNamingPart) {
  return part.field.fieldType === 'NUMBER'
    ? numberText(part.value, part.field)
    : String(part.value ?? '').trim()
}

function dimensionsText(parts: ProductNamingPart[]) {
  if (!parts.length) return ''
  const sameUnit = parts.every((part) => (part.field.unitName || '') === (parts[0].field.unitName || ''))
  if (sameUnit) {
    const values = parts.map(valueText).join('×')
    return `${values}${parts[0].field.unitName || ''}`
  }
  return parts.map((part) => `${valueText(part)}${part.field.unitName || ''}`).join('×')
}

export function generateProductName(
  series: ProductSeries,
  fields: SeriesField[],
  values: Record<number, ProductNamingValue>,
  fallback: ProductNamingFallback = {},
) {
  const populated = fields
    .map((field) => ({ field, value: values[field.id] }))
    .filter((part) => !isBlank(part.value))

  const grade = populated.find(({ field }) => referenceTypeForField(field) === 'GRADE')
  const temper = populated.find(({ field }) => referenceTypeForField(field) === 'TEMPER')
  const dimensions = populated.filter(({ field }) =>
    field.skuDimension && !referenceTypeForField(field))
  const otherIdentity = dimensions.filter(({ field }) => field.fieldType !== 'NUMBER')
  const numericDimensions = dimensions.filter(({ field }) => field.fieldType === 'NUMBER')

  const nameParts = [
    series.name,
    grade ? valueText(grade) : '',
    temper ? valueText(temper) : '',
    dimensionsText(numericDimensions),
    ...otherIdentity.map((part) => `${valueText(part)}${part.field.unitName || ''}`),
  ].filter(Boolean)

  if (nameParts.length > 1) return nameParts.join(' ')

  const specParts = populated
    .filter(({ field }) => field.includeInSpec)
    .map(({ field, value }) => `${field.displayName || field.fieldName}${value}${field.unitName || ''}`)
  const nameText = fallback.nameText || specParts.join(' ')
  return [series.name, nameText].filter(Boolean).join(' ')
}
