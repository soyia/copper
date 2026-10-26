<template>
  <div class="page">
    <div ref="detailPanelRef" class="panel product-detail-panel">
      <div class="panel-header product-detail-header">
        <div class="product-detail-header__main">
          <el-button class="product-detail-back" text :icon="ArrowLeft" title="返回" aria-label="返回" @click="goBack" />
          <div class="product-detail-header__titles">
            <div class="panel-title">{{ product?.name || `商品 #${productId}` }}</div>
            <div v-if="product" class="muted">{{ product.sku }} · {{ product.categoryName }} / {{ product.productSeriesName }}</div>
          </div>
        </div>
        <div class="product-detail-header__actions">
          <el-button v-if="canManageProduct" :icon="Edit" @click="openProductForm('edit')">编辑</el-button>
          <el-button v-if="canManageProduct" :icon="CopyDocument" @click="openProductForm('copy')">复制</el-button>
          <el-button :icon="Refresh" :loading="refreshingDetail" @click="refreshDetail">刷新</el-button>
          <el-button :icon="FullScreen" @click="toggleFullscreen">{{ isFullscreen ? '退出全屏' : '全屏' }}</el-button>
        </div>
      </div>

      <el-skeleton v-if="productsPending" :rows="6" animated />
      <el-result v-else-if="!product" icon="warning" title="商品不存在或无权查看" />

      <el-tabs v-else v-model="activeTab">
        <el-tab-pane label="基本资料" name="overview">
          <el-descriptions :column="3" border>
            <el-descriptions-item label="SKU">{{ product.sku }}</el-descriptions-item>
            <el-descriptions-item label="商品名称">{{ product.name }}</el-descriptions-item>
            <el-descriptions-item label="启停状态">
              <el-tag :type="product.status === 'ENABLED' ? 'success' : 'info'">
                {{ product.status === 'ENABLED' ? '启用' : '停用' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="经营状态">
              <el-tag :type="productBusinessStatus.type">
                {{ productBusinessStatus.label }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="分类">{{ product.categoryName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="系列">{{ product.productSeriesName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="库存主单位">{{ product.unit }}</el-descriptions-item>
            <el-descriptions-item label="完整规格" :span="3">{{ product.spec || '-' }}</el-descriptions-item>
            <el-descriptions-item label="商品条码">{{ product.barcode || '-' }}</el-descriptions-item>
            <el-descriptions-item label="内部原编码">{{ product.originalNumber || '-' }}</el-descriptions-item>
            <el-descriptions-item label="参考单价">{{ money(product.defaultPrice) }}</el-descriptions-item>
          </el-descriptions>
          <el-alert
            v-if="!productBusinessStatus.available"
            :title="productBusinessStatus.label"
            :description="productBusinessStatus.description"
            :type="productBusinessStatus.type === 'danger' ? 'error' : productBusinessStatus.type"
            :closable="false"
            show-icon
            class="product-business-status-alert"
          />

          <section class="detail-section">
            <div class="detail-section-header">
              <div>
                <h3>经营规格</h3>
                <p>这里只展示开单、采购、库存和查货需要的规格，不维护制造试验或厂家资料。</p>
              </div>
            </div>
            <el-table :data="product.fieldValues || []" border size="small" class="compact-empty-table">
              <el-table-column prop="displayName" label="规格项" min-width="160" />
              <el-table-column label="规格值" min-width="220">
                <template #default="{ row }">{{ row.value }}{{ row.unitName || '' }}</template>
              </el-table-column>
              <template #empty>
                <el-empty description="该商品尚未配置经营规格" :image-size="56" />
              </template>
            </el-table>
          </section>
        </el-tab-pane>

        <el-tab-pane label="单位与包装" name="packaging">
          <section class="detail-section">
            <div class="detail-section-header">
              <div>
                <h3>计量与包装</h3>
              </div>
              <div>
                <el-button v-if="canManageProduct" @click="packagingPolicyVisible = true">编辑计量包装特例</el-button>
                <el-button v-if="canManageComplex" type="primary" @click="openPackagingVariant()">新增特殊包装</el-button>
              </div>
            </div>

            <el-skeleton v-if="packagingOverviewPending" :rows="5" animated />
            <el-alert
              v-else-if="packagingOverviewError"
              title="计量包装关系读取失败"
              description="商品资料仍可查看，但当前无法确认系列模板和商品特例的最终生效结果。"
              type="error"
              :closable="false"
              show-icon
            >
              <template #default>
                <el-button size="small" @click="refreshPackaging">重新读取</el-button>
              </template>
            </el-alert>
            <template v-else-if="packagingProfile">
              <div class="packaging-source-card">
                <div class="packaging-source-card__header">
                  <div>
                    <div class="packaging-source-card__title">当前最终生效结果</div>
                    <div class="muted">{{ packagingSourceDescription }}</div>
                  </div>
                  <el-tag :type="packagingSourceTag.type" effect="plain">{{ packagingSourceTag.label }}</el-tag>
                </div>
                <el-descriptions :column="4" border>
                  <el-descriptions-item label="所属系列">{{ product.productSeriesName || packagingProfile.productSeriesName || '-' }}</el-descriptions-item>
                  <el-descriptions-item label="系列模板">
                    {{ packagingProfile.templateName || '未应用模板，使用系统默认' }}
                  </el-descriptions-item>
                  <el-descriptions-item label="模板版本">
                    {{ packagingProfile.templateRevisionNo ? `v${packagingProfile.templateRevisionNo}` : '-' }}
                  </el-descriptions-item>
                  <el-descriptions-item label="商品有效版本">v{{ packagingProfile.versionNo }}</el-descriptions-item>
                </el-descriptions>
              </div>

              <el-alert
                v-for="warning in packagingProfile.warnings || []"
                :key="warning"
                :title="warning"
                type="warning"
                :closable="false"
                show-icon
                class="packaging-profile-warning"
              />

              <div class="packaging-subsection">
                <div class="packaging-subsection__title">当前生效计量策略</div>
                <el-table :data="packagingPolicyRows" border size="small">
                  <el-table-column prop="label" label="策略" min-width="150" />
                  <el-table-column prop="value" label="当前生效值" min-width="190" />
                  <el-table-column label="来源" width="130">
                    <template #default="{ row }">
                      <el-tag :type="row.overridden ? 'warning' : 'success'" effect="plain">
                        {{ row.overridden ? '商品特例' : packagingInheritedSourceName }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="系列原值" min-width="180">
                    <template #default="{ row }">
                      <span :class="{ muted: !row.overridden }">{{ row.inheritedValue }}</span>
                    </template>
                  </el-table-column>
                </el-table>
              </div>

              <div class="packaging-subsection">
                <div class="packaging-subsection__title">计量与包装规则</div>
                <div class="muted packaging-subsection__hint">同时列出最终生效规则，以及被商品特例停用的系列规则。</div>
                <el-table :data="packagingRuleRows" border size="small" class="compact-empty-table">
                  <el-table-column label="层级" min-width="145">
                    <template #default="{ row }">{{ row.parentUnit }} → {{ row.childUnit }}</template>
                  </el-table-column>
                  <el-table-column label="换算规则" min-width="220">
                    <template #default="{ row }">{{ packagingRuleText(row) }}</template>
                  </el-table-column>
                  <el-table-column label="适用业务" min-width="170">
                    <template #default="{ row }">{{ packagingPurposesText(row) }}</template>
                  </el-table-column>
                  <el-table-column label="包装约束" min-width="150">
                    <template #default="{ row }">{{ packagingConstraintText(row) }}</template>
                  </el-table-column>
                  <el-table-column label="包装识别" min-width="190">
                    <template #default="{ row }">{{ packageDefinitionText(row) }}</template>
                  </el-table-column>
                  <el-table-column label="来源" width="130">
                    <template #default="{ row }">
                      <el-tag :type="row.sourceType" effect="plain">{{ row.sourceLabel }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="状态" width="100">
                    <template #default="{ row }">
                      <el-tag :type="row.effective ? 'success' : 'info'">
                        {{ row.effective ? '当前生效' : '特例停用' }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <template #empty>
                    <el-empty description="当前只有基础计量策略，没有箱、盘、卷等包装换算层级" :image-size="56" />
                  </template>
                </el-table>
              </div>
            </template>
          </section>

          <section class="detail-section">
            <div class="detail-section-header">
              <div>
                <h3>商品特殊包装档案</h3>
                <p>仅记录该商品真实存在、不能由系列公共规则表达的包装规格；不会替代上方最终生效规则。</p>
              </div>
            </div>
            <el-table :data="packagingVariants" border size="small" class="compact-empty-table">
              <el-table-column prop="name" label="包装名称" min-width="150" />
              <el-table-column prop="variantCode" label="包装编码" min-width="120" />
              <el-table-column prop="packageUnit" label="包装单位" width="100" />
              <el-table-column label="换算" min-width="170">
                <template #default="{ row }">{{ conversionText(row) }}</template>
              </el-table-column>
              <el-table-column prop="innerSpec" label="包装说明" min-width="180" />
              <el-table-column label="状态" width="90">
                <template #default="{ row }">{{ row.status === 'ENABLED' ? '启用' : '停用' }}</template>
              </el-table-column>
              <el-table-column label="操作" width="160" fixed="right">
                <template #default="{ row }">
                  <el-button v-if="canManageComplex" text type="primary" @click="openPackagingVariant(row)">编辑</el-button>
                  <el-button text @click="showPackagingHistory(row)">历史</el-button>
                </template>
              </el-table-column>
              <template #empty>
                <el-empty description="没有额外的商品特殊包装档案" :image-size="56" />
              </template>
            </el-table>
          </section>
        </el-tab-pane>

        <el-tab-pane v-if="canViewSupplierOffers" :label="`供应商供货 ${supplierOffers.length}`" name="suppliers">
          <section class="detail-section">
            <div class="detail-section-header">
              <div>
                <h3>供应商供货条件</h3>
                <p>记录供应商货号、型号、采购和计价单位；实际订单价格仍在采购时确认并冻结。</p>
              </div>
              <el-button v-if="canManageSupplierOffers" type="primary" @click="openSupplierOffer()">新增供应商</el-button>
            </div>
            <el-table :data="supplierOffers" border size="small" class="compact-empty-table">
              <el-table-column prop="supplierName" label="供应商" min-width="160" />
              <el-table-column prop="supplierProductCode" label="供应商货号" min-width="130" />
              <el-table-column prop="supplierModel" label="厂家型号" min-width="130" />
              <el-table-column label="单位" min-width="140">
                <template #default="{ row }">{{ row.purchaseUnit }}采购 / {{ row.priceUnit }}计价</template>
              </el-table-column>
              <el-table-column label="参考报价" width="120">
                <template #default="{ row }">{{ row.quotedUnitPrice == null ? '-' : money(row.quotedUnitPrice) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }">{{ row.status === 'ENABLED' ? '启用' : '停用' }}</template>
              </el-table-column>
              <el-table-column label="操作" width="160" fixed="right">
                <template #default="{ row }">
                  <el-button v-if="canManageSupplierOffers" text type="primary" @click="openSupplierOffer(row)">编辑</el-button>
                  <el-button text @click="showOfferHistory(row)">历史</el-button>
                </template>
              </el-table-column>
              <template #empty>
                <el-empty description="暂无供应商供货关系，可在采购时直接选择商品并手工确认价格" :image-size="56" />
              </template>
            </el-table>
          </section>
        </el-tab-pane>
      </el-tabs>
    </div>

    <ProductPackagingDialog
      v-if="product"
      v-model="packagingPolicyVisible"
      :product="product"
      @saved="refreshPackaging"
    />

    <el-dialog v-model="packagingVariantVisible" :title="editingPackagingVariantId ? '编辑特殊包装' : '新增特殊包装'" width="800px">
      <el-form label-width="110px">
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="包装名称" required><el-input v-model="packagingForm.name" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="包装编码"><el-input v-model="packagingForm.variantCode" :disabled="Boolean(editingPackagingVariantId)" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="包装单位" required><el-select v-model="packagingForm.packageUnit" filterable><el-option v-for="item in packagingUnitOptions" :key="item" :label="item" :value="item" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="换算方式" required><el-select v-model="packagingForm.conversionMode"><el-option label="固定换算" value="FIXED" /><el-option label="实际称重" value="ACTUAL_WEIGHT" /><el-option label="理论换算" value="THEORETICAL" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="固定基础量"><el-input-number v-model="packagingForm.fixedBaseQuantity" :min="0" :precision="6" :controls="false" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="参考基础量"><el-input-number v-model="packagingForm.referenceBaseQuantity" :min="0" :precision="6" :controls="false" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="净重(kg)"><el-input-number v-model="packagingForm.netWeight" :min="0" :precision="6" :controls="false" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="毛重(kg)"><el-input-number v-model="packagingForm.grossWeight" :min="0" :precision="6" :controls="false" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="包装说明"><el-input v-model="packagingForm.innerSpec" placeholder="如每盘约500kg、木托包装" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="条码"><el-input v-model="packagingForm.barcode" /></el-form-item></el-col>
          <el-col v-if="editingPackagingVariantId" :span="12"><el-form-item label="状态"><el-select v-model="packagingForm.status"><el-option label="启用" value="ENABLED" /><el-option label="停用" value="DISABLED" /></el-select></el-form-item></el-col>
          <el-col v-if="editingPackagingVariantId" :span="12"><el-form-item label="变更原因" required><el-input v-model="packagingForm.changeReason" placeholder="停用或改变换算时说明原因" /></el-form-item></el-col>
        </el-row>
      </el-form>
      <template #footer><el-button @click="packagingVariantVisible = false">取消</el-button><el-button type="primary" :loading="savingPackaging" @click="savePackagingVariant">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="supplierOfferVisible" :title="editingSupplierOfferId ? '编辑供应商供货条件' : '新增供应商供货条件'" width="860px">
      <el-form label-width="110px">
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="供应商" required><PartnerRemoteSelect v-model="offerForm.supplierId" type="SUPPLIER" clearable :disabled="Boolean(editingSupplierOfferId)" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="供应商货号"><el-input v-model="offerForm.supplierProductCode" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="厂家型号"><el-input v-model="offerForm.supplierModel" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="采购单位" required><el-select v-model="offerForm.purchaseUnit" filterable><el-option v-for="item in purchaseUnitOptions" :key="item" :label="item" :value="item" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="计价单位" required><el-select v-model="offerForm.priceUnit" filterable><el-option v-for="item in priceUnitOptions" :key="item" :label="item" :value="item" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="参考报价"><el-input-number v-model="offerForm.quotedUnitPrice" :min="0" :precision="8" :controls="false" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="币种"><el-select v-model="offerForm.currencyCode"><el-option label="CNY" value="CNY" /><el-option label="USD" value="USD" /><el-option label="EUR" value="EUR" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="含税"><el-switch v-model="offerForm.priceIncludesTax" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="税率%"><el-input-number v-model="offerForm.taxRate" :min="0" :max="100" :precision="2" :controls="false" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="起订量"><el-input-number v-model="offerForm.minimumOrderQuantity" :min="0" :precision="6" :controls="false" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="订货倍数"><el-input-number v-model="offerForm.orderMultiple" :min="0" :precision="6" :controls="false" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="交期(天)"><el-input-number v-model="offerForm.leadTimeDays" :min="0" :precision="0" :controls="false" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="生效日期" required><el-date-picker v-model="offerForm.effectiveFrom" value-format="YYYY-MM-DD" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="失效日期"><el-date-picker v-model="offerForm.effectiveTo" clearable value-format="YYYY-MM-DD" /></el-form-item></el-col>
          <el-col v-if="editingSupplierOfferId" :span="12"><el-form-item label="状态"><el-select v-model="offerForm.status"><el-option label="启用" value="ENABLED" /><el-option label="停用" value="DISABLED" /></el-select></el-form-item></el-col>
          <el-col v-if="editingSupplierOfferId" :span="12"><el-form-item label="变更原因" required><el-input v-model="offerForm.changeReason" placeholder="停用或改变供货条件时说明原因" /></el-form-item></el-col>
        </el-row>
      </el-form>
      <template #footer><el-button @click="supplierOfferVisible = false">取消</el-button><el-button type="primary" :loading="savingOffer" @click="saveSupplierOffer">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="historyVisible" :title="historyTitle" width="860px">
      <el-table :data="historyRows" border size="small">
        <el-table-column prop="revisionNo" label="版本" width="80" />
        <el-table-column prop="changeReason" label="变更原因" min-width="220" />
        <el-table-column prop="publishedByName" label="操作人" width="110" />
        <el-table-column prop="publishedAt" label="时间" min-width="170" />
        <el-table-column label="当前" width="80"><template #default="{ row }">{{ row.currentRevision ? '是' : '否' }}</template></el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowLeft, CopyDocument, Edit, FullScreen, Refresh } from '@element-plus/icons-vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRoute, useRouter } from 'vue-router'
import {
  api,
  type PackageDefinition,
  type PackagingProfile,
  type PackagingRule,
  type ProductAllowedUnit,
  type ProductPackagingVariant,
  type ProductPackagingVariantRevision,
  type ProductPackagingVariantSave,
  type SupplierProductOffer,
  type SupplierProductOfferRevision,
  type SupplierProductOfferSave,
} from '@/api/client'
import { money } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import ProductPackagingDialog from '@/components/ProductPackagingDialog.vue'
import { productBusinessStatusPresentation } from '@/utils/productDisplay'
import { useAppStore } from '@/stores/app'

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const appStore = useAppStore()
const productId = Number(route.params.id)
const activeTab = ref(String(route.query.tab || 'overview'))
const detailPanelRef = ref<HTMLElement>()
const refreshingDetail = ref(false)
const isFullscreen = ref(false)
const canManageProduct = computed(() => appStore.hasPermission('product.manage'))
const canManageComplex = computed(() => appStore.hasPermission('product.master.complex.manage'))
const canViewSupplierOffers = computed(() =>
  appStore.hasPermission('product.cost.view') || appStore.hasPermission('product.supplier.offer.manage'))
const canManageSupplierOffers = computed(() => appStore.hasPermission('product.supplier.offer.manage'))

const { data: products, isPending: productsPending } = useQuery({ queryKey: ['products'], queryFn: api.products })
const product = computed(() => (products.value || []).find((item) => item.id === productId))
const productBusinessStatus = computed(() => productBusinessStatusPresentation(
  product.value ?? { status: 'DISABLED', identityStatus: 'INCOMPLETE' },
))
const { data: packagingData } = useQuery({ queryKey: ['product-packaging-variants', productId], queryFn: () => api.productPackagingVariants(productId) })
const {
  data: packagingOverview,
  isPending: packagingOverviewPending,
  isError: packagingOverviewError,
} = useQuery({
  queryKey: ['product-packaging-overview', productId],
  queryFn: async () => {
    const [effective, inherited] = await Promise.all([
      api.packagingProfile(productId),
      api.inheritedPackagingProfile(productId),
    ])
    return { effective, inherited }
  },
})
const {
  data: packageDefinitionData,
  isError: packageDefinitionsError,
} = useQuery({
  queryKey: ['product-package-definitions', productId],
  queryFn: () => api.packageDefinitions(productId),
})
const { data: offerData } = useQuery({
  queryKey: ['supplier-product-offers', productId],
  queryFn: () => api.supplierProductOffers(productId),
  enabled: canViewSupplierOffers,
})
const packagingVariants = computed(() => packagingData.value || [])
const packagingProfile = computed(() => packagingOverview.value?.effective)
const inheritedPackagingProfile = computed(() => packagingOverview.value?.inherited)
const packageDefinitions = computed(() => packageDefinitionData.value || [])
const supplierOffers = computed(() => offerData.value || [])
const allowedUnits = ref<ProductAllowedUnit[]>([])
const packagingUnitOptions = computed(() => unitNames('PACKAGING'))
const purchaseUnitOptions = computed(() => unitNames('PURCHASE'))
const priceUnitOptions = computed(() => unitNames('PRICE'))

const packagingPolicyVisible = ref(false)
const packagingVariantVisible = ref(false)
const editingPackagingVariantId = ref<number>()
const savingPackaging = ref(false)
const packagingForm = reactive<ProductPackagingVariantSave>(emptyPackaging())
const supplierOfferVisible = ref(false)
const editingSupplierOfferId = ref<number>()
const savingOffer = ref(false)
const offerForm = reactive<SupplierProductOfferSave>(emptyOffer())
const historyVisible = ref(false)
const historyTitle = ref('')
const historyRows = ref<Array<ProductPackagingVariantRevision | SupplierProductOfferRevision>>([])
type PackagingPolicyKey = 'baseUnit' | 'minimumSalesUnit' | 'minimumOutboundUnit' | 'defaultPurchaseUnit' | 'defaultSalesUnit' | 'roundingPolicy'
type PackagingRuleRow = PackagingRule & {
  effective: boolean
  sourceLabel: string
  sourceType: 'success' | 'warning' | 'info'
}

const packagingPolicyDefinitions: Array<{ key: PackagingPolicyKey; label: string }> = [
  { key: 'baseUnit', label: '库存核算主单位' },
  { key: 'minimumSalesUnit', label: '最小销售单位' },
  { key: 'minimumOutboundUnit', label: '最小发货单位' },
  { key: 'defaultPurchaseUnit', label: '默认采购单位' },
  { key: 'defaultSalesUnit', label: '默认销售单位' },
  { key: 'roundingPolicy', label: '不足最小单位处理' },
]

const packagingPolicyRows = computed(() => {
  const effective = packagingProfile.value
  const inherited = inheritedPackagingProfile.value
  if (!effective) return []
  return packagingPolicyDefinitions.map(({ key, label }) => {
    const currentValue = packagingPolicyValue(effective, key)
    const inheritedValue = inherited ? packagingPolicyValue(inherited, key) : '-'
    return {
      key,
      label,
      value: currentValue,
      inheritedValue,
      overridden: Boolean(inherited && currentValue !== inheritedValue),
    }
  })
})

const packagingRuleRows = computed<PackagingRuleRow[]>(() => {
  const effectiveRules = packagingProfile.value?.rules || []
  const inheritedRules = inheritedPackagingProfile.value?.rules || []
  const inheritedByKey = new Map(inheritedRules.map((rule) => [packagingRuleIdentity(rule), rule]))
  const effectiveKeys = new Set<string>()
  const rows: PackagingRuleRow[] = effectiveRules.map((rule) => {
    const key = packagingRuleIdentity(rule)
    effectiveKeys.add(key)
    const inherited = inheritedByKey.get(key)
    const overridden = !inherited || packagingRuleSignature(inherited) !== packagingRuleSignature(rule)
    return {
      ...rule,
      effective: true,
      sourceLabel: overridden ? (inherited ? '商品特例覆盖' : '商品特例新增') : packagingInheritedSourceName.value,
      sourceType: overridden ? 'warning' : 'success',
    }
  })
  inheritedRules.forEach((rule) => {
    const key = packagingRuleIdentity(rule)
    if (effectiveKeys.has(key)) return
    rows.push({
      ...rule,
      effective: false,
      sourceLabel: '商品特例停用',
      sourceType: 'info',
    })
  })
  return rows
})

const packagingInheritedSourceName = computed(() => {
  if (packagingProfile.value?.templateName) return '系列模板'
  if (inheritedPackagingProfile.value?.sourceLevel === 'LEGACY') return '历史迁移'
  return '系统默认'
})

const packagingSourceTag = computed<{ label: string; type: 'success' | 'warning' | 'info' }>(() => {
  if (packagingProfile.value?.sourceLevel === 'PRODUCT_OVERRIDE') return { label: '系列模板 + 商品特例', type: 'warning' }
  if (packagingProfile.value?.sourceLevel === 'LEGACY') return { label: '历史迁移规则', type: 'info' }
  if (packagingProfile.value?.templateName) return { label: '完全继承系列模板', type: 'success' }
  return { label: '系统默认规则', type: 'info' }
})

const packagingSourceDescription = computed(() => {
  const profile = packagingProfile.value
  if (!profile) return ''
  if (profile.sourceLevel === 'PRODUCT_OVERRIDE') {
    return `先继承“${profile.templateName || profile.productSeriesName || '系统默认'}”，再叠加当前商品特例；下表已按最终业务结果合并。`
  }
  if (profile.templateName) return `当前商品完全继承系列模板“${profile.templateName}”，没有商品级差异。`
  if (profile.sourceLevel === 'LEGACY') return '当前沿用历史迁移规则，建议后续归并到系列模板；历史业务快照不会改变。'
  return '当前系列尚未应用计量包装模板，商品使用系统默认计量策略。'
})

watch(
  () => product.value?.productSeriesId,
  async (seriesId) => {
    allowedUnits.value = seriesId ? await api.productSeriesAllowedUnits(seriesId).catch(() => []) : []
  },
  { immediate: true },
)

onMounted(() => document.addEventListener('fullscreenchange', syncFullscreenState))
onBeforeUnmount(() => document.removeEventListener('fullscreenchange', syncFullscreenState))

function goBack() {
  router.push(typeof route.query.from === 'string' ? route.query.from : '/products')
}

function openProductForm(action: 'edit' | 'copy') {
  router.push({
    path: '/products',
    query: { action, productId: String(productId) },
  })
}

async function refreshDetail() {
  refreshingDetail.value = true
  try {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['products'] }),
      queryClient.invalidateQueries({ queryKey: ['product-packaging-variants', productId] }),
      queryClient.invalidateQueries({ queryKey: ['product-packaging-overview', productId] }),
      queryClient.invalidateQueries({ queryKey: ['product-package-definitions', productId] }),
      queryClient.invalidateQueries({ queryKey: ['supplier-product-offers', productId] }),
    ])
    const seriesId = product.value?.productSeriesId
    allowedUnits.value = seriesId ? await api.productSeriesAllowedUnits(seriesId).catch(() => []) : []
    ElMessage.success('商品详情已刷新')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    refreshingDetail.value = false
  }
}

async function toggleFullscreen() {
  const panel = detailPanelRef.value
  if (!panel) return
  try {
    if (document.fullscreenElement === panel) {
      await document.exitFullscreen()
    } else {
      await panel.requestFullscreen()
    }
  } catch {
    ElMessage.warning('当前浏览器无法进入全屏，请检查浏览器权限')
  }
}

function syncFullscreenState() {
  isFullscreen.value = document.fullscreenElement === detailPanelRef.value
}

function unitNames(purpose: ProductAllowedUnit['purpose']) {
  const names = allowedUnits.value
    .filter((item) => item.status === 'ENABLED' && item.purpose === purpose)
    .map((item) => item.abbreviation || item.unitName)
  if (!names.length && product.value?.unit) names.push(product.value.unit)
  return [...new Set(names)]
}

function conversionText(row: ProductPackagingVariant) {
  if (row.conversionMode === 'FIXED') return `1 ${row.packageUnit} = ${row.fixedBaseQuantity || '-'} ${product.value?.unit || ''}`
  if (row.conversionMode === 'ACTUAL_WEIGHT') return '按实际称重'
  return `按理论换算${row.referenceBaseQuantity ? `（参考 ${row.referenceBaseQuantity}）` : ''}`
}

function packagingPolicyValue(profile: PackagingProfile, key: PackagingPolicyKey) {
  if (key !== 'roundingPolicy') return String(profile[key] || '-')
  return ({
    REJECT: '不自动调整，直接提示',
    ROUND_UP: '向上补足到最小单位',
    ROUND_DOWN: '向下取到最小单位',
  } as Record<string, string>)[profile.roundingPolicy] || profile.roundingPolicy
}

function packagingRuleIdentity(rule: PackagingRule) {
  return String(rule.ruleKey || `${rule.parentUnit}→${rule.childUnit}`).trim().toLowerCase()
}

function packagingRuleSignature(rule: PackagingRule) {
  return JSON.stringify({
    parentUnit: rule.parentUnit,
    childUnit: rule.childUnit,
    conversionMode: rule.conversionMode,
    fixedChildQuantity: rule.fixedChildQuantity,
    referenceBaseQuantity: rule.referenceBaseQuantity,
    breakable: rule.breakable,
    repackable: rule.repackable,
    purchaseAllowed: rule.purchaseAllowed,
    salesAllowed: rule.salesAllowed,
    outboundAllowed: rule.outboundAllowed,
    integerOnly: rule.integerOnly,
    status: rule.status,
  })
}

function packagingRuleText(rule: PackagingRule) {
  if (rule.conversionMode === 'FIXED') {
    return `1 ${rule.parentUnit} = ${formatQuantity(rule.fixedChildQuantity)} ${rule.childUnit}`
  }
  if (rule.conversionMode === 'ACTUAL_WEIGHT') {
    return `每${rule.parentUnit}逐件实称，库存按${rule.childUnit}汇总`
  }
  return rule.referenceBaseQuantity
    ? `按理论换算，参考 1 ${rule.parentUnit} ≈ ${formatQuantity(rule.referenceBaseQuantity)} ${rule.childUnit}`
    : `按理论换算为${rule.childUnit}`
}

function packagingPurposesText(rule: PackagingRule) {
  const purposes = [
    rule.purchaseAllowed ? '采购' : '',
    rule.salesAllowed ? '销售' : '',
    rule.outboundAllowed ? '发货' : '',
  ].filter(Boolean)
  return purposes.length ? purposes.join('、') : '不用于业务选择'
}

function packagingConstraintText(rule: PackagingRule) {
  const constraints = [
    rule.integerOnly ? `${rule.parentUnit}须整数` : `${rule.parentUnit}可小数`,
    rule.breakable ? '允许拆包' : '整包处理',
    rule.repackable ? '允许重组包装' : '',
  ].filter(Boolean)
  return constraints.join('；')
}

function packageDefinitionText(rule: PackagingRuleRow) {
  if (!rule.effective) return '-'
  if (packageDefinitionsError.value) return '包装识别资料读取失败'
  const definition = packageDefinitions.value.find((item) =>
    normalizedUnit(item.packageUnit) === normalizedUnit(rule.parentUnit))
  if (!definition) return '未设置条码或保质期'
  const details = [
    definition.barcode ? `条码 ${definition.barcode}` : '',
    shelfLifeText(definition),
  ].filter(Boolean)
  return details.length ? details.join('；') : '未设置条码或保质期'
}

function shelfLifeText(definition: PackageDefinition) {
  if (definition.shelfLifeMode !== 'PERIOD') return ''
  const unit = ({ DAY: '天', MONTH: '个月', YEAR: '年' } as Record<string, string>)[definition.shelfLifeUnit || ''] || ''
  return definition.shelfLifeValue ? `保质期 ${definition.shelfLifeValue}${unit}` : ''
}

function normalizedUnit(value?: string) {
  return String(value || '').trim().toLowerCase()
}

function formatQuantity(value?: number | null) {
  if (value == null) return '-'
  return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 6 })
}

function emptyPackaging(): ProductPackagingVariantSave {
  return {
    variantCode: '',
    name: '',
    packageUnit: '',
    conversionMode: 'ACTUAL_WEIGHT',
    fixedBaseQuantity: null,
    referenceBaseQuantity: null,
    innerSpec: '',
    netWeight: null,
    grossWeight: null,
    tareWeight: null,
    barcode: '',
    supplierId: null,
    sourceDocumentRevisionId: null,
    status: 'ENABLED',
    changeReason: '新增特殊包装',
    expectedRevisionNo: 0,
  }
}

function openPackagingVariant(row?: ProductPackagingVariant) {
  editingPackagingVariantId.value = row?.id
  Object.assign(packagingForm, row ? {
    variantCode: row.variantCode,
    name: row.name,
    packageUnit: row.packageUnit,
    conversionMode: row.conversionMode,
    fixedBaseQuantity: row.fixedBaseQuantity,
    referenceBaseQuantity: row.referenceBaseQuantity,
    innerSpec: row.innerSpec,
    netWeight: row.netWeight,
    grossWeight: row.grossWeight,
    tareWeight: row.tareWeight,
    barcode: row.barcode,
    supplierId: row.supplierId,
    sourceDocumentRevisionId: null,
    status: row.status,
    changeReason: '',
    expectedRevisionNo: row.revisionNo,
  } : emptyPackaging())
  if (!packagingForm.packageUnit) packagingForm.packageUnit = packagingUnitOptions.value[0] || product.value?.unit || ''
  packagingVariantVisible.value = true
}

async function savePackagingVariant() {
  if (!packagingForm.name.trim() || !packagingForm.packageUnit) {
    ElMessage.warning('请填写包装名称和包装单位')
    return
  }
  if (packagingForm.conversionMode === 'FIXED' && !(Number(packagingForm.fixedBaseQuantity) > 0)) {
    ElMessage.warning('固定换算必须填写大于0的基础数量')
    return
  }
  if (editingPackagingVariantId.value && !packagingForm.changeReason.trim()) {
    ElMessage.warning('改变或停用既有包装时请填写变更原因')
    return
  }
  savingPackaging.value = true
  try {
    if (editingPackagingVariantId.value) {
      await api.updateProductPackagingVariant(productId, editingPackagingVariantId.value, packagingForm)
    } else {
      await api.createProductPackagingVariant(productId, packagingForm)
    }
    packagingVariantVisible.value = false
    ElMessage.success('包装条件已保存')
    await queryClient.invalidateQueries({ queryKey: ['product-packaging-variants', productId] })
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    savingPackaging.value = false
  }
}

function emptyOffer(): SupplierProductOfferSave {
  return {
    supplierId: undefined,
    supplierProductCode: '',
    supplierModel: '',
    purchaseUnit: '',
    priceUnit: '',
    quotedUnitPrice: null,
    currencyCode: 'CNY',
    priceIncludesTax: true,
    taxRate: 13,
    minimumOrderQuantity: null,
    orderMultiple: null,
    leadTimeDays: null,
    effectiveFrom: new Date().toISOString().slice(0, 10),
    effectiveTo: null,
    packagingVariantRevisionId: null,
    sourceDocumentRevisionId: null,
    commercialTermsJson: '{}',
    status: 'ENABLED',
    changeReason: '新增供应商供货关系',
    expectedRevisionNo: 0,
  }
}

function openSupplierOffer(row?: SupplierProductOffer) {
  editingSupplierOfferId.value = row?.id
  Object.assign(offerForm, row ? {
    supplierId: row.supplierId,
    supplierProductCode: row.supplierProductCode,
    supplierModel: row.supplierModel,
    purchaseUnit: row.purchaseUnit,
    priceUnit: row.priceUnit,
    quotedUnitPrice: row.quotedUnitPrice,
    currencyCode: row.currencyCode,
    priceIncludesTax: row.priceIncludesTax,
    taxRate: row.taxRate,
    minimumOrderQuantity: row.minimumOrderQuantity,
    orderMultiple: row.orderMultiple,
    leadTimeDays: row.leadTimeDays,
    effectiveFrom: row.effectiveFrom,
    effectiveTo: row.effectiveTo,
    packagingVariantRevisionId: row.packagingVariantRevisionId,
    sourceDocumentRevisionId: null,
    commercialTermsJson: row.commercialTermsJson,
    status: row.status,
    changeReason: '',
    expectedRevisionNo: row.revisionNo,
  } : emptyOffer())
  if (!offerForm.purchaseUnit) offerForm.purchaseUnit = purchaseUnitOptions.value[0] || product.value?.unit || ''
  if (!offerForm.priceUnit) offerForm.priceUnit = priceUnitOptions.value[0] || product.value?.unit || ''
  supplierOfferVisible.value = true
}

async function saveSupplierOffer() {
  if (!offerForm.supplierId || !offerForm.purchaseUnit || !offerForm.priceUnit || !offerForm.effectiveFrom) {
    ElMessage.warning('请选择供应商并填写采购单位、计价单位和生效日期')
    return
  }
  if (editingSupplierOfferId.value && !offerForm.changeReason.trim()) {
    ElMessage.warning('改变或停用既有供货条件时请填写变更原因')
    return
  }
  savingOffer.value = true
  try {
    if (editingSupplierOfferId.value) {
      await api.updateSupplierProductOffer(productId, editingSupplierOfferId.value, offerForm)
    } else {
      await api.createSupplierProductOffer(productId, offerForm)
    }
    supplierOfferVisible.value = false
    ElMessage.success('供应商供货条件已保存')
    await queryClient.invalidateQueries({ queryKey: ['supplier-product-offers', productId] })
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    savingOffer.value = false
  }
}

async function showPackagingHistory(row: ProductPackagingVariant) {
  historyTitle.value = `包装历史 · ${row.name}`
  historyRows.value = await api.productPackagingVariantRevisions(productId, row.id)
  historyVisible.value = true
}

async function showOfferHistory(row: SupplierProductOffer) {
  historyTitle.value = `供应商供货历史 · ${row.supplierName || row.supplierId}`
  historyRows.value = await api.supplierProductOfferRevisions(productId, row.id)
  historyVisible.value = true
}

async function refreshPackaging() {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['products'] }),
    queryClient.invalidateQueries({ queryKey: ['product-packaging-variants', productId] }),
    queryClient.invalidateQueries({ queryKey: ['product-packaging-overview', productId] }),
    queryClient.invalidateQueries({ queryKey: ['product-package-definitions', productId] }),
  ])
}

