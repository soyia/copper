<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">商品资料</div>
        <div class="product-header-actions">
          <el-dropdown v-if="canViewComplex" @command="handleAdvancedCommand">
            <el-button>高级工具⌄</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="import">{{ canManageComplex ? '批量导入' : '导入审计' }}</el-dropdown-item>
                <el-dropdown-item v-if="canManageProduct" command="matrix">规格矩阵建档</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button v-if="canManageProduct" type="primary" @click="openCreate">新增商品</el-button>
        </div>
      </div>
      <div class="list-tools">
        <el-input v-model="filters.keyword" clearable placeholder="SKU / 商品 / 分类 / 系列 / 规格属性 / 条码" />
        <el-select v-model="filters.categoryId" clearable filterable placeholder="产品分类">
          <el-option v-for="item in categories ?? []" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-select v-model="filters.productSeriesId" clearable filterable placeholder="产品系列">
          <el-option v-for="item in series ?? []" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-select v-model="filters.status" clearable placeholder="状态">
          <el-option label="启用" value="ENABLED" />
          <el-option label="不可用" value="DISABLED" />
        </el-select>
        <el-select v-model="filters.completeness" clearable placeholder="资料完整性">
          <el-option label="资料完整" value="COMPLETE" />
          <el-option label="资料待补" value="INCOMPLETE" />
        </el-select>
        <div class="list-tools-actions">
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </div>
      <div v-if="filterSeriesFields.length" class="product-spec-filter-bar">
        <span class="product-spec-filter-title">规格筛选</span>
        <div v-for="field in filterSeriesFields" :key="field.id" class="product-spec-filter-item">
          <span>{{ field.displayName || field.fieldName }}</span>
          <el-select
            v-if="fieldOptions(field).length"
            :model-value="attributeFilters[field.id]"
            clearable
            filterable
            placeholder="全部"
            @update:model-value="setAttributeFilter(field.id, $event)"
          >
            <el-option v-for="option in fieldOptions(field)" :key="option" :label="option" :value="option" />
          </el-select>
          <el-input
            v-else-if="field.fieldType === 'NUMBER'"
            :model-value="textAttributeFilter(field.id)"
            type="number"
            inputmode="decimal"
            :min="field.minValue"
            :max="field.maxValue"
            :step="decimalStep(field.decimalScale)"
            placeholder="精确值"
            @input="setNumericAttributeFilter(field.id, $event)"
          />
          <el-input
            v-else
            :model-value="textAttributeFilter(field.id)"
            clearable
            placeholder="包含内容"
            @update:model-value="setAttributeFilter(field.id, $event)"
          />
        </div>
      </div>
      <el-table :data="filteredProducts" class="data-table" size="small" border show-overflow-tooltip max-height="calc(100vh - 274px)">
        <el-table-column prop="id" label="编号" width="80" fixed="left" />
        <el-table-column prop="sku" label="SKU（系统生成）" width="160" />
        <el-table-column prop="name" label="商品" width="180">
          <template #default="{ row }">
            <el-link v-if="canViewComplex" type="primary" underline="never" @click="router.push(`/products/${row.id}`)">{{ row.name }}</el-link>
            <span v-else>{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="categoryName" label="分类" min-width="110" />
        <el-table-column prop="productSeriesName" label="系列" min-width="130" />
        <el-table-column label="规格摘要" min-width="320">
          <template #default="{ row }">{{ productSpecSummary(row) || '-' }}</template>
        </el-table-column>
        <el-table-column prop="barcode" label="条码" min-width="140" />
        <el-table-column prop="originalNumber" label="来时编码" min-width="140" />
        <el-table-column prop="unit" label="单位" width="80" />
        <el-table-column label="默认单价" width="130" align="right">
          <template #default="{ row }">{{ money(row.defaultPrice, 4) }}</template>
        </el-table-column>
        <el-table-column label="资料完整性" width="120">
          <template #default="{ row }">
            <el-tag :type="row.masterDataComplete ? 'success' : 'warning'" size="small">
              {{ row.masterDataComplete ? '完整' : `待补 ${row.missingRequiredFields.length} 项` }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="经营状态" width="170">
          <template #default="{ row }">
            <el-tag :type="productBusinessStatusPresentation(row).type" size="small">
              {{ productBusinessStatusPresentation(row).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="启停状态" width="100">
          <template #default="{ row }">{{ statusName(row.status) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="128" fixed="right">
          <template #default="{ row }">
            <div class="product-table-actions">
              <el-button v-if="canManageProduct" text type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button v-if="canManageProduct" text type="primary" @click="openCopy(row)">复制</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="980px" destroy-on-close>
      <el-steps :active="activeStep" align-center finish-status="success" class="product-create-steps">
        <el-step title="商品与规格" description="选择系列并录入经营规格" />
        <el-step title="单位与供货" description="确认主单位，可选录入包装和供应商" />
      </el-steps>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="112px" class="product-create-form">
        <el-alert
          v-if="copyingSource"
          type="info"
          :closable="false"
          :title="`正在复制 ${copyingSource.name}`"
          :description="copyingNotice"
          show-icon
          style="margin-bottom: 16px"
        />
        <el-alert
          v-if="editingId && form.missingRequiredFields?.length"
          type="warning"
          :closable="false"
          :title="`该商品有 ${form.missingRequiredFields.length} 个后来新增的必填字段待补：${form.missingRequiredFields.join('、')}。可以继续保存其他资料。`"
          style="margin-bottom: 16px"
        />
        <section v-show="activeStep === 0" class="product-step-panel">
          <div class="product-step-heading">
            <strong>先确定产品族</strong>
            <span>选择产品系列后填写实际经营中会询价、开单和查货的规格。</span>
          </div>
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="产品系列" prop="productSeriesId">
                <el-select v-model="form.productSeriesId" :disabled="identityLocked" clearable filterable @change="onSeriesChange">
                  <el-option v-for="item in series ?? []" :key="item.id" :label="`${item.categoryName || '未分类'} / ${item.name}`" :value="item.id" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="规格预设">
                <el-select
                  v-model="form.specPresetId"
                  clearable
                  filterable
                  :loading="seriesMasterLoading"
                  :disabled="identityLocked || !form.productSeriesId"
                  :placeholder="presetSelectPlaceholder"
                  @change="applyPreset"
                >
                  <el-option v-for="item in seriesPresets" :key="item.id" :label="`${item.name} ${item.model}`" :value="item.id" />
                  <template #empty>
                    <div class="preset-select-empty">
                      <span>{{ seriesPresetEmptyText }}</span>
                      <el-button
                        v-if="form.productSeriesId && !seriesMasterLoading && !seriesMasterLoadError"
                        text
                        type="primary"
                        @click.stop="openSeriesPresetManagement"
                      >
                        去维护规格预设
                      </el-button>
                    </div>
                  </template>
                </el-select>
                <div v-if="form.productSeriesId && !seriesMasterLoading && !seriesPresets.length" class="product-field-help">
                  规格预设是可选快捷项；没有预设也可以直接填写下方经营规格。
                </div>
              </el-form-item>
            </el-col>
          </el-row>
          <el-alert
            v-if="selectedSeries"
            type="info"
            :closable="false"
            :title="`已选择 ${selectedSeries.categoryName || '未分类'} / ${selectedSeries.name}`"
            :description="seriesIdentitySummary"
          />
        </section>

        <section v-show="activeStep === 0" class="product-step-panel">
          <div class="product-step-heading">
            <strong>填写经营规格</strong>
            <span>带“区分”标记的字段用于防止重复商品；系统自动检查，不需要另行维护规则。</span>
          </div>
          <el-alert v-if="identityLocked" type="info" :closable="false" :title="identityLockMessage" class="duplicate-alert" />
          <el-alert
            v-if="duplicateAlert"
            :type="duplicateAlert.type"
            :title="duplicateAlert.title"
            :description="duplicateAlert.description"
            :closable="false"
            show-icon
            class="duplicate-alert"
          />
          <el-alert
            v-if="seriesFields.some((field) => referenceTypeForField(field))"
            type="info"
            :closable="false"
            title="牌号、材料状态和执行标准可以直接按厂家资料录入"
            description="可选择已有值，也可直接键入厂家写法；未匹配规范库不会阻断采购、销售或库存业务。"
            class="duplicate-alert"
          />
          <el-row :gutter="12">
            <el-col v-for="field in seriesFields" :key="field.id" :span="8">
              <el-form-item
                :label="`${field.displayName}${field.unitName ? `(${field.unitName})` : ''}`"
                :required="field.required"
              >
                <template #label>
                  <span>{{ field.displayName }}{{ field.unitName ? `(${field.unitName})` : '' }}</span>
                  <el-tag v-if="isIdentityField(field)" size="small" type="warning" effect="plain" class="identity-field-tag">区分</el-tag>
                </template>
                <el-select
                  v-if="referenceTypeForField(field)"
                  :model-value="form.attributeValues[field.id]"
                  :disabled="identityLocked && isIdentityField(field)"
                  clearable
                  filterable
                  allow-create
                  default-first-option
                  placeholder="选择或直接输入厂家写法"
                  @update:model-value="setProductAttributeValue(field.id, $event)"
                >
                  <el-option
                    v-for="option in referenceOptions(field)"
                    :key="option.id"
                    :label="`${option.code} · ${option.name}`"
                    :value="option.code"
                  />
                </el-select>
                <el-select
                  v-else-if="fieldOptions(field).length"
                  :model-value="form.attributeValues[field.id]"
                  :disabled="identityLocked && isIdentityField(field)"
                  clearable
                  filterable
                  @update:model-value="setProductAttributeValue(field.id, $event)"
                >
                  <el-option v-for="option in fieldOptions(field)" :key="option" :label="option" :value="option" />
                </el-select>
                <el-input-number
                  v-else-if="field.fieldType === 'NUMBER'"
                  :model-value="numericProductAttributeValue(field.id)"
                  :disabled="identityLocked && isIdentityField(field)"
                  :precision="field.decimalScale"
                  :min="field.minValue ?? undefined"
                  :max="field.maxValue ?? undefined"
                  style="width: 100%"
                  @update:model-value="setProductAttributeValue(field.id, $event)"
                :controls="false" />
                <el-input
                  v-else
                  :model-value="textProductAttributeValue(field.id)"
                  :disabled="identityLocked && isIdentityField(field)"
                  @update:model-value="setProductAttributeValue(field.id, $event)"
                />
              </el-form-item>
            </el-col>
          </el-row>

          <el-collapse v-if="productHistoricalValues.length" class="history-field-collapse" style="margin-bottom: 12px">
            <el-collapse-item :title="`历史字段（${productHistoricalValues.length}，数据永久保留）`" name="product-history-fields">
              <el-table :data="productHistoricalValues" size="small" border>
                <el-table-column prop="label" label="历史字段" min-width="160" />
                <el-table-column prop="value" label="保留值" min-width="220" />
                <el-table-column prop="reason" label="说明" min-width="180" />
              </el-table>
            </el-collapse-item>
          </el-collapse>

          <div class="toolbar" style="margin-bottom: 12px">
            <el-button :disabled="identityLocked" @click="generateProductNameText(true)">按规格生成名称</el-button>
            <span class="muted">{{ generatedHint }}</span>
          </div>
          <el-row :gutter="12">
            <el-col :span="24"><el-form-item label="商品名称" prop="name"><el-input v-model="form.name" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="商品条码"><el-input v-model="form.barcode" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="内部原编码"><el-input v-model="form.originalNumber" placeholder="仅保留历史内部编码；供应商货号在下一步维护" /></el-form-item></el-col>
          </el-row>
        </section>

        <section v-show="activeStep === 1" class="product-step-panel">
          <div class="product-step-heading">
            <strong>确定主单位和包装</strong>
            <span>优先继承系列包装模板；只有供应商或商品确有特殊包装时才新增包装变体。</span>
          </div>
          <div class="series-packaging-binding-card">
            <el-row :gutter="12" align="middle">
              <el-col :span="16">
                <el-form-item label="系列计量模板">
                  <el-input :model-value="seriesPackagingTemplateName || '未应用计量包装模板'" disabled />
                  <div class="product-field-help">
                    当前商品随产品系列自动继承；更换模板会影响本系列现有 {{ seriesPackagingBinding?.productCount || 0 }} 个商品的后续业务。
                  </div>
                </el-form-item>
              </el-col>
              <el-col :span="8" class="series-packaging-binding-action">
                <el-button
                  v-if="canManageComplex"
                  type="primary"
                  plain
                  :disabled="!form.productSeriesId"
                  @click="openSeriesTemplateEditor"
                >
                  {{ seriesPackagingBinding?.templateId ? '更换系列模板' : '选择系列模板' }}
                </el-button>
              </el-col>
            </el-row>
            <el-alert
              v-if="seriesTemplateEditing"
              type="warning"
              :closable="false"
              title="这是系列级设置，不是当前商品私有设置；应用后本系列所有商品都会生成新的计量包装有效版本。"
              style="margin-bottom: 12px"
            />
            <el-row v-if="seriesTemplateEditing" :gutter="12">
              <el-col :span="10">
                <el-form-item label="选择模板" required>
                  <el-select v-model="seriesTemplateForm.templateId" filterable placeholder="选择已启用的计量包装模板">
                    <el-option
                      v-for="item in selectablePackagingTemplates"
                      :key="item.id"
                      :label="`${item.name}（v${item.currentRevisionNo}，主单位 ${item.baseUnit}）`"
                      :value="item.id"
                    />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="10">
                <el-form-item label="变更原因" required>
                  <el-input v-model="seriesTemplateForm.changeReason" maxlength="255" placeholder="说明为何为本系列应用或更换模板" />
                </el-form-item>
              </el-col>
              <el-col :span="4" class="series-packaging-binding-action">
                <el-button :disabled="seriesTemplateSaving" @click="seriesTemplateEditing = false">取消</el-button>
                <el-button type="primary" :loading="seriesTemplateSaving" @click="saveSeriesTemplateBinding">应用</el-button>
              </el-col>
            </el-row>
          </div>
          <el-row :gutter="12">
            <el-col :span="12">
              <el-form-item label="库存主单位" prop="unit">
                <el-select v-model="form.unit" filterable :disabled="Boolean(editingId || seriesPackagingTemplateName)">
                  <el-option v-for="item in inventoryUnitOptions" :key="item" :label="item" :value="item" />
                </el-select>
                <div class="muted product-unit-source">
                  {{ editingId ? '主库存单位由计量包装版本管理，不在商品资料中直接修改' : seriesPackagingTemplateName ? `继承“${seriesPackagingTemplateName}”` : '系列未应用包装模板，请先确认主库存单位' }}
                </div>
              </el-form-item>
            </el-col>
            <el-col :span="12"><el-form-item label="参考单价"><el-input-number v-model="form.defaultPrice" :min="0" :precision="4" style="width: 100%" :controls="false" /></el-form-item></el-col>
            <el-col :span="12">
              <el-form-item label="商品状态">
                <el-select v-model="form.status"><el-option label="启用" value="ENABLED" /><el-option label="不可用" value="DISABLED" /></el-select>
              </el-form-item>
            </el-col>
          </el-row>
          <el-divider content-position="left">特殊包装变体</el-divider>
          <el-alert v-if="!canManageComplex" type="info" :closable="false" title="当前账号可维护基础商品，但无权维护版本化包装变体。" />
          <el-table v-if="packagingVariants.length" :data="packagingVariants" border size="small" class="related-master-list">
            <el-table-column prop="variantCode" label="包装编码" min-width="130" />
            <el-table-column prop="name" label="名称" min-width="150" />
            <el-table-column prop="packageUnit" label="包装单位" width="100" />
            <el-table-column prop="conversionMode" label="换算方式" width="130" />
            <el-table-column prop="innerSpec" label="包装内规格" min-width="180" />
            <el-table-column label="操作" width="90">
              <template #default="{ row }"><el-button v-if="canManageComplex" text type="primary" @click="hydratePackagingVariant(row)">编辑</el-button><span v-else class="muted">只读</span></template>
            </el-table-column>
          </el-table>
          <el-button v-if="canManageComplex && editingId && packagingVariants.length" text type="primary" @click="startNewPackagingVariant">+ 新增包装变体</el-button>
          <el-switch
            v-if="canManageComplex"
            v-model="packagingVariantEnabled"
            :disabled="Boolean(packagingVariantId)"
            :active-text="packagingVariantId ? '正在维护已存在的特殊包装；停用请修改使用状态' : '同时建立系列模板之外的特殊包装'"
          />
          <el-row v-if="packagingVariantEnabled" :gutter="12" class="related-master-form">
            <el-col :span="12"><el-form-item label="包装编码"><el-input v-model="packagingVariantForm.variantCode" :disabled="Boolean(packagingVariantId)" :placeholder="packagingVariantId ? '建档后保持不变' : '如 COIL-500KG'" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="包装名称"><el-input v-model="packagingVariantForm.name" placeholder="如 大散盘" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="包装单位"><el-select v-model="packagingVariantForm.packageUnit" filterable placeholder="请先在系列允许单位中配置"><el-option v-for="item in packagingUnitOptions" :key="item" :label="item" :value="item" /></el-select></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="换算方式"><el-select v-model="packagingVariantForm.conversionMode"><el-option label="固定换算" value="FIXED" /><el-option label="实际称重" value="ACTUAL_WEIGHT" /><el-option label="理论换算" value="THEORETICAL" /></el-select></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="固定基础量"><el-input-number v-model="packagingVariantForm.fixedBaseQuantity" :min="0" :precision="6" style="width:100%" :controls="false" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="参考基础量"><el-input-number v-model="packagingVariantForm.referenceBaseQuantity" :min="0" :precision="6" style="width:100%" :controls="false" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="净重(kg)"><el-input-number v-model="packagingVariantForm.netWeight" :min="0" :precision="6" style="width:100%" :controls="false" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="毛重(kg)"><el-input-number v-model="packagingVariantForm.grossWeight" :min="0" :precision="6" style="width:100%" :controls="false" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="皮重(kg)"><el-input-number v-model="packagingVariantForm.tareWeight" :min="0" :precision="6" style="width:100%" :controls="false" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="包装内规格"><el-input v-model="packagingVariantForm.innerSpec" placeholder="盘型、线轴、每箱数量、内外径等" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="包装条码"><el-input v-model="packagingVariantForm.barcode" /></el-form-item></el-col>
            <el-col v-if="packagingVariantId" :span="12">
              <el-form-item label="使用状态" required>
                <el-select v-model="packagingVariantForm.status">
                  <el-option label="启用（允许新业务选择）" value="ENABLED" />
                  <el-option label="停用（仅保留历史）" value="DISABLED" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col v-if="packagingVariantId" :span="12"><el-form-item label="变更原因" required><el-input v-model="packagingVariantForm.changeReason" placeholder="说明启停或换算条件变化" /></el-form-item></el-col>
            <el-col v-if="packagingVariantId && packagingVariantForm.status === 'DISABLED'" :span="24">
              <el-alert type="warning" :closable="false" title="停用后新单不能再选择该包装变体；历史订单和冻结版本不变。已绑定供货档案请同步调整。" />
            </el-col>
          </el-row>
        </section>

        <section v-show="activeStep === 1" class="product-step-panel">
          <div class="product-step-heading">
            <strong>绑定供应商供货条件（可选）</strong>
            <span>供应商货号、起订量、交期和包装单独维护，不改变内部商品和历史采购记录。</span>
          </div>
          <el-alert v-if="!canViewSupplierOffers" type="info" :closable="false" title="当前账号无采购成本查看权限，供应商报价与供货条款已隐藏。" />
          <el-alert v-else-if="!canManageSupplierOffers" type="info" :closable="false" title="当前账号可查看供应商供货档案，但无权新增或修改。" />
          <el-table v-if="canViewSupplierOffers && supplierOffers.length" :data="supplierOffers" border size="small" class="related-master-list">
            <el-table-column prop="supplierName" label="供应商" min-width="150" />
            <el-table-column prop="supplierProductCode" label="供应商货号" min-width="140" />
            <el-table-column prop="supplierModel" label="型号" min-width="120" />
            <el-table-column label="采购/计价单位" width="140">
              <template #default="{ row }">{{ row.purchaseUnit }} / {{ row.priceUnit }}</template>
            </el-table-column>
            <el-table-column label="报价" width="140"><template #default="{ row }">{{ row.quotedUnitPrice == null ? '-' : `${row.currencyCode} ${money(row.quotedUnitPrice, 4)}` }}</template></el-table-column>
            <el-table-column label="MOQ/倍数" width="130">
              <template #default="{ row }">{{ row.minimumOrderQuantity || '-' }} / {{ row.orderMultiple || '-' }}</template>
            </el-table-column>
            <el-table-column prop="leadTimeDays" label="交期(天)" width="90" />
            <el-table-column label="操作" width="90">
              <template #default="{ row }"><el-button v-if="canManageSupplierOffers" text type="primary" @click="hydrateSupplierOffer(row)">编辑</el-button><span v-else class="muted">只读</span></template>
            </el-table-column>
          </el-table>
          <el-button v-if="canManageSupplierOffers && editingId && supplierOffers.length" text type="primary" @click="startNewSupplierOffer">+ 新增另一供应商</el-button>
          <el-switch
            v-if="canManageSupplierOffers"
            v-model="supplierOfferEnabled"
            :disabled="Boolean(supplierOfferId)"
            :active-text="supplierOfferId ? '正在维护已存在的供货关系；停用请修改使用状态' : '同时建立供应商供货关系'"
          />
          <el-row v-if="supplierOfferEnabled" :gutter="12" class="related-master-form">
            <el-col :span="12"><el-form-item label="供应商"><PartnerRemoteSelect v-model="supplierOfferForm.supplierId" type="SUPPLIER" clearable :disabled="Boolean(supplierOfferId)" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="供应商货号"><el-input v-model="supplierOfferForm.supplierProductCode" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="供应商型号"><el-input v-model="supplierOfferForm.supplierModel" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="采购单位"><el-select v-model="supplierOfferForm.purchaseUnit" filterable><el-option v-for="item in purchaseUnitOptions" :key="item" :label="item" :value="item" /></el-select></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="计价单位"><el-select v-model="supplierOfferForm.priceUnit" filterable><el-option v-for="item in priceUnitOptions" :key="item" :label="item" :value="item" /></el-select></el-form-item></el-col>
            <el-col :span="12">
              <el-form-item label="参考报价（可空）">
                <el-input-number v-model="supplierOfferForm.quotedUnitPrice" :min="0.00000001" :precision="8" placeholder="留空仅记录供货条款" style="width:100%" :controls="false" />
              </el-form-item>
            </el-col>
            <el-col :span="12"><el-form-item label="起订量"><el-input-number v-model="supplierOfferForm.minimumOrderQuantity" :min="0" :precision="6" style="width:100%" :controls="false" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="订货倍数"><el-input-number v-model="supplierOfferForm.orderMultiple" :min="0" :precision="6" style="width:100%" :controls="false" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="交期(天)"><el-input-number v-model="supplierOfferForm.leadTimeDays" :min="0" :precision="0" style="width:100%" :controls="false" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="币种"><el-select v-model="supplierOfferForm.currencyCode"><el-option label="人民币 CNY" value="CNY" /><el-option label="美元 USD" value="USD" /><el-option label="欧元 EUR" value="EUR" /></el-select></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="含税"><el-switch v-model="supplierOfferForm.priceIncludesTax" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="税率(%)"><el-input-number v-model="supplierOfferForm.taxRate" :min="0" :max="100" :precision="2" style="width:100%" :controls="false" /></el-form-item></el-col>
            <el-col :span="12">
              <el-form-item label="对应包装">
                <el-select v-model="supplierOfferForm.packagingVariantRevisionId" clearable placeholder="沿用系列包装或选择特殊包装">
                  <el-option v-for="item in packagingVariants" :key="item.currentRevisionId" :label="`${item.name} v${item.revisionNo}（${item.packageUnit}）`" :value="item.currentRevisionId" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12"><el-form-item label="生效日期"><el-date-picker v-model="supplierOfferForm.effectiveFrom" value-format="YYYY-MM-DD" placeholder="必填" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="失效日期"><el-date-picker v-model="supplierOfferForm.effectiveTo" clearable value-format="YYYY-MM-DD" placeholder="长期有效" /></el-form-item></el-col>
            <el-col v-if="supplierOfferId" :span="12">
              <el-form-item label="使用状态" required>
                <el-select v-model="supplierOfferForm.status">
                  <el-option label="启用（允许新采购）" value="ENABLED" />
                  <el-option label="停用（仅保留历史）" value="DISABLED" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col v-if="supplierOfferId" :span="12"><el-form-item label="变更原因" required><el-input v-model="supplierOfferForm.changeReason" placeholder="说明启停或供货条件变化" /></el-form-item></el-col>
            <el-col v-if="supplierOfferId && supplierOfferForm.status === 'DISABLED'" :span="24">
              <el-alert type="warning" :closable="false" title="停用后该供货关系不再参与新采购选价和包装建议；历史采购、入库和价格快照保持不变。" />
            </el-col>
            <el-col :span="24"><el-alert type="info" :closable="false" title="报价留空时仅记录供货条款；采购开单将另取价格中心建议价或由有权限人员手工定价。" /></el-col>
          </el-row>
          <el-alert
            v-else
            type="info"
            :closable="false"
            title="可以先保存内部商品，之后在商品编辑中补充多个供应商的供货关系。"
          />
        </section>
      </el-form>
      <template #footer>
        <el-button @click="closeDialog">取消</el-button>
        <el-button v-if="activeStep > 0" :disabled="saving" @click="activeStep--">上一步</el-button>
        <el-button v-if="activeStep < 1" type="primary" :disabled="saving" @click="nextStep">下一步</el-button>
        <el-button v-else type="primary" :loading="saving" :disabled="saving || duplicateBlocksSave" @click="save">
          {{ editingId ? '保存全部变更' : copyingSource ? '创建复制商品' : '创建商品' }}
        </el-button>
      </template>
    </el-dialog>

  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRoute, useRouter } from 'vue-router'
import {
  api,
  type Product,
  type ProductDuplicateCheck,
  type ProductPackagingVariant,
  type ProductPackagingVariantSave,
  type ProductAllowedUnit,
  type ProductReferenceType,
  type PackagingTemplate,
  type SeriesField,
  type SeriesPackagingBinding,
  type SpecPreset,
  type SupplierProductOffer,
  type SupplierProductOfferSave,
} from '@/api/client'
import { money, statusName } from '@/api/format'
import { matchesKeyword } from '@/utils/listFilters'
import { generateProductName } from '@/utils/productNaming'
import { productBusinessStatusPresentation, productSpecSummary } from '@/utils/productDisplay'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import { useAppStore } from '@/stores/app'

interface ProductForm extends Partial<Product> {
  attributeValues: Record<number, string | number | undefined>
}

type HistoricalFieldValue = { key: string; label: string; value: string; reason: string }

const queryClient = useQueryClient()
const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const canManageProduct = computed(() => appStore.hasPermission('product.manage'))
const canViewComplex = computed(() =>
  appStore.hasPermission('product.master.complex.view')
  || appStore.hasPermission('product.master.complex.manage'),
)
const canManageComplex = computed(() => appStore.hasPermission('product.master.complex.manage'))
const canViewSupplierOffers = computed(() =>
  appStore.hasPermission('product.cost.view')
  || appStore.hasPermission('product.supplier.offer.manage'),
)
const canManageSupplierOffers = computed(() => appStore.hasPermission('product.supplier.offer.manage'))
const dialogVisible = ref(false)
const activeStep = ref(0)
const formRef = ref<FormInstance>()
const editingId = ref<number>()
const editingProduct = ref<Product>()
const copyingSource = ref<Product>()
const copyingNotice = ref('')
const originalSeriesId = ref<number>()
const loadedFormSeriesId = ref<number>()
const originalAttributeValues = ref<Record<number, string | number | undefined>>({})
const seriesFields = ref<SeriesField[]>([])
const seriesAllowedUnits = ref<ProductAllowedUnit[]>([])
const seriesPresets = ref<SpecPreset[]>([])
const seriesMasterLoading = ref(false)
const seriesMasterLoadError = ref('')
const seriesPackagingBinding = ref<SeriesPackagingBinding>()
const seriesTemplateEditing = ref(false)
const seriesTemplateSaving = ref(false)
const seriesTemplateForm = reactive({ templateId: undefined as number | undefined, changeReason: '' })
const filterSeriesFields = ref<SeriesField[]>([])
const attributeFilters = reactive<Record<number, string | number | undefined>>({})
const productHistoricalValues = ref<HistoricalFieldValue[]>([])
const seriesPackagingTemplateName = ref('')
const duplicateCheck = ref<ProductDuplicateCheck>()
const duplicateCheckFailedMessage = ref('')
const checkingDuplicate = ref(false)
const packagingVariantEnabled = ref(false)
const packagingVariantId = ref<number>()
const packagingVariants = ref<ProductPackagingVariant[]>([])
const originalPackagingVariantFingerprint = ref('')
const supplierOfferEnabled = ref(false)
const supplierOfferId = ref<number>()
const supplierOffers = ref<SupplierProductOffer[]>([])
const originalSupplierOfferFingerprint = ref('')
let duplicateCheckTimer: ReturnType<typeof setTimeout> | undefined
let duplicateCheckSequence = 0
let seriesMasterLoadSequence = 0
const filters = reactive({
  keyword: '',
  categoryId: undefined as number | undefined,
  productSeriesId: undefined as number | undefined,
  status: '',
  completeness: '',
})
const form = reactive<ProductForm>({
  categoryId: undefined,
  productSeriesId: undefined,
  specPresetId: undefined,
  sku: '',
  name: '',
  attributeJson: '{}',
  attributeValues: {},
  barcode: '',
  originalNumber: '',
  unit: 'kg',
  defaultPrice: 0,
  status: 'ENABLED',
  fieldValues: [],
  masterDataComplete: true,
  missingRequiredFields: [],
})
const packagingVariantForm = reactive<ProductPackagingVariantSave>({
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
  lengthMm: null,
  widthMm: null,
  heightMm: null,
  barcode: '',
  supplierId: null,
  sourceDocumentRevisionId: null,
  status: 'ENABLED',
  changeReason: '商品主数据建档',
  expectedRevisionNo: 0,
})
const supplierOfferForm = reactive<SupplierProductOfferSave>({
  supplierId: undefined,
  supplierProductCode: '',
  supplierModel: '',
  purchaseUnit: 'kg',
  priceUnit: 'kg',
  quotedUnitPrice: null,
  currencyCode: 'CNY',
  priceIncludesTax: true,
  taxRate: 13,
  minimumOrderQuantity: null,
  orderMultiple: null,
  leadTimeDays: null,
  effectiveFrom: todayString(),
  effectiveTo: null,
  packagingVariantRevisionId: null,
  sourceDocumentRevisionId: null,
  commercialTermsJson: '{}',
  status: 'ENABLED',
  changeReason: '首次建立供货关系',
  expectedRevisionNo: 0,
})
const dialogTitle = computed(() => (
  editingId.value ? '编辑商品' : copyingSource.value ? '复制新增商品' : '新增商品'
))
const { data: products } = useQuery({ queryKey: ['products'], queryFn: api.products })
const { data: series } = useQuery({ queryKey: ['product-series'], queryFn: api.productSeries })
const { data: categories } = useQuery({ queryKey: ['product-categories'], queryFn: api.productCategories })
const { data: units } = useQuery({ queryKey: ['units'], queryFn: api.units })
const { data: packagingTemplates } = useQuery({ queryKey: ['packaging-templates'], queryFn: api.packagingTemplates })
const { data: referenceValues } = useQuery({
  queryKey: ['product-reference-values', 'enabled'],
  queryFn: () => api.productReferenceValues({ status: 'ENABLED' }),
})
let routeActionLoading = false
watch(
  [
    () => route.query.action,
    () => route.query.productId,
    () => products.value,
  ],
  async ([actionValue, productIdValue, productRows]) => {
    if (route.path !== '/products' || routeActionLoading) return
    const action = Array.isArray(actionValue) ? actionValue[0] : actionValue
    if (action !== 'edit' && action !== 'copy') return
    if (!productRows) return
    const idText = Array.isArray(productIdValue) ? productIdValue[0] : productIdValue
    const row = productRows.find((item) => item.id === Number(idText))
    routeActionLoading = true
    try {
      await router.replace({ path: '/products' })
      if (!row) {
        ElMessage.warning('未找到要操作的商品，请刷新商品列表后重试')
        return
      }
      if (!canManageProduct.value) {
        ElMessage.warning('当前账号无商品维护权限')
        return
      }
      if (action === 'copy') await openCopy(row)
      else await openEdit(row)
    } finally {
      routeActionLoading = false
    }
  },
  { immediate: true },
)
const legacyUnitOptions = computed(() => [...new Set([
  form.unit,
  ...(units.value ?? [])
    .filter((item) => item.status === 'ENABLED')
    .map((item) => item.abbreviation || item.name),
].map((item) => String(item || '').trim()).filter(Boolean))])
const inventoryUnitOptions = computed(() => seriesAllowedUnits.value.length
  ? allowedUnitOptions('INVENTORY', form.unit)
  : legacyUnitOptions.value)
const packagingUnitOptions = computed(() => canViewComplex.value
  ? allowedUnitOptions('PACKAGING', packagingVariantForm.packageUnit)
  : legacyUnitOptions.value)
const purchaseUnitOptions = computed(() => canViewComplex.value
  ? allowedUnitOptions('PURCHASE', supplierOfferForm.purchaseUnit)
  : legacyUnitOptions.value)
const priceUnitOptions = computed(() => canViewComplex.value
  ? allowedUnitOptions('PRICE', supplierOfferForm.priceUnit)
  : legacyUnitOptions.value)
const filteredProducts = computed(() =>
  (products.value ?? []).filter(
    (item) =>
      (!filters.categoryId || item.categoryId === filters.categoryId) &&
      (!filters.productSeriesId || item.productSeriesId === filters.productSeriesId) &&
      (!filters.status || item.status === filters.status) &&
      (!filters.completeness || (filters.completeness === 'COMPLETE' ? item.masterDataComplete : !item.masterDataComplete)) &&
      matchesAttributeFilters(item) &&
      matchesKeyword(item, filters.keyword, ['categoryName', 'productSeriesName', 'sku', 'name', 'orderSpecText', 'spec', 'attributeJson', 'barcode', 'originalNumber', 'unit']),
  ),
)

watch(
  () => filters.productSeriesId,
  async (seriesId) => {
    Object.keys(attributeFilters).forEach((key) => delete attributeFilters[Number(key)])
    filterSeriesFields.value = []
    if (!seriesId) return
    const fields = await api.seriesFields(seriesId)
    if (filters.productSeriesId !== seriesId) return
    filterSeriesFields.value = fields.filter((field) => field.status === 'ENABLED' && field.searchable)
  },
)

watch(
  () => [form.productSeriesId, JSON.stringify(form.attributeValues)],
  () => scheduleDuplicateCheck(),
)

watch(
  () => form.unit,
  (unit) => {
    if (!supplierOfferForm.purchaseUnit) supplierOfferForm.purchaseUnit = unit || ''
    if (!supplierOfferForm.priceUnit) supplierOfferForm.priceUnit = unit || ''
  },
)

onBeforeUnmount(() => {
  if (duplicateCheckTimer) clearTimeout(duplicateCheckTimer)
})

function scheduleDuplicateCheck() {
  duplicateCheck.value = undefined
  duplicateCheckFailedMessage.value = ''
  if (duplicateCheckTimer) clearTimeout(duplicateCheckTimer)
  if (!dialogVisible.value || !form.productSeriesId) return
  duplicateCheckTimer = setTimeout(() => void runDuplicateCheck(), 400)
}

async function runDuplicateCheck() {
  if (!form.productSeriesId) return undefined
  if (!canViewComplex.value) {
    const result = localProductDuplicateCheck()
    duplicateCheck.value = result
    duplicateCheckFailedMessage.value = ''
    checkingDuplicate.value = false
    return result
  }
  const sequence = ++duplicateCheckSequence
  checkingDuplicate.value = true
  try {
    const result = await api.checkProductDuplicate(productDuplicatePayload())
    if (sequence === duplicateCheckSequence) {
      duplicateCheck.value = result
      duplicateCheckFailedMessage.value = ''
    }
    return result
  } catch {
    const currentCode = String(form.sku || '').trim().toLowerCase()
    const localMatch = currentCode
      ? (products.value ?? []).find((item) => item.id !== editingId.value && item.sku.trim().toLowerCase() === currentCode)
      : undefined
    const fallback: ProductDuplicateCheck | undefined = localMatch
      ? {
          result: 'SKU_CONFLICT',
          existingProductId: localMatch.id,
          existingSku: localMatch.sku,
          existingName: localMatch.name,
          complete: false,
          warnings: [`系统编号已被“${localMatch.name}”使用；服务端重复规格检查暂不可用。`],
        }
      : undefined
    if (sequence === duplicateCheckSequence) {
      duplicateCheck.value = fallback
      duplicateCheckFailedMessage.value = fallback ? '' : '重复检查失败，无法确认是否已有相同规格；请重试后再继续'
    }
    return fallback
  } finally {
    if (sequence === duplicateCheckSequence) checkingDuplicate.value = false
  }
}

function localProductDuplicateCheck(): ProductDuplicateCheck {
  const currentCode = String(form.sku || '').trim().toLowerCase()
  const localMatch = currentCode
    ? (products.value ?? []).find((item) =>
      item.id !== editingId.value
      && item.sku.trim().toLowerCase() === currentCode)
    : undefined
  if (localMatch) {
    return {
      result: 'SKU_CONFLICT',
      existingProductId: localMatch.id,
      existingSku: localMatch.sku,
      existingName: localMatch.name,
      complete: false,
      warnings: [`系统编号已被“${localMatch.name}”使用。`],
    }
  }
  return {
    result: 'NONE',
    existingProductId: null,
    existingSku: '',
    existingName: '',
    complete: true,
    warnings: ['当前账号只能执行本地基础预检；保存时系统还会统一检查相同规格。'],
  }
}

function productDuplicatePayload() {
  const { attributeJson, fieldValues } = serializeProductFields()
  return {
    excludeProductId: editingId.value,
    categoryId: form.categoryId,
    productSeriesId: form.productSeriesId,
    specPresetId: form.specPresetId,
    sku: form.sku,
    name: form.name,
    attributeJson,
    fieldValues,
    barcode: form.barcode,
    unit: form.unit,
  }
}

async function nextStep() {
  if (!form.productSeriesId) {
    ElMessage.warning('请先选择产品系列')
    return
  }
  const missing = firstMissingRequiredField()
  if (missing) {
    ElMessage.warning(`请填写${missing.displayName || missing.fieldName}`)
    return
  }
  generateProductNameText(false)
  if (!form.name) {
    ElMessage.warning('请填写商品名称')
    return
  }
  const result = await runDuplicateCheck()
  if (!result || result.result === 'EXACT' || result.result === 'SKU_CONFLICT') {
    if (!result) ElMessage.error(duplicateCheckFailedMessage.value || '重复检查失败，请重试')
    return
  }
  activeStep.value = 1
}

function matchesAttributeFilters(product: Product) {
  for (const field of filterSeriesFields.value) {
    const expected = attributeFilters[field.id]
    if (expected === undefined || expected === null || expected === '') continue
    const actual = (product.fieldValues ?? []).find((value) => value.seriesFieldId === field.id)?.value
    if (field.fieldType === 'NUMBER') {
      if (Number(actual) !== Number(expected)) return false
    } else if (field.fieldType === 'SELECT') {
      if (String(actual ?? '') !== String(expected)) return false
    } else if (!String(actual ?? '').toLowerCase().includes(String(expected).toLowerCase())) {
      return false
    }
  }
  return true
}

function setAttributeFilter(fieldId: number, value: string | number | null | undefined) {
  if (value === undefined || value === null || value === '') {
    delete attributeFilters[fieldId]
    return
  }
  attributeFilters[fieldId] = value
}

function setNumericAttributeFilter(fieldId: number, value: string | number) {
  setAttributeFilter(fieldId, value === '' ? undefined : value)
}

function textAttributeFilter(fieldId: number) {
  const value = attributeFilters[fieldId]
  return value == null ? '' : String(value)
}

function decimalStep(scale?: number | null) {
  const digits = Math.max(0, Number(scale ?? 3))
  return digits === 0 ? '1' : `0.${'0'.repeat(digits - 1)}1`
}

const rules: FormRules = {
  productSeriesId: [{ required: true, message: '请选择产品系列', trigger: 'change' }],
  name: [{ required: true, message: '请填写商品名称', trigger: 'blur' }],
  unit: [{ required: true, message: '请填写单位', trigger: 'blur' }],
}
const selectedSeries = computed(() => (series.value ?? []).find((item) => item.id === form.productSeriesId))
const selectedPreset = computed(() => seriesPresets.value.find((item) => item.id === form.specPresetId))
const selectablePackagingTemplates = computed<PackagingTemplate[]>(() =>
  (packagingTemplates.value ?? []).filter((item) =>
    item.status === 'ENABLED' || item.id === seriesPackagingBinding.value?.templateId),
)
const presetSelectPlaceholder = computed(() => {
  if (!form.productSeriesId) return '请先选择产品系列'
  if (seriesMasterLoading.value) return '正在加载规格预设'
  if (seriesMasterLoadError.value) return '规格预设加载失败'
  return seriesPresets.value.length ? '选择常用规格（可选）' : '当前系列暂无规格预设'
})
const seriesPresetEmptyText = computed(() =>
  seriesMasterLoadError.value || '当前系列暂无启用的规格预设',
)
const identityLocked = computed(() => Boolean(
  editingProduct.value
  && (editingProduct.value.identitySignature || editingProduct.value.identityStatus !== 'PENDING_REVIEW'),
))
const identityLockMessage = computed(() => editingProduct.value?.status === 'DISABLED'
  ? '该商品已停用：产品系列及用于区分商品的规格只读；历史业务仍保留。'
  : '为避免把两个商品误合并，产品系列及用于区分商品的规格只读；名称、条码、包装和供应商供货资料仍可维护。')
const identityFields = computed(() => seriesFields.value.filter(isIdentityField))
const seriesIdentitySummary = computed(() => {
  if (!seriesFields.value.length) return '该系列尚未配置经营规格；仍可使用商品名称建档，建议管理员补充常用规格。'
  const names = identityFields.value.map((field) => field.displayName || field.fieldName)
  return names.length
    ? `用于区分商品：${names.join('、')}。保存时自动检查重复。`
    : '当前没有勾选“用于区分商品”的规格，建议管理员补充区分字段，避免重复建档。'
})
const duplicateAlert = computed(() => {
  if (checkingDuplicate.value) return { type: 'info' as const, title: '正在检查重复商品', description: '正在按产品系列和用于区分商品的规格匹配现有商品。' }
  if (duplicateCheckFailedMessage.value) {
    return {
      type: 'error' as const,
      title: '重复检查未完成，禁止保存',
      description: duplicateCheckFailedMessage.value,
    }
  }
  const result = duplicateCheck.value
  if (!result) return undefined
  if (result.result === 'EXACT') {
    return {
      type: 'error' as const,
      title: '发现完全相同的商品',
      description: result.warnings?.join('；') || `现有商品 ${result.existingSku || result.existingProductId} 与当前规格完全一致，请直接维护原商品或新增供应商供货关系。`,
    }
  }
  if (result.result === 'SKU_CONFLICT') {
    return {
      type: 'error' as const,
      title: '系统编号冲突',
      description: result.warnings?.join('；') || '系统内部编号冲突，请刷新后重试。',
    }
  }
  if (result.result === 'INCOMPLETE') {
    return {
      type: 'warning' as const,
      title: '必填规格尚未补齐',
      description: result.warnings?.join('；') || '可以先保存草稿；补齐本系列必填规格后，商品才能用于采购、销售和库存。',
    }
  }
  return {
    type: 'success' as const,
    title: '未发现重复商品',
    description: result.warnings?.join('；') || '当前用于区分商品的规格可以继续保存；提交时系统会再次检查重复。',
  }
})
const duplicateBlocksSave = computed(() =>
  checkingDuplicate.value
  || Boolean(duplicateCheckFailedMessage.value)
  || duplicateCheck.value?.result === 'EXACT'
  || duplicateCheck.value?.result === 'SKU_CONFLICT')
const generatedHint = computed(() => {
  const seriesName = selectedSeries.value?.name
  if (!seriesName) return '先选择产品系列，再选择规格或填写字段。'
  return selectedPreset.value ? `已选择：${seriesName} / ${selectedPreset.value.specText}` : `当前系列：${seriesName}`
})

const createMutation = useMutation({
  mutationFn: api.createProduct,
})

const updateMutation = useMutation({
  mutationFn: ({ id, data }: { id: number; data: Partial<Product> }) => api.updateProduct(id, data),
})
const saving = computed(() => createMutation.isPending.value || updateMutation.isPending.value)

function openCreate() {
  resetForm()
  activeStep.value = 0
  dialogVisible.value = true
}

function handleAdvancedCommand(command: string) {
  if (command === 'matrix') {
    router.push('/products/matrix-create')
    return
  }
  router.push('/products/import')
}

async function openEdit(row: Product) {
  editingId.value = row.id
  editingProduct.value = row
  originalSeriesId.value = row.productSeriesId || undefined
  loadedFormSeriesId.value = row.productSeriesId || undefined
  hydrateProductBaseForm(row)
  await loadSeriesMaster(row.productSeriesId)
  await loadSeriesPackagingSource(row.productSeriesId, false)
  await loadRelatedProductMaster(row.id)
  productHistoricalValues.value = historicalProductValues(row)
  activeStep.value = 0
  dialogVisible.value = true
  await nextTick()
  hydrateProductFieldValues(row)
  originalAttributeValues.value = { ...form.attributeValues }
  scheduleDuplicateCheck()
}

async function openCopy(row: Product) {
  resetForm()
  copyingSource.value = row
  loadedFormSeriesId.value = row.productSeriesId || undefined
  hydrateProductBaseForm(row)
  form.sku = ''
  form.barcode = ''
  form.originalNumber = ''
  form.defaultPrice = 0
  form.status = 'ENABLED'
  await loadSeriesMaster(row.productSeriesId)
  await loadSeriesPackagingSource(row.productSeriesId, false)
  await loadCopiedRelatedProductMaster(row)
  activeStep.value = 0
  dialogVisible.value = true
  await nextTick()
  hydrateProductFieldValues(row)
  scheduleDuplicateCheck()
}

function hydrateProductBaseForm(row: Product) {
  form.categoryId = row.categoryId
  form.productSeriesId = row.productSeriesId || undefined
  form.specPresetId = row.specPresetId || undefined
  form.sku = row.sku
  form.name = row.name
  form.grade = row.grade
  form.temper = row.temper
  form.standard = row.standard
  form.attributeJson = row.attributeJson || '{}'
  form.attributeValues = {}
  form.fieldValues = row.fieldValues ?? []
  form.masterDataComplete = row.masterDataComplete
  form.missingRequiredFields = row.missingRequiredFields ?? []
  form.barcode = row.barcode
  form.originalNumber = row.originalNumber
  form.unit = row.unit
  form.defaultPrice = row.defaultPrice
  form.status = row.status || 'ENABLED'
}

function allowedUnitOptions(purpose: ProductAllowedUnit['purpose'], current?: string | null) {
  const values = seriesAllowedUnits.value
    .filter((item) => item.status === 'ENABLED' && item.purpose === purpose)
    .map((item) => item.abbreviation || item.unitName)
  return [...new Set([current, ...values].map((item) => String(item || '').trim()).filter(Boolean))]
}

function isSeriesAllowedUnit(purpose: ProductAllowedUnit['purpose'], unit?: string | null) {
  const normalized = String(unit || '').trim()
  if (!seriesAllowedUnits.value.length) return Boolean(normalized)
  return !!normalized && seriesAllowedUnits.value.some((item) =>
    item.status === 'ENABLED'
    && item.purpose === purpose
    && (item.abbreviation === normalized || item.unitName === normalized))
}

function defaultSeriesAllowedUnit(purpose: ProductAllowedUnit['purpose']) {
  if (!seriesAllowedUnits.value.length) return String(form.unit || legacyUnitOptions.value[0] || '')
  const matches = seriesAllowedUnits.value.filter((item) => item.status === 'ENABLED' && item.purpose === purpose)
  const item = matches.find((candidate) => candidate.defaultUnit) ?? matches[0]
  return item ? (item.abbreviation || item.unitName) : ''
}

function isIdentityField(field: SeriesField) {
  return field.skuDimension
}

function requestedIdentityStatus(duplicate?: ProductDuplicateCheck) {
  return duplicate?.result === 'INCOMPLETE' ? 'PENDING_REVIEW' as const : 'PUBLISHED' as const
}

async function onSeriesChange() {
  const id = form.productSeriesId
  if (id && id === loadedFormSeriesId.value) return
  loadedFormSeriesId.value = id
  form.categoryId = selectedSeries.value?.categoryId
  form.specPresetId = undefined
  form.attributeValues = {}
  form.grade = ''
  form.temper = ''
  form.standard = ''
  productHistoricalValues.value = []
  seriesFields.value = []
  seriesPresets.value = []
  seriesPackagingTemplateName.value = ''
  seriesPackagingBinding.value = undefined
  seriesTemplateEditing.value = false
  if (!id) {
    if (!editingId.value) form.unit = ''
    return
  }
  await loadSeriesMaster(id)
  if (form.productSeriesId !== id) return
  if (editingProduct.value) productHistoricalValues.value = historicalProductValues(editingProduct.value)
  await loadSeriesPackagingSource(id, !editingId.value)
  if (form.productSeriesId !== id) return
  generateProductNameText(false)
}

async function loadSeriesPackagingSource(seriesId?: number, notify = false) {
  if (!seriesId) return
  const binding = await api.seriesPackagingBinding(seriesId)
  if (form.productSeriesId !== seriesId) return
  seriesPackagingBinding.value = binding
  if (!binding.templateId) {
    seriesPackagingTemplateName.value = ''
    if (!editingId.value && canViewComplex.value) form.unit = ''
    if (notify) ElMessage.warning('该系列尚未应用包装模板，建议先在“计量与包装”中统一配置')
    return
  }
  const template = await api.packagingTemplate(binding.templateId)
  if (form.productSeriesId !== seriesId) return
  seriesPackagingTemplateName.value = `${template.name} v${template.currentRevisionNo}`
  if (!editingId.value || originalSeriesId.value !== seriesId) form.unit = template.baseUnit || form.unit
  if (notify) ElMessage.info(`已沿用“${template.name}”v${template.currentRevisionNo}，只有特殊商品才需要另设包装特例`)
}

function openSeriesTemplateEditor() {
  seriesTemplateForm.templateId = seriesPackagingBinding.value?.templateId || undefined
  seriesTemplateForm.changeReason = ''
  seriesTemplateEditing.value = true
}

async function saveSeriesTemplateBinding() {
  const seriesId = form.productSeriesId
  if (!seriesId || !seriesTemplateForm.templateId) {
    ElMessage.warning('请选择计量包装模板')
    return
  }
  if (!seriesTemplateForm.changeReason.trim()) {
    ElMessage.warning('请填写应用或更换模板的原因')
    return
  }
  seriesTemplateSaving.value = true
  try {
    await api.saveSeriesPackagingBinding(seriesId, {
      templateId: seriesTemplateForm.templateId,
      expectedBindingVersionNo: seriesPackagingBinding.value?.bindingVersionNo || 0,
      changeReason: seriesTemplateForm.changeReason.trim(),
    })
    ElMessage.success('系列计量包装模板已应用，当前商品已自动继承')
    seriesTemplateEditing.value = false
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['packaging-templates'] }),
      queryClient.invalidateQueries({ queryKey: ['series-packaging-bindings'] }),
      queryClient.invalidateQueries({ queryKey: ['product-packaging-summaries'] }),
    ])
    await loadSeriesPackagingSource(seriesId, false)
  } catch (error) {
    ElMessage.error(`系列计量包装模板应用失败：${mutationErrorMessage(error)}`)
  } finally {
    seriesTemplateSaving.value = false
  }
}

