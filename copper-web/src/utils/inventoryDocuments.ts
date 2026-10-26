export type InventoryDocumentKind = 'transfer' | 'stocktake' | 'purchase-return' | 'cost-adjustment'

export const inventoryDocumentConfig = {
  transfer: {
    title: '库存调拨',
    newTitle: '新建库存调拨单',
    detailTitle: '库存调拨单',
    listPath: '/inventory/transfers',
    permission: 'inventory.transfer',
    numberKey: 'orderNo',
  },
  stocktake: {
    title: '库存盘点',
    newTitle: '新建库存盘点单',
    detailTitle: '库存盘点单',
    listPath: '/inventory/stocktakes',
    permission: 'inventory.stocktake',
    numberKey: 'orderNo',
  },
  'purchase-return': {
    title: '采购退货',
    newTitle: '新建采购退货单',
    detailTitle: '采购退货单',
    listPath: '/inventory/purchase-returns',
    permission: 'purchase.return',
    numberKey: 'orderNo',
  },
  'cost-adjustment': {
    title: '成本调整',
    newTitle: '新建成本调整单',
    detailTitle: '成本调整单',
    listPath: '/inventory/cost-adjustments',
    permission: 'inventory.cost.adjust',
    numberKey: 'adjustmentNo',
  },
} as const

export const inventoryStatusText: Record<string, string> = {
  DRAFT: '草稿',
  IN_TRANSIT: '在途',
  COMPLETED: '已入库',
  CONFIRMED: '已确认',
  CANCELLED: '已作废',
  REVERSED: '已冲正',
}

export function inventoryStatusTag(status: string) {
  if (status === 'COMPLETED' || status === 'CONFIRMED') return 'success'
  if (status === 'IN_TRANSIT') return 'warning'
  if (status === 'CANCELLED' || status === 'REVERSED') return 'info'
  return 'primary'
}
