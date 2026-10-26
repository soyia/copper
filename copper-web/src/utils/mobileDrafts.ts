const MOBILE_DRAFT_SUFFIXES = ['mobile-quick-sale', 'mobile-stocktake'] as const

export function mobileDraftKey(userId: number | undefined, suffix: typeof MOBILE_DRAFT_SUFFIXES[number]) {
  return `copper-session-draft:v1:${userId || 0}:${suffix}`
}

export function clearMobileDrafts(userId: number | undefined) {
  for (const suffix of MOBILE_DRAFT_SUFFIXES) {
    window.sessionStorage.removeItem(mobileDraftKey(userId, suffix))
  }
}