async function loadSeriesMaster(seriesId?: number) {
  const sequence = ++seriesMasterLoadSequence
  if (!seriesId) {
    seriesFields.value = []
    seriesPresets.value = []
    seriesAllowedUnits.value = []
    seriesMasterLoading.value = false
    seriesMasterLoadError.value = ''
    return
  }
  seriesMasterLoading.value = true
  seriesMasterLoadError.value = ''
  try {
    const [fields, presets, allowedUnits] = await Promise.all([
      api.seriesFields(seriesId),
      api.specPresets(seriesId),
      api.productSeriesAllowedUnits(seriesId).catch(() => []),
    ])
    if (sequence !== seriesMasterLoadSequence || form.productSeriesId !== seriesId) return
    seriesFields.value = fields.filter((field) => field.status === 'ENABLED')
    seriesPresets.value = presets.filter((preset) => preset.status === 'ENABLED' || preset.id === form.specPresetId)
    seriesAllowedUnits.value = allowedUnits
    if (!editingId.value || originalSeriesId.value !== seriesId) {
      form.unit = defaultSeriesAllowedUnit('INVENTORY')
      supplierOfferForm.purchaseUnit = defaultSeriesAllowedUnit('PURCHASE')
      supplierOfferForm.priceUnit = defaultSeriesAllowedUnit('PRICE')
    }
  } catch (error) {
    if (sequence !== seriesMasterLoadSequence) return
    seriesFields.value = []
    seriesPresets.value = []
    seriesAllowedUnits.value = []
    seriesMasterLoadError.value = mutationErrorMessage(error)
    ElMessage.error(`商品规格配置加载失败：${seriesMasterLoadError.value}`)
  } finally {
    if (sequence === seriesMasterLoadSequence) seriesMasterLoading.value = false
  }
}

