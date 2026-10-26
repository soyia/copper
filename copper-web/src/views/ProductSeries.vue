<template>
  <div class="page" :class="{ 'catalog-page': section === 'catalog', 'standards-page': section === 'standards' }">
    <div v-if="section === 'catalog'" class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">产品分类</div>
          <div class="muted">选择分类后，右侧显示对应产品系列。</div>
        </div>
        <el-button type="primary" @click="openCategoryCreate">新增分类</el-button>
      </div>
      <div class="list-tools">
        <el-input v-model="masterFilters.categoryKeyword" clearable placeholder="分类名称 / 编号" />
      </div>
      <el-table
        :key="masterFilters.categoryKeyword"
        :data="filteredCategoryTree"
        class="data-table"
        size="small"
        border
        show-overflow-tooltip
        row-key="id"
        default-expand-all
        :tree-props="{ children: 'children' }"
        :row-class-name="categoryRowClassName"
        max-height="calc(100vh - 310px)"
        @row-click="selectCategory"
      >
        <el-table-column prop="name" label="分类名称" min-width="190" fixed="left" />
        <el-table-column label="编号" width="80">
          <template #default="{ row }">{{ row.isAllCategories ? '—' : row.id }}</template>
        </el-table-column>
        <el-table-column label="本级" width="70" align="center">
          <template #default="{ row }">{{ row.isAllCategories ? '—' : categorySeriesCount(row.id, false) }}</template>
        </el-table-column>
        <el-table-column label="含下级" width="80" align="center">
          <template #default="{ row }">{{ row.isAllCategories ? (series?.length ?? 0) : categorySeriesCount(row.id, true) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button v-if="!row.isAllCategories" text type="primary" @click.stop="openCategoryEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="section === 'standards'" class="panel">
      <div class="panel-header">
        <div class="panel-title">执行标准</div>
        <el-button v-if="!legacyDictionaryReadOnly" type="primary" @click="openStandardCreate">新增标准</el-button>
      </div>
      <el-alert
        v-if="legacyDictionaryReadOnly"
        type="info"
        :closable="false"
        title="该旧入口已退出日常使用；商品建档可直接录入厂家牌号、状态和执行标准。"
        style="margin-bottom: 12px"
      />
      <div class="list-tools">
        <el-input v-model="masterFilters.standardKeyword" clearable placeholder="标准号 / 中英文名称 / 状态" />
      </div>
      <el-table :data="filteredProductStandards" class="data-table standards-table" size="small" border show-overflow-tooltip>
        <el-table-column prop="code" label="标准号" width="150" fixed="left" />
        <el-table-column prop="name" label="中文标准名称" min-width="160" />
        <el-table-column prop="englishName" label="英文标准名称" min-width="240" />
        <el-table-column prop="standardStatus" label="标准状态" width="100" />
        <el-table-column prop="ccs" label="CCS" width="90" />
        <el-table-column prop="ics" label="ICS" width="110" />
        <el-table-column prop="publishDate" label="发布日期" width="120" />
        <el-table-column prop="implementationDate" label="实施日期" width="120" />
        <el-table-column prop="abolitionDate" label="废止日期" width="120" />
        <el-table-column prop="competentDepartment" label="主管部门" width="120" />
        <el-table-column prop="responsibleDepartment" label="归口部门" width="120" />
        <el-table-column prop="publisher" label="发布单位" min-width="260" />
        <el-table-column prop="memo" label="备注" min-width="220" />
        <el-table-column prop="sourceUrl" label="对应网址" min-width="260" />
        <el-table-column label="启用状态" width="100">
          <template #default="{ row }">{{ statusName(row.status) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <span v-if="legacyDictionaryReadOnly" class="muted">只读</span>
            <el-button v-else text type="primary" @click="openStandardEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="section === 'tempers'" class="panel">
      <div class="panel-header">
        <div class="panel-title">产品状态/硬度</div>
        <el-button v-if="!legacyDictionaryReadOnly" type="primary" @click="openTemperCreate">新增状态</el-button>
      </div>
      <el-alert
        v-if="legacyDictionaryReadOnly"
        type="info"
        :closable="false"
        title="该旧入口已退出日常使用；商品建档可直接录入厂家牌号、状态和执行标准。"
        style="margin-bottom: 12px"
      />
      <el-table :data="productTempers ?? []" class="data-table" size="small" border show-overflow-tooltip max-height="260">
        <el-table-column prop="code" label="编码" width="120" fixed="left" />
        <el-table-column prop="name" label="状态名称" width="160" />
        <el-table-column prop="englishName" label="英文名称" min-width="150" />
        <el-table-column label="启用状态" width="100">
          <template #default="{ row }">{{ statusName(row.status) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <span v-if="legacyDictionaryReadOnly" class="muted">只读</span>
            <el-button v-else text type="primary" @click="openTemperEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="section === 'catalog' && catalogView === 'series'" class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">
            产品系列
            <el-tag v-if="selectedCategory" effect="plain" size="small">{{ selectedCategory.name }}</el-tag>
          </div>
          <div class="muted">
            {{ selectedCategory ? `新增系列将直接归属“${selectedCategory.name}”本级` : '当前显示全部分类；点击左侧分类可联动筛选' }}
          </div>
        </div>
        <el-button type="primary" @click="openSeriesCreate">{{ selectedCategory ? '新增本级系列' : '新增系列' }}</el-button>
      </div>
      <div class="list-tools">
        <el-input v-model="filters.keyword" clearable placeholder="系列编码 / 名称 / 别名" />
        <el-checkbox v-if="selectedCategory" v-model="includeChildCategories">包含下级分类</el-checkbox>
        <el-select v-model="filters.status" clearable placeholder="状态">
          <el-option label="启用" value="ENABLED" />
          <el-option label="不可用" value="DISABLED" />
        </el-select>
        <div class="list-tools-actions">
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </div>
      <el-table
        :data="filteredSeries"
        class="data-table"
        size="small"
        border
        show-overflow-tooltip
        :row-class-name="seriesRowClassName"
        max-height="calc(100vh - 310px)"
      >
        <el-table-column prop="name" label="系列名称" width="180" fixed="left" />
        <el-table-column prop="code" label="系列编码" width="120">
          <template #default="{ row }">{{ row.code || '待维护' }}</template>
        </el-table-column>
        <el-table-column prop="id" label="编号" width="80" />
        <el-table-column prop="categoryName" label="产品分类" min-width="120" />
        <el-table-column prop="aliasNames" label="别名" min-width="220" />
        <el-table-column label="包装单位分类" width="150">
          <template #default="{ row }">{{ unitCategoryName(row.packageUnitCategoryId) }}</template>
        </el-table-column>
        <el-table-column label="计价单位分类" width="150">
          <template #default="{ row }">{{ unitCategoryName(row.priceUnitCategoryId) }}</template>
        </el-table-column>
        <el-table-column label="计量模板" min-width="180">
          <template #default="{ row }">
            <template v-if="seriesPackagingBindingMap.get(row.id)?.templateId">
              {{ seriesPackagingBindingMap.get(row.id)?.templateName }}
              <el-tag size="small" effect="plain">v{{ seriesPackagingBindingMap.get(row.id)?.templateRevisionNo }}</el-tag>
            </template>
            <el-tag v-else type="warning" size="small">未应用</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <div class="series-row-actions">
              <el-button text type="primary" @click="openSeriesEdit(row)">编辑</el-button>
              <el-button text type="primary" @click="selectSeriesForSpecs(row)">规格</el-button>
              <el-button
                text
                type="primary"
                :disabled="!seriesPackagingBindingMap.get(row.id)"
                @click="openSeriesPackagingBinding(row)"
              >
                计量
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="section === 'catalog' && catalogView === 'specs'" class="panel series-config-panel">
      <div class="panel-header">
        <div>
          <el-button text type="primary" class="series-config-back" @click="returnToSeriesList">← 返回系列列表</el-button>
          <div class="panel-title">
            {{ activeSeries ? `${activeSeriesPath}的规格配置` : '系列规格配置' }}
          </div>
          <div class="muted">配置商品建档字段和常用规格预设；保存后立即用于该系列的新建、编辑和重复检查。</div>
        </div>
        <div class="series-context-control">
          <span>当前系列</span>
          <el-select v-model="activeSeriesId" filterable placeholder="选择产品系列" @change="changeActiveSeries">
            <el-option
              v-for="item in series ?? []"
              :key="item.id"
              :label="`${item.categoryName || '未分类'} / ${item.name}`"
              :value="item.id"
            />
          </el-select>
        </div>
      </div>
      <el-tabs v-model="seriesConfigTab" class="series-config-tabs">
        <el-tab-pane name="fields">
          <template #label>建档字段（{{ seriesFields.length }}）</template>
          <div class="tab-toolbar">
            <span class="muted">勾选“用于区分商品”后自动设为必填，并参与同系列重复检查。</span>
            <div>
              <el-button @click="navigateToFieldLibrary">维护字段库</el-button>
              <el-button type="primary" :disabled="!activeSeriesId" @click="openFieldCreate">新增字段</el-button>
            </div>
          </div>
          <el-table
            :data="seriesFields"
            class="data-table"
            size="small"
            border
            show-overflow-tooltip
            :empty-text="activeSeries ? '当前系列还没有配置商品建档字段' : '请先选择产品系列'"
            max-height="calc(100vh - 390px)"
          >
            <el-table-column prop="displayName" label="显示名" width="140" fixed="left" />
            <el-table-column prop="fieldName" label="字段" width="120" />
            <el-table-column prop="fieldType" label="类型" width="100" />
            <el-table-column prop="optionJson" label="选项" min-width="240" />
            <el-table-column prop="unitName" label="单位" width="90" />
            <el-table-column label="必填" width="90">
              <template #default="{ row }"><el-tag :type="row.required ? 'danger' : 'info'" size="small">{{ row.required ? '必填' : '选填' }}</el-tag></template>
            </el-table-column>
            <el-table-column label="业务用途" min-width="240">
              <template #default="{ row }">
                <div class="field-purpose-tags">
                  <el-tag v-if="row.skuDimension" type="danger" size="small">区分商品</el-tag>
                  <el-tag v-if="row.includeInSpec" type="success" size="small">规格文本</el-tag>
                  <el-tag v-if="row.searchable" size="small">列表筛选</el-tag>
                  <el-tag v-if="row.orderVisible" type="warning" size="small">业务单据</el-tag>
                  <span v-if="!seriesFieldPurposeTags(row).length" class="muted">仅存档</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="已用数据" width="120" align="center">
              <template #default="{ row }">商品 {{ row.productCount }} / 预设 {{ row.presetCount }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">{{ statusName(row.status) }}</template>
            </el-table-column>
            <el-table-column prop="sortNo" label="排序" width="90" />
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button text type="primary" @click="openFieldEdit(row)">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane name="presets">
          <template #label>规格预设（{{ specPresets.length }}）</template>
          <div class="tab-toolbar">
            <span class="muted">维护常用规格组合；商品建档选择预设后自动带入字段默认值。</span>
            <el-button type="primary" :disabled="!activeSeriesId" @click="openPresetCreate">新增预设</el-button>
          </div>
          <el-table
            :data="specPresets"
            class="data-table"
            size="small"
            border
            show-overflow-tooltip
            :empty-text="activeSeries ? '当前系列还没有维护规格预设' : '请先选择产品系列'"
            max-height="calc(100vh - 390px)"
          >
            <el-table-column prop="name" label="品名" width="120" fixed="left" />
            <el-table-column prop="model" label="型号" width="160" fixed="left" />
            <el-table-column prop="specText" label="规格文本" min-width="220" />
            <el-table-column prop="skuSuffix" label="SKU后缀" min-width="140" />
            <el-table-column label="字段默认值" min-width="260">
              <template #default="{ row }">{{ attributeSummary(row) }}</template>
            </el-table-column>
            <el-table-column prop="sortNo" label="排序" width="90" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">{{ statusName(row.status) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button text type="primary" @click="openPresetEdit(row)">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-dialog v-model="categoryDialogVisible" :title="categoryDialogTitle" width="460px">
      <el-form ref="categoryFormRef" :model="categoryForm" :rules="categoryRules" label-width="90px">
        <el-form-item label="上级分类">
          <el-tree-select
            v-model="categoryForm.parentId"
            :data="categoryParentOptions"
            filterable
            default-expand-all
            check-strictly
            :render-after-expand="false"
            placeholder="选择上级分类"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="分类名称" prop="name"><el-input v-model="categoryForm.name" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="categoryForm.sortNo" :min="0" :precision="0" style="width: 100%" :controls="false" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="categoryForm.status">
            <el-option label="启用" value="ENABLED" />
            <el-option label="不可用" value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeCategoryDialog">取消</el-button>
        <el-button type="primary" @click="saveCategory">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="standardDialogVisible" :title="standardDialogTitle" width="860px">
      <el-form ref="standardFormRef" :model="standardForm" :rules="standardRules" label-width="120px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="标准号" prop="code"><el-input v-model="standardForm.code" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="中文标准名称" prop="name"><el-input v-model="standardForm.name" /></el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="英文标准名称"><el-input v-model="standardForm.englishName" /></el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="标准状态">
              <el-select v-model="standardForm.standardStatus" filterable allow-create default-first-option>
                <el-option label="现行" value="现行" />
                <el-option label="废止" value="废止" />
                <el-option label="即将实施" value="即将实施" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="CCS"><el-input v-model="standardForm.ccs" /></el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="ICS"><el-input v-model="standardForm.ics" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="发布日期">
              <el-date-picker v-model="standardForm.publishDate" type="date" value-format="YYYY-MM-DD" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="实施日期">
              <el-date-picker v-model="standardForm.implementationDate" type="date" value-format="YYYY-MM-DD" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="废止日期">
              <el-date-picker v-model="standardForm.abolitionDate" type="date" value-format="YYYY-MM-DD" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="主管部门"><el-input v-model="standardForm.competentDepartment" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="归口部门"><el-input v-model="standardForm.responsibleDepartment" /></el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="发布单位"><el-input v-model="standardForm.publisher" /></el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="备注"><el-input v-model="standardForm.memo" type="textarea" :rows="2" /></el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="对应网址"><el-input v-model="standardForm.sourceUrl" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="启用状态">
              <el-select v-model="standardForm.status">
                <el-option label="启用" value="ENABLED" />
                <el-option label="不可用" value="DISABLED" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="closeStandardDialog">取消</el-button>
        <el-button type="primary" @click="saveStandard">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="temperDialogVisible" :title="temperDialogTitle" width="460px">
      <el-form ref="temperFormRef" :model="temperForm" :rules="temperRules" label-width="90px">
        <el-form-item label="状态名称" prop="name"><el-input v-model="temperForm.name" /></el-form-item>
        <el-form-item label="英文名称"><el-input v-model="temperForm.englishName" /></el-form-item>
        <el-form-item label="编码"><el-input v-model="temperForm.code" /></el-form-item>
        <el-form-item label="启用状态">
          <el-select v-model="temperForm.status">
            <el-option label="启用" value="ENABLED" />
            <el-option label="不可用" value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeTemperDialog">取消</el-button>
        <el-button type="primary" @click="saveTemper">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="seriesDialogVisible" :title="seriesDialogTitle" width="520px">
      <el-form ref="seriesFormRef" :model="seriesForm" :rules="seriesRules" label-width="110px">
        <el-form-item label="产品分类" prop="categoryId">
          <el-select v-model="seriesForm.categoryId" clearable filterable>
            <el-option v-for="item in productCategories ?? []" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="系列编码" prop="code">
          <el-input
            v-model="seriesForm.code"
            maxlength="32"
            placeholder="如 ZTXPG"
            @input="seriesForm.code = String($event || '').toUpperCase()"
          />
        </el-form-item>
        <el-form-item label="系列名称" prop="name"><el-input v-model="seriesForm.name" /></el-form-item>
        <el-form-item label="别名"><el-input v-model="seriesForm.aliasNames" placeholder="多个别名用逗号分隔" /></el-form-item>
        <el-form-item label="包装单位分类">
          <el-select v-model="seriesForm.packageUnitCategoryId" clearable>
            <el-option v-for="item in unitCategories ?? []" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="计价单位分类">
          <el-select v-model="seriesForm.priceUnitCategoryId" clearable>
            <el-option v-for="item in unitCategories ?? []" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="seriesForm.status">
            <el-option label="启用" value="ENABLED" />
            <el-option label="不可用" value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeSeriesDialog">取消</el-button>
        <el-button type="primary" @click="saveSeries">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="fieldDialogVisible" :title="fieldDialogTitle" width="780px" @closed="resetFieldForm">
      <el-alert
        v-if="activeSeries"
        type="info"
        :closable="false"
        :title="`适用产品系列：${activeSeriesPath}`"
        style="margin-bottom: 16px"
      />
      <el-form :model="fieldForm" label-width="110px">
        <el-form-item label="字段定义" required>
          <el-select
            v-model="fieldForm.fieldId"
            filterable
            placeholder="外径 / 壁厚 / 品名 / 型号"
            style="width: 100%"
            @change="handleFieldDefinitionChange"
          >
            <el-option v-for="item in eligibleCustomFields" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="fieldForm.displayName" placeholder="留空时使用字段名称" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="计量分类">
              <el-select v-model="fieldForm.unitCategoryId" clearable filterable style="width: 100%" @change="handleFieldUnitCategoryChange">
                <el-option v-for="item in unitCategories ?? []" :key="item.id" :label="item.name" :value="item.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="计量单位" :required="!!fieldForm.unitCategoryId">
              <el-select v-model="fieldForm.unitId" clearable filterable :disabled="!fieldForm.unitCategoryId" style="width: 100%">
                <el-option v-for="item in fieldUnitOptions" :key="item.id" :label="`${item.name}（${item.abbreviation || item.name}）`" :value="item.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item v-if="selectedFieldDefinition?.fieldType === 'SELECT'" label="本系列选项" required>
          <div class="field-option-editor">
            <div v-for="option in seriesFieldOptions" :key="option.id" class="field-option-row">
              <el-input v-model="option.value" placeholder="输入当前系列可选值" />
              <el-button text type="danger" @click="removeSeriesFieldOption(option.id)">删除</el-button>
            </div>
            <div class="field-option-actions">
              <el-button text type="primary" @click="addSeriesFieldOption">添加选项</el-button>
              <span class="muted">只影响当前产品系列，不修改其他系列。</span>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="必填">
          <el-switch v-model="fieldForm.required" />
        </el-form-item>
        <el-form-item label="业务用途">
          <el-checkbox v-model="fieldForm.skuDimension">用于区分商品</el-checkbox>
          <el-checkbox v-model="fieldForm.includeInSpec">生成规格文本</el-checkbox>
          <el-checkbox v-model="fieldForm.searchable">支持列表筛选</el-checkbox>
          <el-checkbox v-model="fieldForm.orderVisible">采购销售显示</el-checkbox>
          <div class="muted" style="width: 100%">勾选后自动设为必填并参与重复检查，无需维护或发布另一套规则。</div>
        </el-form-item>
        <el-row v-if="selectedFieldDefinition?.fieldType === 'NUMBER'" :gutter="12">
          <el-col :span="8">
            <el-form-item label="最小值"><el-input-number v-model="fieldForm.minValue" :controls="false" style="width: 100%" /></el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="最大值"><el-input-number v-model="fieldForm.maxValue" :controls="false" style="width: 100%" /></el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="小数位">
              <el-input-number v-model="fieldForm.decimalScale" :min="0" :max="10" :precision="0" style="width: 100%" :controls="false" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="排序">
          <el-input-number v-model="fieldForm.sortNo" :min="0" :precision="0" style="width: 100%" :controls="false" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="fieldForm.status" style="width: 100%">
            <el-option label="启用" value="ENABLED" />
            <el-option label="不可用" value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeFieldDialog">取消</el-button>
        <el-button type="primary" @click="saveField">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="presetDialogVisible" :title="presetDialogTitle" width="720px" @closed="resetPresetForm">
      <el-alert
        v-if="activeSeries"
        type="info"
        :closable="false"
        :title="`适用产品系列：${activeSeriesPath}。下列字段由系列模板自动带入，只需填写需要预设的默认值。`"
        style="margin-bottom: 16px"
      />
      <el-alert
        v-if="presetHistoricalValues.length"
        type="warning"
        :closable="false"
        :title="`存在 ${presetHistoricalValues.length} 项历史字段值；保存时继续保留，不会删除，也不会自动带入新商品。`"
        style="margin-bottom: 16px"
      />
      <el-form ref="presetFormRef" :model="presetForm" :rules="presetRules" label-width="90px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="品名" prop="name"><el-input v-model="presetForm.name" placeholder="填写预设品名" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="型号" prop="model"><el-input v-model="presetForm.model" placeholder="填写预设型号" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="规格文本"><el-input v-model="presetForm.specText" placeholder="留空自动拼接" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="SKU后缀"><el-input v-model="presetForm.skuSuffix" placeholder="BLK-6-9-180" /></el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="字段默认值">
              <div class="preset-field-default-list">
                <el-table
                  :data="presetSeriesFields"
                  class="preset-field-default-table"
                  size="small"
                  border
                  empty-text="当前系列还没有启用的商品字段"
                >
                  <el-table-column label="字段" width="180">
                    <template #default="{ row: field }">
                      <div class="preset-field-name">{{ field.displayName || field.fieldName }}</div>
                      <div v-if="presetFieldMeta(field)" class="muted">{{ presetFieldMeta(field) }}</div>
                    </template>
                  </el-table-column>
                  <el-table-column label="商品建档要求" width="130" align="center">
                    <template #default="{ row: field }">
                      <el-tag :type="field.required ? 'danger' : 'info'" size="small">
                        {{ field.required ? '必填' : '选填' }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="预设默认值" min-width="280">
                    <template #default="{ row: field }">
                      <el-select
                        v-if="fieldOptions(field).length"
                        :model-value="presetFieldValues[field.id]"
                        clearable
                        filterable
                        placeholder="选择默认值"
                        @update:model-value="setPresetFieldValue(field.id, $event)"
                      >
                        <el-option
                          v-for="option in fieldOptions(field)"
                          :key="option"
                          :label="option"
                          :value="option"
                        />
                      </el-select>
                      <el-input-number
                        v-else-if="field.fieldType === 'NUMBER'"
                        :model-value="numericPresetFieldValue(field.id)"
                        :precision="field.decimalScale"
                        :min="field.minValue ?? undefined"
                        :max="field.maxValue ?? undefined"
                        clearable
                        placeholder="输入默认值"
                        style="width: 100%"
                        @update:model-value="setPresetFieldValue(field.id, $event)"
                      :controls="false" />
                      <el-input
                        v-else
                        :model-value="textPresetFieldValue(field.id)"
                        clearable
                        placeholder="输入默认值"
                        @update:model-value="setPresetFieldValue(field.id, $event)"
                      />
                    </template>
                  </el-table-column>
                </el-table>
                <div v-if="presetSeriesFields.length" class="muted">预设默认值可以留空；“商品建档要求”仅在新增或编辑商品时校验。</div>
                <el-collapse v-if="presetHistoricalValues.length" class="history-field-collapse">
                  <el-collapse-item :title="`历史字段（${presetHistoricalValues.length}）`" name="history-fields">
                    <el-table :data="presetHistoricalValues" size="small" border>
                      <el-table-column prop="label" label="历史字段" min-width="160" />
                      <el-table-column prop="value" label="保留值" min-width="220" />
                      <el-table-column prop="reason" label="说明" min-width="160" />
                    </el-table>
                  </el-collapse-item>
                </el-collapse>
              </div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="排序"><el-input-number v-model="presetForm.sortNo" :min="0" :precision="0" style="width: 100%" :controls="false" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态">
              <el-select v-model="presetForm.status" style="width: 100%">
                <el-option label="启用" value="ENABLED" />
                <el-option label="不可用" value="DISABLED" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="closePresetDialog">取消</el-button>
        <el-button type="primary" @click="savePreset">保存</el-button>
      </template>
    </el-dialog>

    <SeriesPackagingBindingDialog
      v-model="seriesPackagingDialogVisible"
      :binding="activeSeriesPackagingBinding"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  api,
  type ProductCategory,
  type ProductSeries,
  type ProductStandard,
  type ProductTemper,
  type SeriesPackagingBinding,
  type SeriesField,
  type SpecPreset,
} from '@/api/client'
import { statusName } from '@/api/format'
import { matchesKeyword } from '@/utils/listFilters'
import SeriesPackagingBindingDialog from '@/components/SeriesPackagingBindingDialog.vue'

