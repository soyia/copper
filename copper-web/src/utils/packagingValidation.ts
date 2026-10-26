import type { PackagingRule } from '@/api/client'

export interface PackagingPolicyValues {
  baseUnit: string
  minimumSalesUnit: string
  minimumOutboundUnit: string
  defaultPurchaseUnit: string
  defaultSalesUnit: string
}

export interface PackagingValidationResult {
  rowErrors: string[][]
  policyErrors: string[]
  errors: string[]
}

export interface ActualWeightPackagingPolicy {
  baseUnit: string
  minimumSalesUnit: string
  minimumOutboundUnit: string
  defaultPurchaseUnit: string
  defaultSalesUnit: string
}

function unitKey(value?: string | null) {
  return String(value || '').trim().toLowerCase()
}

function positive(value?: number | null) {
  return typeof value === 'number' && Number.isFinite(value) && value > 0
}

export function validatePackagingConfiguration(
  policy: PackagingPolicyValues,
  rules: PackagingRule[],
  allowedParentUnits: string[] = [],
): PackagingValidationResult {
  const rowErrors = rules.map(() => [] as string[])
  const policyErrors: string[] = []
  const parentIndexes = new Map<string, number>()
  const allowedParents = new Set(allowedParentUnits.map(unitKey).filter(Boolean))
  const baseKey = unitKey(policy.baseUnit)

  rules.forEach((rule, index) => {
    const parent = unitKey(rule.parentUnit)
    const child = unitKey(rule.childUnit)
    if (!parent || !child) {
      rowErrors[index].push('请完整选择上级包装和下级单位')
      return
    }
    if (parent === child) rowErrors[index].push('上下级单位不能相同')
    if (allowedParents.size && !allowedParents.has(parent)) rowErrors[index].push(`“${rule.parentUnit}”不是当前系列的包装单位`)
    const duplicateIndex = parentIndexes.get(parent)
    if (duplicateIndex != null) {
      rowErrors[index].push(`“${rule.parentUnit}”已配置下级关系`)
      rowErrors[duplicateIndex].push(`“${rule.parentUnit}”只能配置一个下级关系`)
    } else {
      parentIndexes.set(parent, index)
    }
    if (rule.conversionMode === 'FIXED' && !positive(rule.fixedChildQuantity)) {
      rowErrors[index].push('固定换算的包含数量必须大于0')
    }
    if (rule.conversionMode === 'THEORETICAL' && !positive(rule.referenceBaseQuantity)) {
      rowErrors[index].push('理论换算必须填写参考库存数')
    }
    if ((rule.conversionMode === 'ACTUAL_WEIGHT' || rule.conversionMode === 'THEORETICAL') && child !== baseKey) {
      if (rule.conversionMode === 'ACTUAL_WEIGHT') {
        rowErrors[index].push(
          `“${rule.parentUnit}”是实物包装，“${rule.childUnit}”才是库存核算单位；请把库存核算主单位设为“${rule.childUnit}”`,
        )
      } else {
        rowErrors[index].push(`理论换算的下级必须是库存核算主单位“${policy.baseUnit}”`)
      }
    }
    if (rule.conversionMode !== 'FIXED' && rule.breakable) {
      rowErrors[index].push('只有固定数量包装可以拆分；实重包装必须整包流转')
    }
  })

  const cycleRows = new Set<number>()
  rules.forEach((_, startIndex) => {
    const pathPositions = new Map<string, number>()
    const pathIndexes: number[] = []
    let currentIndex: number | undefined = startIndex
    while (currentIndex != null) {
      const rule = rules[currentIndex]
      const parent = unitKey(rule.parentUnit)
      if (!parent) break
      if (pathPositions.has(parent)) {
        const cycleStart = pathPositions.get(parent) ?? 0
        pathIndexes.slice(cycleStart).forEach((index) => cycleRows.add(index))
        break
      }
      pathPositions.set(parent, pathIndexes.length)
      pathIndexes.push(currentIndex)
      currentIndex = parentIndexes.get(unitKey(rule.childUnit))
    }
  })
  cycleRows.forEach((index) => rowErrors[index].push('包装换算关系存在循环'))

  const hasFixedPathToBase = (unit: string, visiting = new Set<string>()): boolean => {
    const key = unitKey(unit)
    if (!key) return false
    if (key === baseKey) return true
    if (visiting.has(key)) return false
    visiting.add(key)
    const index = parentIndexes.get(key)
    if (index == null) return false
    const rule = rules[index]
    if (rule.conversionMode === 'THEORETICAL') return unitKey(rule.childUnit) === baseKey && positive(rule.referenceBaseQuantity)
    if (rule.conversionMode !== 'FIXED' || !positive(rule.fixedChildQuantity)) return false
    return hasFixedPathToBase(rule.childUnit, visiting)
  }

  rules.forEach((rule, index) => {
    if (rule.conversionMode === 'FIXED' && unitKey(rule.parentUnit) && !hasFixedPathToBase(rule.parentUnit)) {
      rowErrors[index].push(`无法完整换算到主库存单位“${policy.baseUnit}”`)
    }
  })

  const requireFixed = (unit: string, label: string) => {
    if (hasFixedPathToBase(unit)) return
    const rule = rules[parentIndexes.get(unitKey(unit)) ?? -1]
    if (rule?.conversionMode === 'ACTUAL_WEIGHT') {
      policyErrors.push(
        `${label}“${unit}”不能作为固定数量步长，因为每${unit}重量不同；这里请选择“${policy.baseUnit}”，整包收发由实重包装规则控制`,
      )
      return
    }
    policyErrors.push(`${label}“${unit}”必须有固定换算到“${policy.baseUnit}”的关系`)
  }
  const requireAllowed = (unit: string, label: string, field: 'purchaseAllowed' | 'salesAllowed' | 'outboundAllowed') => {
    if (unitKey(unit) === baseKey) return
    const index = parentIndexes.get(unitKey(unit))
    if (index == null) {
      policyErrors.push(`${label}“${unit}”未配置为包装单位`)
      return
    }
    if (!rules[index][field]) policyErrors.push(`${label}“${unit}”未开启对应业务用途`)
  }

  if (policy.minimumSalesUnit) requireFixed(policy.minimumSalesUnit, '最小销售单位')
  if (policy.minimumOutboundUnit) requireFixed(policy.minimumOutboundUnit, '最小发货单位')
  if (policy.defaultPurchaseUnit) requireAllowed(policy.defaultPurchaseUnit, '默认采购单位', 'purchaseAllowed')
  if (policy.defaultSalesUnit) requireAllowed(policy.defaultSalesUnit, '默认销售单位', 'salesAllowed')
  if (policy.minimumSalesUnit) requireAllowed(policy.minimumSalesUnit, '最小销售单位', 'salesAllowed')
  if (policy.minimumOutboundUnit) requireAllowed(policy.minimumOutboundUnit, '最小发货单位', 'outboundAllowed')

  const errors = [...new Set([...rowErrors.flat(), ...policyErrors])]
  return { rowErrors, policyErrors, errors }
}