function openSeriesPresetManagement() {
  if (!form.productSeriesId) return
  void router.push({
    path: '/product-catalog',
    query: { view: 'specs', seriesId: String(form.productSeriesId), tab: 'presets' },
  })
}

async function loadRelatedProductMaster(productId: number) {
  if (!canViewComplex.value) {
    packagingVariants.value = []
    supplierOffers.value = []
    return
  }
  try {
    const variants = await api.productPackagingVariants(productId)
    packagingVariants.value = variants
    const variant = variants.find((item) => item.status === 'ENABLED') ?? variants[0]
    if (variant) hydratePackagingVariant(variant)
  } catch {
    ElMessage.warning('商品基础资料已加载，但包装变体暂时不可用')
  }
  if (!canViewSupplierOffers.value) {
    supplierOffers.value = []
    return
  }
  try {
    const offers = await api.supplierProductOffers(productId)
    supplierOffers.value = offers
    const offer = offers.find((item) => item.status === 'ENABLED') ?? offers[0]
    if (offer) hydrateSupplierOffer(offer)
  } catch {
    supplierOffers.value = []
    ElMessage.warning('供应商供货档案暂时不可用')
  }
}

async function loadCopiedRelatedProductMaster(source: Product) {
  let enabledPackagingCount = 0
  let enabledOfferCount = 0
  if (canManageComplex.value) {
    try {
      const variants = await api.productPackagingVariants(source.id)
      const enabledVariants = variants.filter((item) => item.status === 'ENABLED')
      enabledPackagingCount = enabledVariants.length
      if (enabledVariants[0]) {
        hydratePackagingVariant(enabledVariants[0])
        packagingVariantId.value = undefined
        originalPackagingVariantFingerprint.value = ''
        Object.assign(packagingVariantForm, {
          variantCode: '',
          barcode: '',
          sourceDocumentRevisionId: null,
          status: 'ENABLED',
          changeReason: `从商品 ${source.name} 复制`,
          expectedRevisionNo: 0,
        })
      }
    } catch {
      ElMessage.warning('商品基础资料已复制，但源商品包装变体暂时无法读取')
    }
  }
  packagingVariants.value = []

  if (canManageSupplierOffers.value) {
    try {
      const offers = await api.supplierProductOffers(source.id)
      const enabledOffers = offers.filter((item) => item.status === 'ENABLED')
      enabledOfferCount = enabledOffers.length
      if (enabledOffers[0]) {
        hydrateSupplierOffer(enabledOffers[0])
        supplierOfferId.value = undefined
        originalSupplierOfferFingerprint.value = ''
        Object.assign(supplierOfferForm, {
          supplierProductCode: '',
          supplierModel: '',
          quotedUnitPrice: null,
          effectiveFrom: todayString(),
          effectiveTo: null,
          packagingVariantRevisionId: null,
          sourceDocumentRevisionId: null,
          status: 'ENABLED',
          changeReason: `从商品 ${source.name} 复制`,
          expectedRevisionNo: 0,
        })
      }
    } catch {
      ElMessage.warning('商品基础资料已复制，但源商品供应商供货条件暂时无法读取')
    }
  }
  supplierOffers.value = []

  const related: string[] = []
  if (enabledPackagingCount) related.push(`1 条启用包装变体${enabledPackagingCount > 1 ? `（源商品共 ${enabledPackagingCount} 条，仅带入第一条）` : ''}`)
  if (enabledOfferCount) related.push(`1 条启用供应商供货条件${enabledOfferCount > 1 ? `（源商品共 ${enabledOfferCount} 条，仅带入第一条）` : ''}`)
  copyingNotice.value = [
    '已带入分类、系列、经营规格、商品名称、库存单位及可复用的计量供货配置。',
    related.length ? `同时带入${related.join('、')}。` : '源商品没有可带入的启用包装或供货条件。',
    '未复制商品 ID、商品条码、内部原编码、参考价、包装/供应商专属编码、历史版本和来源附件，请核对补充后再创建。',
  ].join('')
}

