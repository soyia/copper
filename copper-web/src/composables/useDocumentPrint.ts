import { nextTick, ref } from 'vue'
import { currentDateTimeText } from '@/api/format'

export function useDocumentPrint(bodyClasses: string[] = []) {
  const printTime = ref(currentDateTimeText(new Date(), ' '))

  async function printDocument(title: string) {
    printTime.value = currentDateTimeText(new Date(), ' ')
    const originalTitle = document.title
    document.title = title
    document.body.classList.add('is-printing', ...bodyClasses)
    await nextTick()

    let cleaned = false
    const cleanup = () => {
      if (cleaned) return
      cleaned = true
      window.removeEventListener('afterprint', cleanup)
      document.body.classList.remove('is-printing', ...bodyClasses)
      document.title = originalTitle
    }
    window.addEventListener('afterprint', cleanup, { once: true })
    window.setTimeout(() => {
      window.print()
      window.setTimeout(cleanup, 1000)
    }, 50)
  }

  return { printTime, printDocument }
}

export function printFileName(...parts: string[]) {
  return parts
    .map((part) => String(part || '')
      .replace(/[\\/:*?"<>|]/g, '_')
      .replace(/\s+/g, ' ')
      .trim()
      .replace(/[. ]+$/g, ''))
    .filter(Boolean)
    .join('_')
    .slice(0, 160) || 'Copper_单据'
}