const props = withDefaults(defineProps<{
  section?: 'catalog' | 'specs' | 'standards' | 'tempers'
}>(), {
  section: 'catalog',
})
const section = computed(() => props.section)
const legacyDictionaryReadOnly = computed(() =>
  section.value === 'standards' || section.value === 'tempers')

const router = useRouter()
const route = useRoute()
const queryClient = useQueryClient()
const catalogView = ref<'series' | 'specs'>(route.query.view === 'specs' ? 'specs' : 'series')
const seriesConfigTab = ref<'fields' | 'presets'>(route.query.tab === 'presets' ? 'presets' : 'fields')
const filters = reactive({ keyword: '', categoryId: undefined as number | undefined, status: '' })
const masterFilters = reactive({ categoryKeyword: '', standardKeyword: '' })
const includeChildCategories = ref(true)

const categoryDialogVisible = ref(false)
const standardDialogVisible = ref(false)
const temperDialogVisible = ref(false)
const seriesDialogVisible = ref(false)
const fieldDialogVisible = ref(false)
const presetDialogVisible = ref(false)
const seriesPackagingDialogVisible = ref(false)
const activeSeriesPackagingBinding = ref<SeriesPackagingBinding>()
const categoryFormRef = ref<FormInstance>()
const standardFormRef = ref<FormInstance>()
const temperFormRef = ref<FormInstance>()
const seriesFormRef = ref<FormInstance>()
const presetFormRef = ref<FormInstance>()
const editingCategoryId = ref<number>()
const editingStandardId = ref<number>()
const editingTemperId = ref<number>()
const editingSeriesId = ref<number>()
const editingFieldId = ref<number>()
const editingPresetId = ref<number>()