function hydratePackagingVariant(variant: ProductPackagingVariant) {
  packagingVariantEnabled.value = true
  packagingVariantId.value = variant.id
  Object.assign(packagingVariantForm, {
    variantCode: variant.variantCode,
    name: variant.name,
    packageUnit: variant.packageUnit,
    conversionMode: variant.conversionMode,
    fixedBaseQuantity: variant.fixedBaseQuantity,
    referenceBaseQuantity: variant.referenceBaseQuantity,
    innerSpec: variant.innerSpec,
    netWeight: variant.netWeight,
    grossWeight: variant.grossWeight,
    tareWeight: variant.tareWeight,
    lengthMm: variant.lengthMm,
    widthMm: variant.widthMm,
    heightMm: variant.heightMm,
    barcode: variant.barcode,
    supplierId: variant.supplierId,
    sourceDocumentRevisionId: variant.sourceDocumentRevisionId,
    status: variant.status,
    changeReason: '维护商品包装变体',
    expectedRevisionNo: variant.revisionNo,
  })
  originalPackagingVariantFingerprint.value = JSON.stringify(packagingVariantForm)
}

function startNewPackagingVariant() {
  packagingVariantEnabled.value = true
  packagingVariantId.value = undefined
  originalPackagingVariantFingerprint.value = ''
  Object.assign(packagingVariantForm, {
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
    lengthMm: null,
    widthMm: null,
    heightMm: null,
    barcode: '',
    supplierId: null,
    sourceDocumentRevisionId: null,
    status: 'ENABLED',
    changeReason: '新增商品包装变体',
    expectedRevisionNo: 0,
  })
}