export function applyActualWeightPackagingPreset(
  policy: ActualWeightPackagingPolicy,
  rules: PackagingRule[],
  packageUnit: string,
  baseUnit: string,
) {
  const normalizedPackageUnit = String(packageUnit || '').trim()
  const normalizedBaseUnit = String(baseUnit || '').trim()
  if (!normalizedPackageUnit || !normalizedBaseUnit || unitKey(normalizedPackageUnit) === unitKey(normalizedBaseUnit)) {
    return false
  }

  policy.baseUnit = normalizedBaseUnit
  policy.minimumSalesUnit = normalizedBaseUnit
  policy.minimumOutboundUnit = normalizedBaseUnit
  policy.defaultPurchaseUnit = normalizedBaseUnit
  policy.defaultSalesUnit = normalizedBaseUnit

  let rule = rules.find((item) => unitKey(item.parentUnit) === unitKey(normalizedPackageUnit))
  if (!rule) {
    rule = {
      ruleKey: `ACTUAL-WEIGHT-${Date.now()}`,
      parentUnit: normalizedPackageUnit,
      childUnit: normalizedBaseUnit,
      conversionMode: 'ACTUAL_WEIGHT',
      fixedChildQuantity: null,
      referenceBaseQuantity: null,
      breakable: false,
      repackable: false,
      purchaseAllowed: true,
      salesAllowed: true,
      outboundAllowed: true,
      integerOnly: true,
      sortNo: (rules.length + 1) * 10,
      status: 'ENABLED',
    } as PackagingRule
    rules.push(rule)
  }

  Object.assign(rule, {
    childUnit: normalizedBaseUnit,
    conversionMode: 'ACTUAL_WEIGHT',
    fixedChildQuantity: null,
    breakable: false,
    repackable: false,
    purchaseAllowed: true,
    salesAllowed: true,
    outboundAllowed: true,
    integerOnly: true,
    status: 'ENABLED',
  })
  return true
}

export function packagingHierarchyPaths(rules: PackagingRule[]) {
  const completeRules = rules.filter((rule) => unitKey(rule.parentUnit) && unitKey(rule.childUnit))
  const parentMap = new Map(completeRules.map((rule) => [unitKey(rule.parentUnit), rule]))
  const childKeys = new Set(completeRules.map((rule) => unitKey(rule.childUnit)))
  const roots = completeRules.filter((rule) => !childKeys.has(unitKey(rule.parentUnit)))
  const starts = roots.length ? roots : completeRules
  const rendered = new Set<string>()

  return starts.flatMap((start) => {
    const startKey = unitKey(start.parentUnit)
    if (rendered.has(startKey)) return []
    const parts = [start.parentUnit]
    const visiting = new Set<string>()
    let rule: PackagingRule | undefined = start
    while (rule) {
      const parent = unitKey(rule.parentUnit)
      if (visiting.has(parent)) {
        parts.push('↻ 循环')
        break
      }
      visiting.add(parent)
      rendered.add(parent)
      if (rule.conversionMode === 'FIXED') parts.push(`→ ${rule.fixedChildQuantity || '?'} ${rule.childUnit}`)
      else if (rule.conversionMode === 'ACTUAL_WEIGHT') parts.push(`→ 实际称重 ${rule.childUnit}`)
      else parts.push(`→ 约 ${rule.referenceBaseQuantity || '?'} ${rule.childUnit}`)
      rule = parentMap.get(unitKey(rule.childUnit))
    }
    return [parts.join(' ')]
  })
}

export function packagingErrorMessage(error: unknown, fallback = '计量包装保存失败') {
  const responseMessage = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  return responseMessage || (error instanceof Error ? error.message : fallback)
}