const activeSeriesId = ref<number>()
const seriesFields = ref<SeriesField[]>([])
const specPresets = ref<SpecPreset[]>([])
type PresetDefaultValue = string | number | null | undefined
type HistoricalFieldValue = { key: string; label: string; value: string; reason: string }
type SeriesFieldOption = { id: number; value: string }
let seriesFieldOptionId = 0
const createSeriesFieldOption = (value = ''): SeriesFieldOption => ({ id: ++seriesFieldOptionId, value })
const seriesFieldOptions = ref<SeriesFieldOption[]>([createSeriesFieldOption()])
const presetFieldValues = ref<Record<number, PresetDefaultValue>>({})
const presetHistoricalValues = ref<HistoricalFieldValue[]>([])

const categoryForm = reactive<Partial<ProductCategory>>({ parentId: 0, name: '', sortNo: 0, status: 'ENABLED' })
const standardForm = reactive<Partial<ProductStandard>>({
  code: '',
  name: '',
  englishName: '',
  standardStatus: '现行',
  ccs: '',
  ics: '',
  publishDate: null,
  implementationDate: null,
  abolitionDate: null,
  competentDepartment: '',
  responsibleDepartment: '',
  publisher: '',
  memo: '',
  sourceUrl: '',
  status: 'ENABLED',
})
const temperForm = reactive<Partial<ProductTemper>>({ name: '', code: '', englishName: '', status: 'ENABLED' })
const seriesForm = reactive<Partial<ProductSeries>>({ categoryId: undefined, code: '', name: '', aliasNames: '', packageUnitCategoryId: undefined, priceUnitCategoryId: undefined, status: 'ENABLED' })
const fieldForm = reactive<Partial<SeriesField>>({
  fieldId: undefined,
  displayName: '',
  optionJson: '[]',
  unitCategoryId: undefined,
  unitId: undefined,
  unitName: '',
  required: true,
  skuDimension: true,
  includeInSpec: true,
  searchable: true,
  orderVisible: true,
  minValue: undefined,
  maxValue: undefined,
  decimalScale: 3,
  sortNo: 10,
  status: 'ENABLED',
})
const presetForm = reactive<Partial<SpecPreset>>({ name: '', model: '', attributeJson: '', specText: '', skuSuffix: '', sortNo: 10, status: 'ENABLED' })