function hydrateSupplierOffer(offer: SupplierProductOffer) {
  supplierOfferEnabled.value = true
  supplierOfferId.value = offer.id
  Object.assign(supplierOfferForm, {
    supplierId: offer.supplierId,
    supplierProductCode: offer.supplierProductCode,
    supplierModel: offer.supplierModel,
    purchaseUnit: offer.purchaseUnit,
    priceUnit: offer.priceUnit,
    quotedUnitPrice: offer.quotedUnitPrice,
    currencyCode: offer.currencyCode,
    priceIncludesTax: offer.priceIncludesTax,
    taxRate: offer.taxRate,
    minimumOrderQuantity: offer.minimumOrderQuantity,
    orderMultiple: offer.orderMultiple,
    leadTimeDays: offer.leadTimeDays,
    effectiveFrom: offer.effectiveFrom,
    effectiveTo: offer.effectiveTo,
    packagingVariantRevisionId: offer.packagingVariantRevisionId,
    sourceDocumentRevisionId: offer.sourceDocumentRevisionId,
    commercialTermsJson: offer.commercialTermsJson,
    status: offer.status,
    changeReason: '维护供应商供货条件',
    expectedRevisionNo: offer.revisionNo,
  })
  originalSupplierOfferFingerprint.value = JSON.stringify(supplierOfferForm)
}

