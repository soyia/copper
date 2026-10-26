<template>
  <div class="page">
    <div class="panel packaging-workbench">
      <div class="panel-header">
        <div>
          <div class="panel-title">计量与包装</div>
          <div class="muted">包装模板减少重复；系列统一应用；商品只保存差异；业务单据和库存批次保存不可变快照。</div>
        </div>
      </div>
      <el-alert
        title="维护顺序：先发布包装模板，再应用到产品系列。只有确实不同的SKU才设置商品特例。分类只用于筛选，不参与规则继承。"
        type="info"
        :closable="false"
        show-icon
        style="margin: 0 16px 12px"
      />

      <el-tabs v-model="activeTab" class="packaging-tabs">
        <el-tab-pane label="包装模板" name="templates">
          <div class="tab-toolbar">
            <el-input v-model="templateKeyword" clearable placeholder="模板编码 / 名称 / 说明" />
            <el-button type="primary" @click="openTemplate()">新增模板</el-button>
          </div>
          <el-table :data="filteredTemplates" border size="small" max-height="calc(100vh - 310px)">
            <el-table-column prop="code" label="模板编码" width="170" />
            <el-table-column prop="name" label="模板名称" min-width="180" />
            <el-table-column prop="description" label="适用说明" min-width="220" show-overflow-tooltip />
            <el-table-column prop="baseUnit" label="主库存单位" width="110" />
            <el-table-column label="包装关系" min-width="200"><template #default="{ row }">{{ templateRuleSummary(row) }}</template></el-table-column>
            <el-table-column label="应用范围" width="170"><template #default="{ row }">系列 {{ row.seriesCount || 0 }} / 商品 {{ row.productCount || 0 }}</template></el-table-column>
            <el-table-column label="当前版本" width="100"><template #default="{ row }">v{{ row.currentRevisionNo }}</template></el-table-column>
            <el-table-column label="状态" width="72"><template #default="{ row }">{{ statusName(row.status) }}</template></el-table-column>
            <el-table-column label="操作" width="190" fixed="right">
              <template #default="{ row }">
                <el-button text type="primary" @click="openDetail(row)">详情</el-button>
                <el-button text type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button text type="primary" :disabled="row.status !== 'ENABLED'" @click="openTemplate(row)">发新版</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="系列应用" name="bindings">
          <div class="tab-toolbar">
            <el-input v-model="bindingKeyword" clearable placeholder="产品分类 / 产品系列 / 模板" />
            <el-select v-model="bindingStatus" clearable placeholder="应用状态">
              <el-option label="已应用模板" value="BOUND" />
              <el-option label="未应用模板" value="UNBOUND" />
            </el-select>
          </div>
          <el-table :data="filteredBindings" border size="small" max-height="calc(100vh - 310px)">
            <el-table-column prop="categoryName" label="产品分类" width="150" />
            <el-table-column prop="productSeriesName" label="产品系列" min-width="190" />
            <el-table-column prop="productCount" label="商品数" width="90" align="right" />
            <el-table-column label="当前模板" min-width="210">
              <template #default="{ row }">
                <template v-if="row.templateId">
                  {{ row.templateName }}
                  <el-tag size="small" effect="plain">v{{ row.templateRevisionNo }}</el-tag>
                </template>
                <el-tag v-else type="warning" size="small">未应用模板</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="商品特例" width="110" align="right"><template #default="{ row }">{{ row.overrideProductCount || 0 }} 个</template></el-table-column>
            <el-table-column label="风险提示" min-width="190"><template #default="{ row }"><span :class="row.warnings?.length ? 'text-warning' : 'muted'">{{ row.warnings?.join('；') || '配置一致' }}</span></template></el-table-column>
            <el-table-column label="操作" width="100" align="center" fixed="right">
              <template #default="{ row }">
                <el-button text type="primary" @click="openBinding(row)">
                  {{ row.templateId ? '变更模板' : '应用模板' }}
                </el-button>
              </template>

            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="商品规则" name="products">
          <div class="tab-toolbar product-packaging-filters">
            <el-input v-model="filters.keyword" clearable placeholder="SKU / 商品 / 规格" />
            <el-select v-model="filters.categoryId" clearable filterable placeholder="产品分类"><el-option v-for="item in categories ?? []" :key="item.id" :label="item.name" :value="item.id" /></el-select>
            <el-select v-model="filters.productSeriesId" clearable filterable placeholder="产品系列"><el-option v-for="item in series ?? []" :key="item.id" :label="item.name" :value="item.id" /></el-select>
            <el-select v-model="filters.sourceLevel" clearable placeholder="规则来源"><el-option label="系列继承" value="TEMPLATE" /><el-option label="商品特例" value="PRODUCT_OVERRIDE" /><el-option label="历史迁移" value="LEGACY" /></el-select>
          </div>
          <el-table :data="filteredProducts" size="small" border show-overflow-tooltip max-height="calc(100vh - 310px)">
            <el-table-column prop="sku" label="SKU" width="180" fixed="left" />
            <el-table-column prop="name" label="商品名称" min-width="220" fixed="left" />
            <el-table-column prop="productSeriesName" label="产品系列" width="170" />
            <el-table-column prop="spec" label="规格" min-width="216" />
            <el-table-column label="规则来源" width="140">
              <template #default="{ row }"><el-tag :type="sourceTagType(summary(row.id)?.sourceLevel)" size="small">{{ sourceName(summary(row.id)?.sourceLevel) }}</el-tag></template>
            </el-table-column>
            <el-table-column label="有效包装" min-width="260"><template #default="{ row }">{{ summary(row.id)?.packagingSummary || `仅主库存单位 ${row.unit}` }}</template></el-table-column>
            <el-table-column label="特例" width="90" align="right"><template #default="{ row }">{{ summary(row.id)?.overrideCount || 0 }} 项</template></el-table-column>
            <el-table-column label="有效版本" width="105"><template #default="{ row }">v{{ summary(row.id)?.versionNo || 0 }}</template></el-table-column>
            <el-table-column label="操作" width="110" align="center" fixed="right"><template #default="{ row }"><el-button text type="primary" @click="openPackaging(row)">{{ summary(row.id)?.overrideCount ? '编辑特例' : '设置特例' }}</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>

    <PackagingTemplateDialog v-model="templateDialogVisible" :template-id="editingTemplateId" @saved="handleTemplateSaved" />
    <PackagingTemplateEditDialog
      v-model="editDialogVisible"
      :template="editingTemplate"
      @saved="handleTemplateSaved"
    />
    <ProductPackagingDialog v-model="productDialogVisible" :product="activeProduct" @saved="handleProductSaved" />
    <SeriesPackagingBindingDialog
      v-model="bindingDialogVisible"
      :binding="activeBinding"
      @saved="handleBindingSaved"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRoute, useRouter } from 'vue-router'