const { data: series } = useQuery({ queryKey: ['product-series'], queryFn: api.productSeries })
const { data: productCategories } = useQuery({ queryKey: ['product-categories'], queryFn: api.productCategories })
const { data: productStandards } = useQuery({ queryKey: ['product-standards'], queryFn: api.productStandards })
const { data: productTempers } = useQuery({ queryKey: ['product-tempers'], queryFn: api.productTempers })
const { data: unitCategories } = useQuery({ queryKey: ['unit-categories'], queryFn: api.unitCategories })
const { data: units } = useQuery({ queryKey: ['units'], queryFn: api.units })
const { data: customFields } = useQuery({ queryKey: ['custom-fields'], queryFn: api.customFields })
const { data: seriesPackagingBindings } = useQuery({
  queryKey: ['series-packaging-bindings'],
  queryFn: api.seriesPackagingBindings,
})
const seriesPackagingBindingMap = computed(() =>
  new Map((seriesPackagingBindings.value ?? []).map((item) => [item.productSeriesId, item])),
)
const activeSeries = computed(() => (series.value ?? []).find((item) => item.id === activeSeriesId.value))
const presetSeriesFields = computed(() => seriesFields.value.filter((field) => field.status === 'ENABLED'))
const activeSeriesPath = computed(() =>
  activeSeries.value ? `${activeSeries.value.categoryName || '未分类'} / ${activeSeries.value.name}` : '',
)
const eligibleCustomFields = computed(() =>
  (customFields.value ?? []).filter((item) => item.status === 'ENABLED' || item.id === fieldForm.fieldId),
)
const selectedFieldDefinition = computed(() =>
  (customFields.value ?? []).find((item) => item.id === fieldForm.fieldId),
)
const fieldUnitOptions = computed(() =>
  (units.value ?? []).filter((item) => item.status === 'ENABLED' && item.unitCategoryId === fieldForm.unitCategoryId),
)
const unitCategoryNames = computed(() => new Map((unitCategories.value ?? []).map((item) => [item.id, item.name])))

watch(
  () => fieldForm.skuDimension,
  (enabled) => {
    if (!enabled) return
    fieldForm.required = true
    fieldForm.includeInSpec = true
    fieldForm.searchable = true
    fieldForm.orderVisible = true
  },
)

function unitCategoryName(id?: number) {
  if (!id) return '—'
  return unitCategoryNames.value.get(id) || `未知分类（${id}）`
}

function seriesFieldPurposeTags(row: SeriesField) {
  return [row.skuDimension, row.includeInSpec, row.searchable, row.orderVisible].filter(Boolean)
}

type ProductCategoryTree = ProductCategory & {
  children?: ProductCategoryTree[]
  isAllCategories?: boolean
}
type CategoryParentOption = {
  value: number
  label: string
  disabled?: boolean
  children?: CategoryParentOption[]
}

const productCategoryTree = computed<ProductCategoryTree[]>(() => {
  const rows = productCategories.value ?? []
  const nodeMap = new Map<number, ProductCategoryTree>()
  const roots: ProductCategoryTree[] = []

  rows.forEach((item) => {
    nodeMap.set(item.id, { ...item, children: [] })
  })

  nodeMap.forEach((node) => {
    const parentId = node.parentId || 0
    const parent = parentId ? nodeMap.get(parentId) : undefined
    if (parent && parent.id !== node.id) {
      parent.children?.push(node)
    } else {
      roots.push(node)
    }
  })

  const sortNodes = (nodes: ProductCategoryTree[]) => {
    nodes.sort((a, b) => a.sortNo - b.sortNo || a.id - b.id)
    nodes.forEach((node) => {
      if (node.children?.length) {
        sortNodes(node.children)
      } else {
        delete node.children
      }
    })
  }
  sortNodes(roots)
  return roots
})

function filterCategoryTree(nodes: ProductCategoryTree[], keyword: string): ProductCategoryTree[] {
  if (!keyword.trim()) return nodes
  return nodes.flatMap((node) => {
    const children = node.children ? filterCategoryTree(node.children, keyword) : []
    if (matchesKeyword(node, keyword, ['id', 'name'])) {
      return [node]
    }
    if (children.length) {
      return [{ ...node, children }]
    }
    return []
  })
}

