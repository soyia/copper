import { ref } from 'vue'
import { useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { api, type EvidenceDocumentType } from '@/api/client'
import { useDocumentPrint } from '@/composables/useDocumentPrint'
import { sha256Text } from '@/utils/sha256Text'

function stableSerialize(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value)
  if (Array.isArray(value)) return `[${value.map(stableSerialize).join(',')}]`
  const record = value as Record<string, unknown>
  return `{${Object.keys(record).sort().map((key) => `${JSON.stringify(key)}:${stableSerialize(record[key])}`).join(',')}}`
}

async function sha256Snapshot(value: unknown) {
  return sha256Text(stableSerialize(value))
}

export function useAuditedDocumentPrint(
  documentType: EvidenceDocumentType,
  documentId: () => number,
  bodyClasses: string[] = ['is-a4-printing'],
) {
  const queryClient = useQueryClient()
  const snapshotHash = ref('')
  const printing = ref(false)
  const { printTime, printDocument } = useDocumentPrint(bodyClasses)

  async function printAndRecord(fileName: string, snapshot: unknown, templateVersion: string) {
    printing.value = true
    try {
      snapshotHash.value = await sha256Snapshot(snapshot)
      const id = documentId()
      await api.recordEvidenceOutput(documentType, id, {
        outputType: 'PRINT_OR_PDF', templateVersion, outputFileName: `${fileName}.pdf`, snapshotHash: snapshotHash.value,
      })
      await queryClient.invalidateQueries({ queryKey: ['evidence-output-records', documentType, id] })
      await printDocument(fileName)
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '打印留痕失败，尚未打开打印窗口')
    } finally {
      printing.value = false
    }
  }

  return { printTime, snapshotHash, printing, printAndRecord }
}
