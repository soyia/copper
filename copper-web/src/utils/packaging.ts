import type { PackagingProfile, PackagingPurpose } from '@/api/client'

export function packagingUnits(profile: PackagingProfile | undefined, purpose: PackagingPurpose) {
  if (!profile) return []
  const purchasePurpose = purpose === 'PURCHASE' || purpose === 'PURCHASE_ORDER' || purpose === 'INBOUND'
  const allowed = profile.rules
    .filter((rule) => {
      if (purchasePurpose) return rule.purchaseAllowed
      if (purpose === 'OUTBOUND') return rule.outboundAllowed
      return rule.salesAllowed
    })
    .flatMap((rule) => [rule.parentUnit, rule.childUnit])
  const defaults = purchasePurpose
    ? [profile.defaultPurchaseUnit]
    : purpose === 'OUTBOUND'
      ? [profile.minimumOutboundUnit]
      : [profile.defaultSalesUnit, profile.minimumSalesUnit]
  return [...new Set([...defaults, ...allowed, profile.baseUnit].map((item) => item?.trim()).filter(Boolean))]
}

export function requiresActualBaseQuantity(profile: PackagingProfile | undefined, unit: string) {
  return profile?.rules.some((rule) => rule.parentUnit === unit && rule.conversionMode === 'ACTUAL_WEIGHT') ?? false
}

export function defaultQuantityUnit(profile: PackagingProfile | undefined, purpose: PackagingPurpose) {
  if (!profile) return ''
  if (purpose === 'PURCHASE' || purpose === 'PURCHASE_ORDER' || purpose === 'INBOUND') return profile.defaultPurchaseUnit || profile.baseUnit
  if (purpose === 'OUTBOUND') return profile.minimumOutboundUnit || profile.baseUnit
  return profile.defaultSalesUnit || profile.minimumSalesUnit || profile.baseUnit
}