const filteredCategoryTree = computed<ProductCategoryTree[]>(() => {
  const keyword = masterFilters.categoryKeyword.trim()
  const children = keyword === '全部分类'
    ? productCategoryTree.value
    : filterCategoryTree(productCategoryTree.value, keyword)
  return [{
    id: 0,
    parentId: 0,
    name: '全部分类',
    sortNo: -1,
    status: 'ENABLED',
    isAllCategories: true,
    children,
  }]
})

const selectedCategory = computed(() =>
  (productCategories.value ?? []).find((item) => item.id === filters.categoryId),
)

const categoryChildMap = computed(() => {
  const childMap = new Map<number, number[]>()
  ;(productCategories.value ?? []).forEach((item) => {
    const parentId = item.parentId || 0
    childMap.set(parentId, [...(childMap.get(parentId) ?? []), item.id])
  })
  return childMap
})

function categoryIdsFor(categoryId: number, includeChildren: boolean) {
  const categoryIds = new Set<number>([categoryId])
  if (!includeChildren) return categoryIds

  const collectChildren = (parentId: number) => {
    ;(categoryChildMap.value.get(parentId) ?? []).forEach((childId) => {
      if (categoryIds.has(childId)) return
      categoryIds.add(childId)
      collectChildren(childId)
    })
  }
  collectChildren(categoryId)
  return categoryIds
}

const visibleCategoryIds = computed(() =>
  filters.categoryId ? categoryIdsFor(filters.categoryId, includeChildCategories.value) : undefined,
)

function categorySeriesCount(categoryId: number, includeChildren: boolean) {
  const categoryIds = categoryIdsFor(categoryId, includeChildren)
  return (series.value ?? []).filter((item) => categoryIds.has(item.categoryId)).length
}

function selectCategory(row: ProductCategoryTree) {
  if (row.isAllCategories) {
    clearSelectedCategory()
  } else {
    filters.categoryId = row.id
    includeChildCategories.value = true
  }
  if (catalogView.value === 'specs') {
    catalogView.value = 'series'
    void router.replace('/product-catalog')
  }
}

function clearSelectedCategory() {
  filters.categoryId = undefined
  includeChildCategories.value = true
}

function categoryRowClassName({ row }: { row: ProductCategoryTree }) {
  return [
    (row.isAllCategories && !filters.categoryId) || row.id === filters.categoryId ? 'category-row-selected' : '',
    row.status === 'DISABLED' ? 'master-data-row-disabled' : '',
  ].filter(Boolean).join(' ')
}

function seriesRowClassName({ row }: { row: ProductSeries }) {
  return [
    row.id === activeSeriesId.value ? 'category-row-selected' : '',
    row.status === 'DISABLED' ? 'master-data-row-disabled' : '',
  ].filter(Boolean).join(' ')
}

const blockedParentCategoryIds = computed(() => {
  const blockedIds = new Set<number>()
  const categoryId = editingCategoryId.value
  if (!categoryId) return blockedIds

  const childMap = new Map<number, number[]>()
  ;(productCategories.value ?? []).forEach((item) => {
    const parentId = item.parentId || 0
    childMap.set(parentId, [...(childMap.get(parentId) ?? []), item.id])
  })

  const collect = (id: number) => {
    blockedIds.add(id)
    ;(childMap.get(id) ?? []).forEach(collect)
  }
  collect(categoryId)
  return blockedIds
})

const categoryParentOptions = computed<CategoryParentOption[]>(() => {
  const toOption = (item: ProductCategoryTree): CategoryParentOption => {
    const option: CategoryParentOption = {
      value: item.id,
      label: `${item.name}（${item.id}）`,
      disabled: blockedParentCategoryIds.value.has(item.id),
    }
    if (item.children?.length) {
      option.children = item.children.map(toOption)
    }
    return option
  }

  return [{ value: 0, label: '无上级分类' }, ...productCategoryTree.value.map(toOption)]
})

const filteredProductStandards = computed(() =>
  (productStandards.value ?? []).filter((item) =>
    matchesKeyword(item, masterFilters.standardKeyword, [
      'code',
      'name',
      'englishName',
      'standardStatus',
      'ccs',
      'ics',
      'publisher',
      'memo',
    ]),
  ),
)

const filteredSeries = computed(() =>
  (series.value ?? []).filter(
    (item) =>
      (!visibleCategoryIds.value || visibleCategoryIds.value.has(item.categoryId)) &&
      (!filters.status || item.status === filters.status) &&
      matchesKeyword(item, filters.keyword, ['code', 'name', 'aliasNames', 'categoryName']),
  ),
)
const categoryDialogTitle = computed(() => (editingCategoryId.value ? '编辑产品分类' : '新增产品分类'))
const standardDialogTitle = computed(() => (editingStandardId.value ? '编辑执行标准' : '新增执行标准'))
const temperDialogTitle = computed(() => (editingTemperId.value ? '编辑产品状态' : '新增产品状态'))
const seriesDialogTitle = computed(() => (editingSeriesId.value ? '编辑产品系列' : '新增产品系列'))
const fieldDialogTitle = computed(() => (editingFieldId.value ? '编辑系列字段' : '新增系列字段'))
const presetDialogTitle = computed(() => (editingPresetId.value ? '编辑规格预设' : '新增规格预设'))

const categoryRules: FormRules = {
  name: [{ required: true, message: '请填写分类名称', trigger: 'blur' }],
}
const standardRules: FormRules = {
  code: [{ required: true, message: '请填写标准号', trigger: 'blur' }],
  name: [{ required: true, message: '请填写中文标准名称', trigger: 'blur' }],
}
const temperRules: FormRules = {
  name: [{ required: true, message: '请填写状态名称', trigger: 'blur' }],
}
const seriesRules: FormRules = {
  categoryId: [{ required: true, message: '请选择产品分类', trigger: 'change' }],
  code: [
    { required: true, message: '请填写系列编码', trigger: 'blur' },
    { pattern: /^[A-Z0-9][A-Z0-9_-]*$/, message: '只能使用大写字母、数字、- 和 _', trigger: 'blur' },
  ],
  name: [{ required: true, message: '请填写系列名称', trigger: 'blur' }],
}
const presetRules: FormRules = {
  name: [{ required: true, message: '请填写品名', trigger: 'blur' }],
  model: [{ required: true, message: '请填写型号', trigger: 'blur' }],
}

watch(
  () => series.value,
  (rows) => {
    const requestedId = Number(route.query.seriesId)
    const requestedSeries = rows?.find((item) => item.id === requestedId)
    if (route.query.view === 'specs' && requestedSeries) {
      catalogView.value = 'specs'
      seriesConfigTab.value = route.query.tab === 'presets' ? 'presets' : 'fields'
      if (activeSeriesId.value !== requestedSeries.id) {
        activeSeriesId.value = requestedSeries.id
        void loadSeriesMaster()
      }
    } else if (route.query.view === 'specs' && rows) {
      catalogView.value = 'series'
      void router.replace('/product-catalog')
    } else if (!activeSeriesId.value && rows?.length) {
      activeSeriesId.value = rows[0].id
      void loadSeriesMaster()
    }
  },
  { immediate: true },
)

watch(
  () => route.fullPath,
  () => {
    const requestedId = Number(route.query.seriesId)
    const requestedSeries = (series.value ?? []).find((item) => item.id === requestedId)
    const requestedSpecsView = route.query.view === 'specs'
    catalogView.value = requestedSpecsView && requestedSeries ? 'specs' : 'series'
    seriesConfigTab.value = route.query.tab === 'presets' ? 'presets' : 'fields'
    if (requestedSpecsView && !requestedSeries && series.value) {
      void router.replace('/product-catalog')
      return
    }
    if (catalogView.value === 'specs' && requestedSeries && requestedId !== activeSeriesId.value) {
      activeSeriesId.value = requestedId
      void loadSeriesMaster()
    }
  },
)

watch(seriesConfigTab, (tab) => {
  if (catalogView.value !== 'specs' || !activeSeriesId.value || route.query.tab === tab) return
  void router.replace({
    path: '/product-catalog',
    query: { view: 'specs', seriesId: String(activeSeriesId.value), tab },
  })
})