function startNewSupplierOffer() {
  supplierOfferEnabled.value = true
  supplierOfferId.value = undefined
  originalSupplierOfferFingerprint.value = ''
  Object.assign(supplierOfferForm, {
    supplierId: undefined,
    supplierProductCode: '',
    supplierModel: '',
    purchaseUnit: form.unit || 'kg',
    priceUnit: form.unit || 'kg',
    quotedUnitPrice: null,
    currencyCode: 'CNY',
    priceIncludesTax: true,
    taxRate: 13,
    minimumOrderQuantity: null,
    orderMultiple: null,
    leadTimeDays: null,
    effectiveFrom: todayString(),
    effectiveTo: null,
    packagingVariantRevisionId: null,
    sourceDocumentRevisionId: null,
    commercialTermsJson: '{}',
    status: 'ENABLED',
    changeReason: '新增供应商供货关系',
    expectedRevisionNo: 0,
  })
}

function hydrateProductFieldValues(row: Product) {
  const legacyAttributes = parseJsonObject(row.attributeJson)
  const hydratedValues: Record<number, string | number> = {}
  for (const field of seriesFields.value) {
    const linkedValue = (row.fieldValues ?? []).find(
      (item) => Number(item.seriesFieldId) === Number(field.id),
    )?.value
    const fallbackValue = legacyAttributes[field.fieldName]
    const value = normalizeProductValueForInput(field, linkedValue ?? fallbackValue)
    if (value !== undefined) hydratedValues[field.id] = value
  }
  Object.keys(form.attributeValues).forEach((key) => delete form.attributeValues[Number(key)])
  Object.assign(form.attributeValues, hydratedValues)
  syncLegacyReferenceFields()
}

