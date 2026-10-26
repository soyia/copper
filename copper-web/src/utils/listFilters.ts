export type DateRange = string[] | null | undefined

export function normalizeText(value: unknown) {
  return String(value ?? '').trim().toLowerCase()
}

export function matchesKeyword<T>(row: T, keyword: string, fields: Array<keyof T | ((row: T) => unknown)>) {
  const text = normalizeText(keyword)
  if (!text) return true
  return fields.some((field) => {
    const value = typeof field === 'function' ? field(row) : row[field]
    return normalizeText(value).includes(text)
  })
}

export function matchesDateRange(value: unknown, range: DateRange) {
  if (!range || range.length !== 2 || !range[0] || !range[1]) return true
  const date = String(value ?? '')
  if (!date) return false
  return date >= range[0] && date <= range[1]
}

export function isAll(value: unknown) {
  return value === undefined || value === null || value === '' || value === 'ALL'
}
