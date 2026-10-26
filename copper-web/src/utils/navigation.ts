import type { RouteLocationNormalizedLoaded, RouteLocationRaw } from 'vue-router'

export function returnPath(route: RouteLocationNormalizedLoaded, fallback: string) {
  const from = route.query.from
  if (typeof from === 'string' && from.startsWith('/') && !from.startsWith('//')) {
    return from
  }
  return fallback
}

export function relatedDetailRoute(route: RouteLocationNormalizedLoaded, path: string): RouteLocationRaw {
  return {
    path,
    query: { from: route.fullPath },
  }
}