function normalizeProductValueForInput(field: SeriesField, value: unknown) {
  if (value === undefined || value === null || value === '') return undefined
  if (field.fieldType === 'NUMBER') {
    const numericValue = typeof value === 'number'
      ? value
      : Number(String(value).trim().match(/^-?\d+(?:\.\d+)?/)?.[0])
    return Number.isFinite(numericValue) ? numericValue : undefined
  }
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}

function setProductAttributeValue(fieldId: number, value: string | number | null | undefined) {
  if (value === undefined || value === null || value === '') {
    delete form.attributeValues[fieldId]
    syncLegacyReferenceFields()
    if (!identityLocked.value) generateProductNameText(true)
    return
  }
  form.attributeValues[fieldId] = value
  syncLegacyReferenceFields()
  if (!identityLocked.value) generateProductNameText(true)
}

function numericProductAttributeValue(fieldId: number) {
  const value = form.attributeValues[fieldId]
  if (typeof value === 'number') return value
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : undefined
}

function textProductAttributeValue(fieldId: number) {
  const value = form.attributeValues[fieldId]
  return value == null ? '' : String(value)
}

function historicalProductValues(row: Product): HistoricalFieldValue[] {
  const activeFieldIds = new Set(seriesFields.value.map((field) => field.id))
  const activeFieldNames = new Set(seriesFields.value.map((field) => field.fieldName))
  const relatedFieldNames = new Set((row.fieldValues ?? []).map((item) => item.fieldName).filter(Boolean))
  const history: HistoricalFieldValue[] = []

  for (const item of row.fieldValues ?? []) {
    if (activeFieldIds.has(item.seriesFieldId)) continue
    history.push({
      key: `related-${item.seriesFieldId}`,
      label: item.displayName || item.fieldName || `字段${item.fieldId || item.seriesFieldId}`,
      value: String(item.value ?? ''),
      reason: item.seriesFieldStatus === 'DISABLED'
        ? '系列字段已停用，商品历史值保留'
        : item.productSeriesId && item.productSeriesId !== row.productSeriesId
          ? '原产品系列字段，商品历史值保留'
          : '历史关联字段，商品历史值保留',
    })
  }

  for (const [fieldName, value] of Object.entries(parseJsonObject(row.attributeJson))) {
    if (activeFieldNames.has(fieldName) || relatedFieldNames.has(fieldName)) continue
    history.push({
      key: `legacy-${fieldName}`,
      label: fieldName,
      value: value == null ? '' : String(value),
      reason: '旧版未关联字段，商品历史值保留',
    })
  }
  return history
}

function applyPreset() {
  const preset = selectedPreset.value
  if (!preset) return
  if (preset.fieldValues?.length) {
    const fieldsBySeriesFieldId = new Map(seriesFields.value.map((field) => [field.id, field]))
    const linkedValues: Array<[number, string | number]> = []
    for (const item of preset.fieldValues) {
      const field = fieldsBySeriesFieldId.get(item.seriesFieldId)
      if (!field) continue
      if (field.fieldType === 'NUMBER') {
        const numericValue = Number(item.value)
        if (Number.isFinite(numericValue)) linkedValues.push([field.id, numericValue])
      } else {
        linkedValues.push([field.id, String(item.value ?? '')])
      }
    }
    form.attributeValues = Object.fromEntries(linkedValues)
  } else {
    const presetValues = parseJsonObject(preset.attributeJson)
    form.attributeValues = Object.fromEntries(seriesFields.value.flatMap((field) => {
      const value = presetValues[field.fieldName]
      return value === undefined ? [] : [[field.id, value]]
    }))
  }
  syncLegacyReferenceFields()
  generateProductNameText(true)
}

function generateProductNameText(force: boolean) {
  const currentSeries = selectedSeries.value
  if (!currentSeries) return
  const preset = selectedPreset.value
  const generatedName = generateProductName(currentSeries, seriesFields.value, form.attributeValues, {
    nameText: preset?.specText,
  })
  if (force || !form.name) form.name = generatedName
}

function fieldOptions(field: SeriesField) {
  const parsed = parseJsonArray(field.optionJson)
  return parsed
}

function referenceTypeForField(field: SeriesField): ProductReferenceType | undefined {
  const names = [field.fieldName, field.displayName].map((item) => String(item || '').replace(/\s/g, ''))
  if (names.some((name) => ['牌号', '材质牌号', '磁材牌号'].includes(name))) return 'GRADE'
  if (names.some((name) => ['材料状态', '状态', '软硬状态'].includes(name))) return 'TEMPER'
  if (names.some((name) => ['执行标准', '标准'].includes(name))) return 'STANDARD'
  return undefined
}

function referenceOptions(field: SeriesField) {
  const type = referenceTypeForField(field)
  return type ? (referenceValues.value ?? []).filter((item) => item.referenceType === type && item.status === 'ENABLED') : []
}

function syncLegacyReferenceFields() {
  for (const field of seriesFields.value) {
    const type = referenceTypeForField(field)
    if (!type) continue
    const value = form.attributeValues[field.id]
    const normalized = value == null ? '' : String(value)
    if (type === 'GRADE') form.grade = normalized
    if (type === 'TEMPER') form.temper = normalized
    if (type === 'STANDARD') form.standard = normalized
  }
}

function parseJsonArray(value: string) {
  try {
    const parsed = JSON.parse(value || '[]')
    return Array.isArray(parsed) ? parsed.map((item) => String(item)) : []
  } catch {
    return []
  }
}

function parseJsonObject(value: string): Record<string, string | number | undefined> {
  try {
    const parsed = JSON.parse(value || '{}') as unknown
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {}
    return Object.fromEntries(Object.entries(parsed).map(([key, item]) => [
      key,
      item == null ? undefined : typeof item === 'number' ? item : typeof item === 'string' ? item : JSON.stringify(item),
    ]))
  } catch {
    return {}
  }
}