const createCategoryMutation = useMutation({
  mutationFn: api.createProductCategory,
  onSuccess: async () => {
    ElMessage.success('产品分类已新增')
    closeCategoryDialog()
    await queryClient.invalidateQueries({ queryKey: ['product-categories'] })
  },
})
const updateCategoryMutation = useMutation({
  mutationFn: ({ id, data }: { id: number; data: Partial<ProductCategory> }) => api.updateProductCategory(id, data),
  onSuccess: async () => {
    ElMessage.success('产品分类已更新')
    closeCategoryDialog()
    await queryClient.invalidateQueries({ queryKey: ['product-categories'] })
  },
})
const createStandardMutation = useMutation({
  mutationFn: api.createProductStandard,
  onSuccess: async () => {
    ElMessage.success('执行标准已新增')
    closeStandardDialog()
    await queryClient.invalidateQueries({ queryKey: ['product-standards'] })
  },
})
const updateStandardMutation = useMutation({
  mutationFn: ({ id, data }: { id: number; data: Partial<ProductStandard> }) => api.updateProductStandard(id, data),
  onSuccess: async () => {
    ElMessage.success('执行标准已更新')
    closeStandardDialog()
    await queryClient.invalidateQueries({ queryKey: ['product-standards'] })
  },
})
const createTemperMutation = useMutation({
  mutationFn: api.createProductTemper,
  onSuccess: async () => {
    ElMessage.success('产品状态已新增')
    closeTemperDialog()
    await queryClient.invalidateQueries({ queryKey: ['product-tempers'] })
  },
})
const updateTemperMutation = useMutation({
  mutationFn: ({ id, data }: { id: number; data: Partial<ProductTemper> }) => api.updateProductTemper(id, data),
  onSuccess: async () => {
    ElMessage.success('产品状态已更新')
    closeTemperDialog()
    await queryClient.invalidateQueries({ queryKey: ['product-tempers'] })
  },
})
const createSeriesMutation = useMutation({
  mutationFn: api.createProductSeries,
  onSuccess: async (result) => {
    const createdId = result.id
    ElMessage.success('产品系列已新增，可直接配置经营规格')
    closeSeriesDialog()
    await queryClient.invalidateQueries({ queryKey: ['product-series'] })
    await openSeriesConfig(createdId, 'fields', true)
  },
})
const updateSeriesMutation = useMutation({
  mutationFn: ({ id, data }: { id: number; data: Partial<ProductSeries> }) => api.updateProductSeries(id, data),
  onSuccess: async () => {
    ElMessage.success('产品系列已更新')
    closeSeriesDialog()
    await queryClient.invalidateQueries({ queryKey: ['product-series'] })
  },
})
const createFieldMutation = useMutation({
  mutationFn: api.createSeriesField,
  onSuccess: async () => {
    ElMessage.success('字段已添加')
    closeFieldDialog()
    await loadSeriesMaster()
  },
})
const updateFieldMutation = useMutation({
  mutationFn: ({ id, data }: { id: number; data: Partial<SeriesField> }) => api.updateSeriesField(id, data),
  onSuccess: async () => {
    ElMessage.success('字段已更新')
    closeFieldDialog()
    await loadSeriesMaster()
  },
})
const createPresetMutation = useMutation({
  mutationFn: api.createSpecPreset,
  onSuccess: async () => {
    ElMessage.success('规格预设已添加')
    closePresetDialog()
    await loadSeriesMaster()
  },
})
const updatePresetMutation = useMutation({
  mutationFn: ({ id, data }: { id: number; data: Partial<SpecPreset> }) => api.updateSpecPreset(id, data),
  onSuccess: async () => {
    ElMessage.success('规格预设已更新')
    closePresetDialog()
    await loadSeriesMaster()
  },
})

function openCategoryCreate() {
  resetCategoryForm()
  categoryForm.parentId = filters.categoryId ?? 0
  categoryDialogVisible.value = true
}

function openCategoryEdit(row: ProductCategory) {
  editingCategoryId.value = row.id
  categoryForm.parentId = row.parentId
  categoryForm.name = row.name
  categoryForm.sortNo = row.sortNo
  categoryForm.status = row.status || 'ENABLED'
  categoryDialogVisible.value = true
}

async function saveCategory() {
  const valid = await categoryFormRef.value?.validate().catch(() => false)
  if (!valid) return
  const parentId = Number(categoryForm.parentId || 0)
  if (blockedParentCategoryIds.value.has(parentId)) {
    ElMessage.error('上级分类不能选择自己或自己的下级')
    return
  }
  categoryForm.parentId = parentId
  if (editingCategoryId.value) {
    updateCategoryMutation.mutate({ id: editingCategoryId.value, data: { ...categoryForm } })
  } else {
    createCategoryMutation.mutate({ ...categoryForm })
  }
}

function closeCategoryDialog() {
  categoryDialogVisible.value = false
  resetCategoryForm()
}

function resetCategoryForm() {
  editingCategoryId.value = undefined
  categoryForm.parentId = 0
  categoryForm.name = ''
  categoryForm.sortNo = 0
  categoryForm.status = 'ENABLED'
  categoryFormRef.value?.clearValidate()
}

function openStandardCreate() {
  resetStandardForm()
  standardDialogVisible.value = true
}

function openStandardEdit(row: ProductStandard) {
  editingStandardId.value = row.id
  standardForm.code = row.code
  standardForm.name = row.name
  standardForm.englishName = row.englishName || ''
  standardForm.standardStatus = row.standardStatus || '现行'
  standardForm.ccs = row.ccs || ''
  standardForm.ics = row.ics || ''
  standardForm.publishDate = row.publishDate || null
  standardForm.implementationDate = row.implementationDate || null
  standardForm.abolitionDate = row.abolitionDate || null
  standardForm.competentDepartment = row.competentDepartment || ''
  standardForm.responsibleDepartment = row.responsibleDepartment || ''
  standardForm.publisher = row.publisher || ''
  standardForm.memo = row.memo || ''
  standardForm.sourceUrl = row.sourceUrl || ''
  standardForm.status = row.status || 'ENABLED'
  standardDialogVisible.value = true
}

async function saveStandard() {
  const valid = await standardFormRef.value?.validate().catch(() => false)
  if (!valid) return
  if (editingStandardId.value) {
    updateStandardMutation.mutate({ id: editingStandardId.value, data: { ...standardForm } })
  } else {
    createStandardMutation.mutate({ ...standardForm })
  }
}

function closeStandardDialog() {
  standardDialogVisible.value = false
  resetStandardForm()
}

function resetStandardForm() {
  editingStandardId.value = undefined
  standardForm.code = ''
  standardForm.name = ''
  standardForm.englishName = ''
  standardForm.standardStatus = '现行'
  standardForm.ccs = ''
  standardForm.ics = ''
  standardForm.publishDate = null
  standardForm.implementationDate = null
  standardForm.abolitionDate = null
  standardForm.competentDepartment = ''
  standardForm.responsibleDepartment = ''
  standardForm.publisher = ''
  standardForm.memo = ''
  standardForm.sourceUrl = ''
  standardForm.status = 'ENABLED'
  standardFormRef.value?.clearValidate()
}

function openTemperCreate() {
  resetTemperForm()
  temperDialogVisible.value = true
}

function openTemperEdit(row: ProductTemper) {
  editingTemperId.value = row.id
  temperForm.name = row.name
  temperForm.code = row.code
  temperForm.englishName = row.englishName || ''
  temperForm.status = row.status || 'ENABLED'
  temperDialogVisible.value = true
}

async function saveTemper() {
  const valid = await temperFormRef.value?.validate().catch(() => false)
  if (!valid) return
  if (editingTemperId.value) {
    updateTemperMutation.mutate({ id: editingTemperId.value, data: { ...temperForm } })
  } else {
    createTemperMutation.mutate({ ...temperForm })
  }
}

function closeTemperDialog() {
  temperDialogVisible.value = false
  resetTemperForm()
}

function resetTemperForm() {
  editingTemperId.value = undefined
  temperForm.name = ''
  temperForm.code = ''
  temperForm.englishName = ''
  temperForm.status = 'ENABLED'
  temperFormRef.value?.clearValidate()
}

function openSeriesCreate() {
  resetSeriesForm()
  seriesForm.categoryId = filters.categoryId
  seriesDialogVisible.value = true
}

