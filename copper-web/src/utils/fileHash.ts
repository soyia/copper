export async function sha256File(file: Blob): Promise<string> {
  if (!globalThis.crypto?.subtle) throw new Error('当前浏览器不支持文件摘要校验')
  const digest = await globalThis.crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
}