function resetForm() {
  activeStep.value = 0
  editingId.value = undefined
  editingProduct.value = undefined
  copyingSource.value = undefined
  copyingNotice.value = ''
  originalSeriesId.value = undefined
  loadedFormSeriesId.value = undefined
  originalAttributeValues.value = {}
  form.categoryId = undefined
  form.productSeriesId = undefined
  form.specPresetId = undefined
  form.sku = ''
  form.name = ''
  form.grade = ''
  form.temper = ''
  form.standard = ''
  form.attributeJson = '{}'
  form.attributeValues = {}
  form.barcode = ''
  form.originalNumber = ''
  form.unit = 'kg'
  form.defaultPrice = 0
  form.status = 'ENABLED'
  form.fieldValues = []
  form.masterDataComplete = true
  form.missingRequiredFields = []
  productHistoricalValues.value = []
  seriesFields.value = []
  seriesAllowedUnits.value = []
  seriesPresets.value = []
  seriesPackagingTemplateName.value = ''
  seriesPackagingBinding.value = undefined
  seriesTemplateEditing.value = false
  seriesTemplateForm.templateId = undefined
  seriesTemplateForm.changeReason = ''
  duplicateCheck.value = undefined
  duplicateCheckFailedMessage.value = ''
  checkingDuplicate.value = false
  packagingVariantEnabled.value = false
  packagingVariantId.value = undefined
  packagingVariants.value = []
  originalPackagingVariantFingerprint.value = ''
  Object.assign(packagingVariantForm, {
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
    lengthMm: null,
    widthMm: null,
    heightMm: null,
    barcode: '',
    supplierId: null,
    sourceDocumentRevisionId: null,
    status: 'ENABLED',
    changeReason: '商品主数据建档',
    expectedRevisionNo: 0,
  })
  supplierOfferEnabled.value = false
  supplierOfferId.value = undefined
  supplierOffers.value = []
  originalSupplierOfferFingerprint.value = ''
  Object.assign(supplierOfferForm, {
    supplierId: undefined,
    supplierProductCode: '',
    supplierModel: '',
    purchaseUnit: 'kg',
    priceUnit: 'kg',
    quotedUnitPrice: null,
    currencyCode: 'CNY',
    priceIncludesTax: true,
    taxRate: 13,
    minimumOrderQuantity: null,
    orderMultiple: null,
    leadTimeDays: null,
    effectiveFrom: todayString(),
    effectiveTo: null,
    packagingVariantRevisionId: null,
    sourceDocumentRevisionId: null,
    commercialTermsJson: '{}',
    status: 'ENABLED',
    changeReason: '首次建立供货关系',
    expectedRevisionNo: 0,
  })
  if (duplicateCheckTimer) clearTimeout(duplicateCheckTimer)
  formRef.value?.clearValidate()
}

function closeDialog() {
  dialogVisible.value = false
  resetForm()
}

async function save() {
  if (saving.value) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  const missing = firstMissingRequiredField()
  if (missing) {
    ElMessage.warning(`请填写${missing.displayName || missing.fieldName}`)
    return
  }
  generateProductNameText(false)
  const duplicate = await runDuplicateCheck()
  if (!duplicate || duplicate.result === 'EXACT' || duplicate.result === 'SKU_CONFLICT') {
    activeStep.value = 0
    if (!duplicate) ElMessage.error(duplicateCheckFailedMessage.value || '重复检查失败，请重试')
    return
  }
  if (!isSeriesAllowedUnit('INVENTORY', form.unit)) {
    activeStep.value = 1
    ElMessage.warning('库存主单位不在当前系列允许范围内，请先维护系列单位映射')
    return
  }
  if (packagingVariantEnabled.value && (!packagingVariantForm.name || !packagingVariantForm.packageUnit || !packagingVariantForm.status || !packagingVariantForm.changeReason.trim())) {
    activeStep.value = 1
    ElMessage.warning('特殊包装需要填写名称、包装单位、使用状态和变更原因')
    return
  }
  if (packagingVariantEnabled.value && !isSeriesAllowedUnit('PACKAGING', packagingVariantForm.packageUnit)) {
    activeStep.value = 1
    ElMessage.warning('包装单位不在当前系列允许范围内，请先维护系列单位映射')
    return
  }
  if (canManageSupplierOffers.value && supplierOfferEnabled.value && (!supplierOfferForm.supplierId || !supplierOfferForm.purchaseUnit || !supplierOfferForm.priceUnit || !supplierOfferForm.effectiveFrom || !supplierOfferForm.status || !supplierOfferForm.changeReason.trim())) {
    ElMessage.warning('供应商供货关系需要选择供应商、采购单位、计价单位、生效日期、使用状态并填写变更原因')
    return
  }
  if (canManageSupplierOffers.value && supplierOfferEnabled.value && supplierOfferForm.quotedUnitPrice != null && Number(supplierOfferForm.quotedUnitPrice) <= 0) {
    ElMessage.warning('供应商参考报价填写时必须大于 0；动态铜价可留空')
    return
  }
  if (canManageSupplierOffers.value && supplierOfferEnabled.value && (!isSeriesAllowedUnit('PURCHASE', supplierOfferForm.purchaseUnit) || !isSeriesAllowedUnit('PRICE', supplierOfferForm.priceUnit))) {
    ElMessage.warning('采购或计价单位不在当前系列允许范围内，请先维护系列单位映射')
    return
  }
  const { attributeJson, fieldValues } = serializeProductFields()
  form.attributeJson = attributeJson
  const {
    attributeValues,
    masterDataComplete,
    missingRequiredFields,
    ...productPayload
  } = form
  const payload = {
    ...productPayload,
    fieldValues,
    identityStatus: requestedIdentityStatus(duplicate),
  }
  let productId = editingId.value
  let productSaved = false
  try {
    if (productId) {
      await updateMutation.mutateAsync({ id: productId, data: payload })
    } else {
      productId = (await createMutation.mutateAsync(payload)).id
    }
    productSaved = true

    let variantId = packagingVariantId.value
    let variantRevisionId = supplierOfferForm.packagingVariantRevisionId
    const packagingChanged = packagingVariantEnabled.value && (
      !variantId || JSON.stringify(packagingVariantForm) !== originalPackagingVariantFingerprint.value
    )
    if (packagingChanged) {
      const savedVariant = variantId
        ? await api.updateProductPackagingVariant(productId, variantId, packagingVariantForm)
        : await api.createProductPackagingVariant(productId, packagingVariantForm)
      variantId = savedVariant.id
      variantRevisionId = savedVariant.currentRevisionId
    }
    const supplierOfferChanged = canManageSupplierOffers.value && supplierOfferEnabled.value && (
      !supplierOfferId.value
      || JSON.stringify(supplierOfferForm) !== originalSupplierOfferFingerprint.value
      || (packagingChanged && variantRevisionId !== supplierOfferForm.packagingVariantRevisionId)
    )
    if (supplierOfferChanged) {
      const offerPayload: SupplierProductOfferSave = {
        ...supplierOfferForm,
        packagingVariantRevisionId: variantRevisionId || null,
      }
      if (supplierOfferId.value) {
        await api.updateSupplierProductOffer(productId, supplierOfferId.value, offerPayload)
      } else {
        await api.createSupplierProductOffer(productId, offerPayload)
      }
    }
    ElMessage.success(editingId.value ? '商品、包装和供货资料已更新' : '商品已创建，可直接用于采购、销售和库存业务')
    closeDialog()
    await queryClient.invalidateQueries({ queryKey: ['products'] })
  } catch (error) {
    const prefix = productSaved ? '商品基础资料已保存，但关联包装或供货资料保存失败：' : ''
    ElMessage.error(`${prefix}${mutationErrorMessage(error)}`)
    if (productSaved && productId) {
      editingId.value = productId
      await queryClient.invalidateQueries({ queryKey: ['products'] })
    }
  }
}

function firstMissingRequiredField() {
  return seriesFields.value.find((field) => {
    if (!field.required || !isMissingFieldValue(form.attributeValues[field.id])) return false
    if (!editingId.value || originalSeriesId.value !== form.productSeriesId) return true
    return !isMissingFieldValue(originalAttributeValues.value[field.id])
  })
}

function serializeProductFields() {
  syncLegacyReferenceFields()
  const activeAttributes: Record<string, string | number> = {}
  const fieldValues = seriesFields.value.map((field) => {
    const value = form.attributeValues[field.id]
    const normalizedValue = isMissingFieldValue(value) ? null : value as string | number
    if (normalizedValue !== null) activeAttributes[field.fieldName] = normalizedValue
    return { seriesFieldId: field.id, value: normalizedValue }
  })
  return { attributeJson: JSON.stringify(activeAttributes), fieldValues }
}

function mutationErrorMessage(error: unknown) {
  const responseMessage = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  return responseMessage || (error instanceof Error ? error.message : '商品保存失败')
}

function isMissingFieldValue(value: unknown) {
  return value === undefined || value === null || value === ''
}

function todayString() {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date())
}

function resetFilters() {
  filters.keyword = ''
  filters.categoryId = undefined
  filters.productSeriesId = undefined
  filters.status = ''
  filters.completeness = ''
  Object.keys(attributeFilters).forEach((key) => delete attributeFilters[Number(key)])
}
</script>

<style scoped>
.product-header-actions {
  display: flex;
  gap: 8px;
}

.product-table-actions {
  display: inline-flex;
  align-items: center;
  gap: 0;
}

.product-create-steps {
  padding: 4px 20px 20px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.product-create-form {
  min-height: 420px;
  padding-top: 18px;
}

.product-step-panel {
  min-height: 390px;
}

.product-step-heading {
  display: flex;
  gap: 10px;
  align-items: baseline;
  margin-bottom: 16px;
}

.product-step-heading strong {
  font-size: var(--app-title-font-size);
}

.product-step-heading span {
  color: var(--el-text-color-secondary);
  font-size: var(--app-small-font-size);
}

.duplicate-alert {
  margin-bottom: 14px;
}

.identity-field-tag {
  margin-left: 5px;
  vertical-align: 1px;
}

.related-master-form {
  padding-top: 18px;
}

.related-master-list {
  margin-bottom: 8px;
}

.product-step-panel :deep(.el-select),
.product-step-panel :deep(.el-date-editor) {
  width: 100%;
}

.preset-select-empty {
  display: flex;
  min-width: 260px;
  padding: 8px 12px;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  color: var(--el-text-color-secondary);
}

.product-field-help {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: var(--app-small-font-size);
  line-height: 1.5;
}

.series-packaging-binding-card {
  margin-bottom: 14px;
  padding: 14px 14px 2px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-lighter);
}

.series-packaging-binding-action {
  display: flex;
  min-height: 40px;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
}

@media (max-width: 900px) {
  .product-header-actions {
    flex-wrap: wrap;
    justify-content: flex-end;
  }

  .product-step-heading {
    flex-direction: column;
    gap: 4px;
  }
}
</style>
