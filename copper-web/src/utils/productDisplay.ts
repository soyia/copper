import type { Product } from '@/api/client'

type ProductDisplaySource = Pick<Product, 'sku' | 'name' | 'spec' | 'orderSpecText' | 'fieldValues'>
type TransactionProductSource = ProductDisplaySource
  & Pick<Product, 'id' | 'status' | 'identityStatus'>
type ProductBusinessStatusSource = Pick<Product, 'status' | 'identityStatus'>

export interface ProductBusinessStatusPresentation {
  label: string
  type: 'success' | 'warning' | 'danger' | 'info'
  available: boolean
  description: string
}

export function productSpecSummary(product: ProductDisplaySource) {
  const dynamic = (product.fieldValues ?? [])
    .filter((item) => item.seriesFieldStatus === 'ENABLED' && item.includeInSpec)
    .map((item) => `${item.displayName || item.fieldName || ''}${item.displayValue ?? item.value ?? ''}`)
    .filter(Boolean)
    .join(' / ')
  return dynamic || product.orderSpecText || product.spec || ''
}

export function productOptionLabel(product: ProductDisplaySource) {
  return [product.sku, product.name, productSpecSummary(product)].filter(Boolean).join(' / ')
}

export function isTransactionSelectableProduct(product: TransactionProductSource) {
  return product.status === 'ENABLED' && product.identityStatus === 'PUBLISHED'
}

export function productBusinessStatusPresentation(
  product: ProductBusinessStatusSource,
): ProductBusinessStatusPresentation {
  if (product.status !== 'ENABLED') {
    return {
      label: '已停用，不可开单',
      type: 'info',
      available: false,
      description: '该商品已停用，只能查看历史业务，不能新增采购、销售或出入库业务。',
    }
  }
  if (product.identityStatus === 'PUBLISHED') {
    return {
      label: '可用于业务',
      type: 'success',
      available: true,
      description: '该商品可用于采购、销售和出入库业务。',
    }
  }
  if (product.identityStatus === 'PENDING_REVIEW') {
    return {
      label: '待复核，不可开单',
      type: 'warning',
      available: false,
      description: '该商品的规格身份仍待复核，暂不能用于采购、销售和出入库业务。',
    }
  }
  return {
    label: '资料未完成，不可开单',
    type: 'danger',
    available: false,
    description: '该商品的必需资料尚未完成，暂不能用于采购、销售和出入库业务。',
  }
}

export function transactionProductOptions(
  products: readonly Product[],
  currentProductId?: number | null,
) {
  return products.filter((product) =>
    isTransactionSelectableProduct(product) || product.id === currentProductId)
}

export function transactionProductOptionLabel(product: TransactionProductSource) {
  const base = productOptionLabel(product)
  if (product.status !== 'ENABLED') return `${base} [已停用，仅保留当前单据]`
  if (product.identityStatus === 'PENDING_REVIEW') return `${base} [待复核，不可新增业务]`
  if (product.identityStatus !== 'PUBLISHED') return `${base} [身份未完成，不可新增业务]`
  return base
}