function errorText(error: unknown) {
  if (error instanceof Error) return error.message
  return '保存失败，请稍后重试'
}
</script>

<style scoped>
.product-detail-panel:fullscreen {
  height: 100vh;
  overflow-y: auto;
  border: 0;
  border-radius: 0;
}

.product-detail-header {
  gap: 16px;
}

.product-detail-header__main {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.product-detail-header__titles {
  min-width: 0;
}

.product-detail-back {
  flex: 0 0 auto;
  width: 32px;
  height: 32px;
  padding: 0;
  color: var(--el-text-color-primary);
  font-size: 18px;
}

.product-detail-header__actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
}

.product-business-status-alert {
  margin-top: 12px;
}

:deep(.compact-empty-table .el-table__empty-block) {
  min-height: 150px;
}

:deep(.compact-empty-table .el-empty) {
  padding: 12px 0;
}

:deep(.compact-empty-table .el-empty__description) {
  margin-top: 8px;
}

.detail-section {
  margin-top: 18px;
}

.detail-section-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 12px;
}

.detail-section-header h3 {
  margin: 0 0 4px;
}

.detail-section-header p {
  margin: 0;
  color: var(--text-muted);
}

.packaging-source-card {
  padding: 14px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-fill-color-light);
}

.packaging-source-card__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.packaging-source-card__title,
.packaging-subsection__title {
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.packaging-subsection {
  margin-top: 16px;
}

.packaging-subsection__hint {
  margin: 4px 0 8px;
}

.packaging-profile-warning {
  margin-top: 10px;
}

.muted {
  color: var(--text-muted);
  font-size: 13px;
  margin-top: 4px;
}

:deep(.el-select),
:deep(.el-date-editor) {
  width: 100%;
}
</style>