import {
  api,
  type PackagingTemplate,
  type Product,
  type ProductPackagingSummary,
  type SeriesPackagingBinding,
} from '@/api/client'
import { statusName } from '@/api/format'
import { matchesKeyword } from '@/utils/listFilters'
import { relatedDetailRoute } from '@/utils/navigation'
import PackagingTemplateDialog from '@/components/PackagingTemplateDialog.vue'
import PackagingTemplateEditDialog from '@/components/PackagingTemplateEditDialog.vue'
import ProductPackagingDialog from '@/components/ProductPackagingDialog.vue'
import SeriesPackagingBindingDialog from '@/components/SeriesPackagingBindingDialog.vue'

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const activeTab = ref('templates')
const templateKeyword = ref('')
const bindingKeyword = ref('')
const bindingStatus = ref('')
const templateDialogVisible = ref(false)
const editingTemplateId = ref<number>()
const editDialogVisible = ref(false)
const editingTemplate = ref<PackagingTemplate>()
const productDialogVisible = ref(false)
const activeProduct = ref<Pick<Product, 'id' | 'name' | 'unit' | 'productSeriesId'>>()
const bindingDialogVisible = ref(false)
const activeBinding = ref<SeriesPackagingBinding>()
const filters = reactive({ keyword: '', categoryId: undefined as number | undefined, productSeriesId: undefined as number | undefined, sourceLevel: '' })

