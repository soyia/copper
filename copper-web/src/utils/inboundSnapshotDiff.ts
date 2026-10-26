import type {
  InboundReviewSnapshot,
  InboundReviewSnapshotAttachment,
  InboundReviewSnapshotItem,
} from '@/api/client'

export type SnapshotFieldChange = {
  key: string
  label: string
  before: unknown
  after: unknown
}

export type SnapshotItemComparison = InboundReviewSnapshotItem & {
  changeType: 'UNCHANGED' | 'ADDED' | 'REMOVED' | 'MODIFIED'
  changes: SnapshotFieldChange[]
}

export type InboundSnapshotDiff = {
  headerChanges: SnapshotFieldChange[]
  itemRows: SnapshotItemComparison[]
  addedAttachments: InboundReviewSnapshotAttachment[]
  removedAttachments: InboundReviewSnapshotAttachment[]
  changedItemCount: number
  totalChangeCount: number
  hasChanges: boolean
}

const headerFields: Array<[keyof InboundReviewSnapshot, string]> = [
  ['purchaseOrderNo', '采购订单'],
  ['supplierName', '供应商'],
  ['warehouseName', '仓库'],
  ['sourceType', '来源'],
  ['sourceNote', '来源说明'],
  ['businessDate', '入库日期'],
  ['sendDate', '发货日期'],
  ['sendNumber', '发货单号'],
  ['quantitySummary', '业务数量'],
  ['originalAmount', '原金额'],
  ['discountRate', '折扣率'],
  ['adjustmentAmount', '调整金额'],
  ['totalAmount', '总金额'],
  ['memo', '备注'],
]

const itemFields: Array<[keyof InboundReviewSnapshotItem, string]> = [
  ['sku', 'SKU'],
  ['productName', '商品'],
  ['spec', '规格'],
  ['requestedQuantity', '业务数量'],
  ['requestedUnit', '业务单位'],
  ['quantity', '库存数量'],
  ['baseUnit', '库存单位'],
  ['packageQuantity', '包装数'],
  ['packageUnit', '包装单位'],
  ['priceUnit', '计价单位'],
  ['pricingQuantity', '计价数量'],
  ['unitCost', '成本单价'],
  ['amount', '金额'],
  ['batchNo', '批次号'],
  ['serialNumber', '厂家序列'],
  ['packageBarcode', '包装条码'],
  ['productionDate', '生产日期'],
  ['shelfLifeMode', '保质期模式'],
  ['shelfLifeValue', '保质期数值'],
  ['shelfLifeUnit', '保质期单位'],
  ['expirationDate', '到期日'],
  ['packagingPlanJson', '计量包装快照'],
]

export function compareInboundSnapshots(
  current?: InboundReviewSnapshot | null,
  previous?: InboundReviewSnapshot | null,
): InboundSnapshotDiff {
  if (!current || !previous) {
    return {
      headerChanges: [],
      itemRows: (current?.items ?? []).map((item) => ({ ...item, changeType: 'UNCHANGED', changes: [] })),
      addedAttachments: [],
      removedAttachments: [],
      changedItemCount: 0,
      totalChangeCount: 0,
      hasChanges: false,
    }
  }

  const headerChanges = headerFields.flatMap(([key, label]) =>
    sameValue(current[key], previous[key]) ? [] : [{ key: String(key), label, before: previous[key], after: current[key] }],
  )
  const currentItems = new Map(current.items.map((item) => [item.lineNo, item]))
  const previousItems = new Map(previous.items.map((item) => [item.lineNo, item]))
  const lineNumbers = [...new Set([...currentItems.keys(), ...previousItems.keys()])].sort((a, b) => a - b)
  const itemRows = lineNumbers.map<SnapshotItemComparison>((lineNo) => {
    const after = currentItems.get(lineNo)
    const before = previousItems.get(lineNo)
    if (after && !before) return { ...after, changeType: 'ADDED', changes: [] }
    if (!after && before) return { ...before, changeType: 'REMOVED', changes: [] }
    const changes = itemFields.flatMap(([key, label]) =>
      sameValue(after?.[key], before?.[key]) ? [] : [{ key: String(key), label, before: before?.[key], after: after?.[key] }],
    )
    return { ...after!, changeType: changes.length ? 'MODIFIED' : 'UNCHANGED', changes }
  })
  const previousAttachmentHashes = new Set(previous.attachments.map((item) => item.sha256))
  const currentAttachmentHashes = new Set(current.attachments.map((item) => item.sha256))
  const addedAttachments = current.attachments.filter((item) => !previousAttachmentHashes.has(item.sha256))
  const removedAttachments = previous.attachments.filter((item) => !currentAttachmentHashes.has(item.sha256))
  const changedItemCount = itemRows.filter((item) => item.changeType !== 'UNCHANGED').length
  const totalChangeCount = headerChanges.length + changedItemCount + addedAttachments.length + removedAttachments.length
  return {
    headerChanges,
    itemRows,
    addedAttachments,
    removedAttachments,
    changedItemCount,
    totalChangeCount,
    hasChanges: totalChangeCount > 0,
  }
}

export function snapshotChangeTypeName(type: SnapshotItemComparison['changeType']) {
  return ({ UNCHANGED: '未变更', ADDED: '新增', REMOVED: '删除', MODIFIED: '修改' } as const)[type]
}

export function snapshotChangeTypeTag(type: SnapshotItemComparison['changeType']) {
  if (type === 'ADDED') return 'success'
  if (type === 'REMOVED') return 'danger'
  if (type === 'MODIFIED') return 'warning'
  return 'info'
}

function sameValue(left: unknown, right: unknown) {
  if (left == null && right == null) return true
  if (typeof left === 'number' || typeof right === 'number') return Number(left ?? 0) === Number(right ?? 0)
  return String(left ?? '').trim() === String(right ?? '').trim()
}
