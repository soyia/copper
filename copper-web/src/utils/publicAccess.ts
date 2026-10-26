function isPrivateIpv4(hostname: string) {
  const parts = hostname.split('.').map(Number)
  if (parts.length !== 4 || parts.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) return false
  return parts[0] === 10
    || parts[0] === 127
    || (parts[0] === 169 && parts[1] === 254)
    || (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31)
    || (parts[0] === 192 && parts[1] === 168)
}

export function externalPublicOrigin() {
  const configured = String(import.meta.env.VITE_EXTERNAL_PUBLIC_ORIGIN || '').trim()
  if (!configured) return ''
  try {
    const url = new URL(configured)
    const hostname = url.hostname.toLowerCase()
    if (url.protocol !== 'https:' || url.username || url.password || url.pathname !== '/' || url.search || url.hash) return ''
    if (hostname === 'localhost' || hostname === '::1' || hostname.endsWith('.local') || isPrivateIpv4(hostname)) return ''
    return url.origin
  } catch {
    return ''
  }
}
