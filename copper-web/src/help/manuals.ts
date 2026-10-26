import basicMasterDataSource from '../../docs/help/BASE_DATA_USER_GUIDE.md?raw'

export type HelpManual = {
  id: string
  module: string
  title: string
  summary: string
  updatedAt: string
  source: string
  relatedRoutes: Array<{ label: string; path: string }>
}

export const helpManuals: HelpManual[] = [
  {
    id: 'base-data',
    module: '基础资料',
    title: '基础资料使用手册',
    summary: '计量单位、仓库、分类与规格、计量与包装、商品资料及批量导入。',
    updatedAt: '2026-07-28',
    source: basicMasterDataSource,
    relatedRoutes: [
      { label: '商品资料', path: '/products' },
      { label: '分类与规格', path: '/product-catalog' },
      { label: '计量与包装', path: '/product-packaging' },
      { label: '仓库资料', path: '/warehouses' },
      { label: '计量单位', path: '/units' },
    ],
  },
]