const { data: products } = useQuery({ queryKey: ['products'], queryFn: api.products })
const { data: categories } = useQuery({ queryKey: ['product-categories'], queryFn: api.productCategories })
const { data: series } = useQuery({ queryKey: ['product-series'], queryFn: api.productSeries })
const { data: templates } = useQuery({ queryKey: ['packaging-templates'], queryFn: api.packagingTemplates })
const { data: bindings } = useQuery({ queryKey: ['series-packaging-bindings'], queryFn: api.seriesPackagingBindings })
const { data: summaries } = useQuery({ queryKey: ['product-packaging-summaries'], queryFn: api.productPackagingSummaries })
const summaryMap = computed(() => new Map((summaries.value ?? []).map((item) => [item.productId, item])))
const filteredTemplates = computed(() => (templates.value ?? []).filter((item) => matchesKeyword(item, templateKeyword.value, ['code', 'name', 'description', 'baseUnit'])))
const filteredBindings = computed(() => (bindings.value ?? []).filter((item) =>
  (!bindingStatus.value || (bindingStatus.value === 'BOUND' ? Boolean(item.templateId) : !item.templateId))
  && matchesKeyword(item, bindingKeyword.value, ['categoryName', 'productSeriesName', 'templateName']),
))
const filteredProducts = computed(() => (products.value ?? []).filter((item) => {
  const itemSummary = summaryMap.value.get(item.id)
  return (!filters.categoryId || item.categoryId === filters.categoryId)
    && (!filters.productSeriesId || item.productSeriesId === filters.productSeriesId)
    && (!filters.sourceLevel || itemSummary?.sourceLevel === filters.sourceLevel)
    && matchesKeyword(item, filters.keyword, ['sku', 'name', 'grade', 'spec', 'productSeriesName'])
}))

function summary(productId: number): ProductPackagingSummary | undefined { return summaryMap.value.get(productId) }
function openTemplate(item?: PackagingTemplate) { editingTemplateId.value = item?.id; templateDialogVisible.value = true }
function openDetail(item: PackagingTemplate) {
  router.push(relatedDetailRoute(route, `/product-packaging/templates/${item.id}`))
}
function openEdit(item: PackagingTemplate) {
  editingTemplate.value = item
  editDialogVisible.value = true
}
function openPackaging(row: Product) { activeProduct.value = row; productDialogVisible.value = true }
function openBinding(row: SeriesPackagingBinding) {
  activeBinding.value = row
  bindingDialogVisible.value = true
}

async function handleTemplateSaved() { await refreshAll() }
async function handleProductSaved() { await refreshAll() }
async function handleBindingSaved() { await refreshAll() }
async function refreshAll() {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['packaging-templates'] }),
    queryClient.invalidateQueries({ queryKey: ['series-packaging-bindings'] }),
    queryClient.invalidateQueries({ queryKey: ['product-packaging-summaries'] }),
    queryClient.invalidateQueries({ queryKey: ['products'] }),
  ])
}
function templateRuleSummary(row: PackagingTemplate) {
  const rules = row.rules ?? []
  if (!rules.length) return row.ruleCount ? `${row.ruleCount} 层包装（查看版本详情）` : `仅 ${row.baseUnit || '主库存单位'}`
  return rules.map((item) => `${item.parentUnit}→${item.childUnit}${item.conversionMode === 'FIXED' ? `×${item.fixedChildQuantity}` : item.conversionMode === 'ACTUAL_WEIGHT' ? '（实重）' : '（理论）'}`).join('；')
}
function sourceName(value?: string) { return ({ TEMPLATE: '系列继承', PRODUCT_OVERRIDE: '商品特例', SYSTEM_DEFAULT: '系统默认', LEGACY: '历史迁移' } as Record<string, string>)[value || ''] || '未配置' }
function sourceTagType(value?: string) { return value === 'PRODUCT_OVERRIDE' ? 'warning' : value === 'TEMPLATE' ? 'success' : 'info' }
</script>