function openSeriesEdit(row: ProductSeries) {
  editingSeriesId.value = row.id
  seriesForm.categoryId = row.categoryId
  seriesForm.code = row.code || ''
  seriesForm.name = row.name
  seriesForm.aliasNames = row.aliasNames
  seriesForm.packageUnitCategoryId = row.packageUnitCategoryId || undefined
  seriesForm.priceUnitCategoryId = row.priceUnitCategoryId || undefined
  seriesForm.status = row.status || 'ENABLED'
  seriesDialogVisible.value = true
}

function openSeriesPackagingBinding(row: ProductSeries) {
  activeSeriesPackagingBinding.value = seriesPackagingBindingMap.value.get(row.id)
  if (!activeSeriesPackagingBinding.value) {
    ElMessage.warning('系列计量模板关系尚未加载，请稍后重试')
    return
  }
  seriesPackagingDialogVisible.value = true
}

async function selectSeriesForSpecs(row: ProductSeries) {
  await openSeriesConfig(row.id, 'fields', false)
}

async function openSeriesConfig(seriesId: number, tab: 'fields' | 'presets', replace: boolean) {
  activeSeriesId.value = seriesId
  seriesConfigTab.value = tab
  catalogView.value = 'specs'
  await loadSeriesMaster()
  const location = {
    path: '/product-catalog',
    query: { view: 'specs', seriesId: String(seriesId), tab },
  }
  if (replace) await router.replace(location)
  else await router.push(location)
}

async function changeActiveSeries() {
  if (!activeSeriesId.value) return
  await loadSeriesMaster()
  await router.replace({
    path: '/product-catalog',
    query: { view: 'specs', seriesId: String(activeSeriesId.value), tab: seriesConfigTab.value },
  })
}

async function returnToSeriesList() {
  catalogView.value = 'series'
  await router.replace('/product-catalog')
}

async function saveSeries() {
  const valid = await seriesFormRef.value?.validate().catch(() => false)
  if (!valid) return
  if (editingSeriesId.value) {
    updateSeriesMutation.mutate({ id: editingSeriesId.value, data: { ...seriesForm } })
  } else {
    createSeriesMutation.mutate({ ...seriesForm })
  }
}

function closeSeriesDialog() {
  seriesDialogVisible.value = false
  resetSeriesForm()
}

function resetSeriesForm() {
  editingSeriesId.value = undefined
  seriesForm.categoryId = undefined
  seriesForm.code = ''
  seriesForm.name = ''
  seriesForm.aliasNames = ''
  seriesForm.packageUnitCategoryId = undefined
  seriesForm.priceUnitCategoryId = undefined
  seriesForm.status = 'ENABLED'
  seriesFormRef.value?.clearValidate()
}

async function loadSeriesMaster() {
  resetFieldForm()
  resetPresetForm()
  if (!activeSeriesId.value) {
    seriesFields.value = []
    specPresets.value = []
    return
  }
  const [fields, presets] = await Promise.all([api.seriesFields(activeSeriesId.value), api.specPresets(activeSeriesId.value)])
  seriesFields.value = fields
  specPresets.value = presets
}

function navigateToFieldLibrary() {
  router.push('/product-fields')
}

function openFieldCreate() {
  if (!activeSeriesId.value) {
    ElMessage.warning('请先选择产品系列')
    return
  }
  resetFieldForm()
  fieldDialogVisible.value = true
}

function openFieldEdit(row: SeriesField) {
  editingFieldId.value = row.id
  fieldForm.fieldId = row.fieldId
  fieldForm.displayName = row.displayName
  fieldForm.optionJson = row.optionJson || '[]'
  seriesFieldOptions.value = parseSeriesFieldOptions(row.optionJson).map(createSeriesFieldOption)
  if (!seriesFieldOptions.value.length) seriesFieldOptions.value = [createSeriesFieldOption()]
  fieldForm.unitCategoryId = optionalPositiveId(row.unitCategoryId)
  fieldForm.unitId = fieldForm.unitCategoryId ? optionalPositiveId(row.unitId) : undefined
  fieldForm.unitName = row.unitName
  fieldForm.required = row.required
  fieldForm.skuDimension = row.skuDimension
  fieldForm.includeInSpec = row.includeInSpec
  fieldForm.searchable = row.searchable
  fieldForm.orderVisible = row.orderVisible
  fieldForm.minValue = row.minValue
  fieldForm.maxValue = row.maxValue
  fieldForm.decimalScale = row.decimalScale
  fieldForm.sortNo = row.sortNo
  fieldForm.status = row.status || 'ENABLED'
  fieldDialogVisible.value = true
}

function saveField() {
  if (!activeSeriesId.value || !fieldForm.fieldId) {
    ElMessage.warning('请选择系列和字段')
    return
  }
  const options = seriesFieldOptions.value.map((item) => item.value.trim()).filter(Boolean)
  if (selectedFieldDefinition.value?.fieldType === 'SELECT' && !options.length) {
    ElMessage.warning('下拉字段至少需要一个本系列可选值')
    return
  }
  if (new Set(options).size !== options.length) {
    ElMessage.warning('本系列可选值不能重复')
    return
  }
  if (fieldForm.unitCategoryId && !fieldForm.unitId) {
    ElMessage.warning('请选择具体计量单位')
    return
  }
  if (fieldForm.skuDimension && !fieldForm.required) {
    ElMessage.warning('用于区分商品的字段必须设置为必填')
    return
  }
  if (selectedFieldDefinition.value?.fieldType === 'NUMBER'
      && fieldForm.minValue != null
      && fieldForm.maxValue != null
      && fieldForm.minValue > fieldForm.maxValue) {
    ElMessage.warning('最小值不能大于最大值')
    return
  }
  const payload = {
    ...fieldForm,
    optionJson: selectedFieldDefinition.value?.fieldType === 'SELECT' ? JSON.stringify(options) : '[]',
    productSeriesId: activeSeriesId.value,
  }
  if (editingFieldId.value) {
    updateFieldMutation.mutate({ id: editingFieldId.value, data: payload })
  } else {
    createFieldMutation.mutate(payload)
  }
}

function closeFieldDialog() {
  fieldDialogVisible.value = false
  resetFieldForm()
}

function resetFieldForm() {
  editingFieldId.value = undefined
  fieldForm.fieldId = undefined
  fieldForm.displayName = ''
  fieldForm.optionJson = '[]'
  seriesFieldOptions.value = [createSeriesFieldOption()]
  fieldForm.unitCategoryId = undefined
  fieldForm.unitId = undefined
  fieldForm.unitName = ''
  fieldForm.required = true
  fieldForm.skuDimension = true
  fieldForm.includeInSpec = true
  fieldForm.searchable = true
  fieldForm.orderVisible = true
  fieldForm.minValue = undefined
  fieldForm.maxValue = undefined
  fieldForm.decimalScale = 3
  fieldForm.sortNo = 10
  fieldForm.status = 'ENABLED'
}

function handleFieldDefinitionChange(fieldId?: number) {
  const definition = (customFields.value ?? []).find((item) => item.id === fieldId)
  fieldForm.displayName = definition?.name || ''
  fieldForm.unitCategoryId = optionalPositiveId(definition?.unitCategoryId)
  fieldForm.unitId = undefined
  fieldForm.unitName = ''
  fieldForm.decimalScale = definition?.valueType === 'INTEGER' ? 0 : 3
  fieldForm.optionJson = definition?.fieldType === 'SELECT' ? definition.optionJson || '[]' : '[]'
  seriesFieldOptions.value = parseSeriesFieldOptions(fieldForm.optionJson).map(createSeriesFieldOption)
  if (!seriesFieldOptions.value.length) seriesFieldOptions.value = [createSeriesFieldOption()]
  if (fieldUnitOptions.value.length === 1) fieldForm.unitId = fieldUnitOptions.value[0].id
}

function handleFieldUnitCategoryChange() {
  fieldForm.unitId = undefined
  fieldForm.unitName = ''
  if (fieldUnitOptions.value.length === 1) fieldForm.unitId = fieldUnitOptions.value[0].id
}

function optionalPositiveId(value?: number | null) {
  return typeof value === 'number' && value > 0 ? value : undefined
}

function addSeriesFieldOption() {
  seriesFieldOptions.value.push(createSeriesFieldOption())
}

function removeSeriesFieldOption(id: number) {
  if (seriesFieldOptions.value.length === 1) {
    seriesFieldOptions.value = [createSeriesFieldOption()]
    return
  }
  seriesFieldOptions.value = seriesFieldOptions.value.filter((item) => item.id !== id)
}

