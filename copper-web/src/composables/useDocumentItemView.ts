import { ref, watch } from 'vue'

export type DocumentItemViewMode = 'COMPACT' | 'MULTILINE' | 'TABLE'

const STORAGE_KEY = 'copper.document-item-view-mode'

function initialMode(): DocumentItemViewMode {
  if (typeof window === 'undefined') return 'MULTILINE'
  const stored = window.localStorage.getItem(STORAGE_KEY)
  if (stored === 'TABLE' || stored === 'MULTILINE' || stored === 'COMPACT') return stored
  return 'COMPACT'
}

const itemViewMode = ref<DocumentItemViewMode>(initialMode())

if (typeof window !== 'undefined') {
  watch(itemViewMode, (value) => window.localStorage.setItem(STORAGE_KEY, value))
}

export const documentItemViewOptions = [
  { label: '精简视图', value: 'COMPACT' },
  { label: '多行视图', value: 'MULTILINE' },
  { label: '完整表格', value: 'TABLE' },
]

export function useDocumentItemView() {
  return { itemViewMode, documentItemViewOptions }
}
