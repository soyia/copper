type NavigatorWithUserAgentData = Navigator & {
  userAgentData?: {
    mobile?: boolean
  }
}

export function isPhoneBrowser(
  navigatorValue: NavigatorWithUserAgentData = window.navigator,
  viewportWidth = window.innerWidth,
) {
  const reportedMobile = navigatorValue.userAgentData?.mobile
  if (typeof reportedMobile === 'boolean') return reportedMobile

  const phoneUserAgent = /Android.+Mobile|iPhone|iPod|IEMobile|Windows Phone|Opera Mini|Mobi/i
  return viewportWidth <= 1024 && phoneUserAgent.test(navigatorValue.userAgent)
}

export function resolvePostLoginPath(requestedRedirect: unknown, phoneBrowser = isPhoneBrowser()) {
  const safeRedirect = typeof requestedRedirect === 'string'
    && requestedRedirect.startsWith('/')
    && !requestedRedirect.startsWith('//')
    ? requestedRedirect
    : ''

  if (phoneBrowser) {
    return safeRedirect.startsWith('/mobile') ? safeRedirect : '/mobile'
  }
  return safeRedirect || '/dashboard'
}