function parseSeriesFieldOptions(optionJson?: string) {
  try {
    const parsed = JSON.parse(optionJson || '[]')
    return Array.isArray(parsed) ? parsed.map((item) => String(item)) : []
  } catch {
    return []
  }
}

function openPresetCreate() {
  if (!activeSeriesId.value) {
    ElMessage.warning('请先选择产品系列')
    return
  }
  resetPresetForm()
  presetFieldValues.value = Object.fromEntries(presetSeriesFields.value.map((field) => [field.id, undefined]))
  presetDialogVisible.value = true
}

async function openPresetEdit(row: SpecPreset) {
  editingPresetId.value = row.id
  presetForm.name = row.name
  presetForm.model = row.model
  presetForm.attributeJson = row.attributeJson
  presetForm.specText = row.specText
  presetForm.skuSuffix = row.skuSuffix
  presetForm.sortNo = row.sortNo
  presetForm.status = row.status || 'ENABLED'
  const legacyAttributes = parseAttributeObject(row.attributeJson)
  const linkedValues = new Map((row.fieldValues ?? []).map((item) => [item.seriesFieldId, item.value]))
  presetHistoricalValues.value = historicalPresetValues(row)
  presetDialogVisible.value = true
  await nextTick()
  presetFieldValues.value = Object.fromEntries(presetSeriesFields.value.map((field) => {
    const linkedValue = linkedValues.get(field.id)
    const fallbackValue = legacyAttributes[field.fieldName]
    return [field.id, normalizePresetValueForInput(field, linkedValue ?? fallbackValue)]
  }))
}

async function savePreset() {
  if (!activeSeriesId.value) {
    ElMessage.warning('当前适用产品系列未加载，请关闭窗口后重新选择')
    return
  }
  const valid = await presetFormRef.value?.validate().catch(() => false)
  if (!valid) return
  const { fieldValues, attributeJson } = buildPresetFieldValues()
  const specText = presetForm.specText || `${presetForm.name} ${presetForm.model}`
  const payload = { ...presetForm, productSeriesId: activeSeriesId.value, fieldValues, attributeJson, specText }
  if (editingPresetId.value) {
    updatePresetMutation.mutate({ id: editingPresetId.value, data: payload })
  } else {
    createPresetMutation.mutate(payload)
  }
}

function closePresetDialog() {
  presetDialogVisible.value = false
  resetPresetForm()
}

function parseAttributeObject(attributeJson?: string) {
  if (!attributeJson) return {} as Record<string, unknown>
  try {
    const parsed = JSON.parse(attributeJson) as unknown
    return parsed && !Array.isArray(parsed) && typeof parsed === 'object'
      ? parsed as Record<string, unknown>
      : {}
  } catch {
    return {}
  }
}

function buildPresetFieldValues() {
  const attributes: Record<string, string | number> = {}
  const fieldValues: Array<{ seriesFieldId: number; value: string | number | null }> = []
  for (const field of presetSeriesFields.value) {
    const currentValue = presetFieldValues.value[field.id]
    const value = typeof currentValue === 'string' ? currentValue.trim() : currentValue
    const normalizedValue = value === undefined || value === null || value === '' ? null : value
    if (normalizedValue !== null) attributes[field.fieldName] = normalizedValue
    fieldValues.push({ seriesFieldId: field.id, value: normalizedValue })
  }
  return { fieldValues, attributeJson: JSON.stringify(attributes) }
}

function fieldOptions(field: SeriesField) {
  if (!field.optionJson) return []
  try {
    const parsed = JSON.parse(field.optionJson)
    return Array.isArray(parsed) ? parsed.map((item) => String(item)) : []
  } catch {
    return []
  }
}

function presetFieldMeta(field: SeriesField) {
  const displayName = field.displayName || field.fieldName
  const parts: string[] = []
  if (field.fieldName !== displayName) parts.push(`字段：${field.fieldName}`)
  if (field.unitName) parts.push(`单位：${field.unitName}`)
  return parts.join(' · ')
}

function normalizePresetValueForInput(field: SeriesField, value: unknown): PresetDefaultValue {
  if (value === undefined || value === null || value === '') return undefined
  if (field.fieldType === 'NUMBER') {
    const number = typeof value === 'number'
      ? value
      : Number(String(value).trim().match(/^-?\d+(?:\.\d+)?/)?.[0])
    return Number.isFinite(number) ? number : undefined
  }
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}

function setPresetFieldValue(fieldId: number, value: string | number | null | undefined) {
  if (value === undefined || value === null || value === '') {
    delete presetFieldValues.value[fieldId]
    return
  }
  presetFieldValues.value[fieldId] = value
}

function numericPresetFieldValue(fieldId: number) {
  const value = presetFieldValues.value[fieldId]
  if (typeof value === 'number') return value
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : undefined
}

function textPresetFieldValue(fieldId: number) {
  const value = presetFieldValues.value[fieldId]
  return value == null ? '' : String(value)
}

function historicalPresetValues(preset: SpecPreset): HistoricalFieldValue[] {
  const activeFieldIds = new Set(presetSeriesFields.value.map((field) => field.id))
  const activeFieldNames = new Set(presetSeriesFields.value.map((field) => field.fieldName))
  const relatedFieldNames = new Set((preset.fieldValues ?? []).map((item) => item.fieldName).filter(Boolean))
  const history: HistoricalFieldValue[] = []

  for (const item of preset.fieldValues ?? []) {
    if (activeFieldIds.has(item.seriesFieldId)) continue
    const field = seriesFields.value.find((candidate) => candidate.id === item.seriesFieldId)
    history.push({
      key: `related-${item.seriesFieldId}`,
      label: field?.displayName || item.fieldName || `字段${item.fieldId || item.seriesFieldId}`,
      value: String(item.value ?? ''),
      reason: field?.status === 'DISABLED' ? '系列字段已停用，历史值保留' : '不在当前启用模板，历史值保留',
    })
  }

  for (const [fieldName, value] of Object.entries(parseAttributeObject(preset.attributeJson))) {
    if (activeFieldNames.has(fieldName) || relatedFieldNames.has(fieldName)) continue
    const field = seriesFields.value.find((candidate) => candidate.fieldName === fieldName)
    history.push({
      key: `legacy-${fieldName}`,
      label: field?.displayName || fieldName,
      value: value == null ? '' : typeof value === 'object' ? JSON.stringify(value) : String(value),
      reason: field?.status === 'DISABLED' ? '系列字段已停用，历史值保留' : '旧版未关联字段，历史值保留',
    })
  }
  return history
}

function attributeSummary(preset: SpecPreset) {
  const availableFields = new Map(presetSeriesFields.value.map((field) => [field.id, field]))
  const linkedEntries = (preset.fieldValues ?? []).flatMap((item) => {
    const field = availableFields.get(item.seriesFieldId)
    return field ? [`${field.displayName || field.fieldName}：${item.value}`] : []
  })
  const legacyAttributes = parseAttributeObject(preset.attributeJson)
  const fallbackEntries = linkedEntries.length ? [] : presetSeriesFields.value.flatMap((field) => {
    const value = legacyAttributes[field.fieldName]
    return value === undefined || value === null || value === ''
      ? []
      : [`${field.displayName || field.fieldName}：${typeof value === 'object' ? JSON.stringify(value) : String(value)}`]
  })
  const historicalCount = historicalPresetValues(preset).length
  const historicalHint = historicalCount ? `另有 ${historicalCount} 项历史字段已保留` : ''
  return [[...linkedEntries, ...fallbackEntries].join('；'), historicalHint].filter(Boolean).join('；') || '—'
}

function resetPresetForm() {
  editingPresetId.value = undefined
  presetForm.name = ''
  presetForm.model = ''
  presetForm.attributeJson = ''
  presetForm.specText = ''
  presetForm.skuSuffix = ''
  presetForm.sortNo = 10
  presetForm.status = 'ENABLED'
  presetFieldValues.value = {}
  presetHistoricalValues.value = []
  presetFormRef.value?.clearValidate()
}

function resetFilters() {
  filters.keyword = ''
  filters.status = ''
}
</script>

<style scoped>
.series-row-actions {
  display: inline-flex;
  align-items: center;
  gap: 0;
  white-space: nowrap;
}
</style>
