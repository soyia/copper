<template>
  <div class="page" v-if="partner">
    <div class="panel">
      <PageHeader :title="partner.name" :back-to="backPath" />
      <div class="panel-body">
        <div class="detail-grid detail-grid-3">
          <div class="detail-label">业务编号</div>
          <div class="detail-value">{{ partner.businessNo }}</div>
          <div class="detail-label">业务角色</div>
          <div class="detail-value">{{ typeName(partner) }}</div>
          <div class="detail-label">主体类型</div>
          <div class="detail-value">{{ entityTypeName(partner.entityType) }}</div>
          <div class="detail-label">全称</div>
          <div class="detail-value">{{ partner.name || '-' }}</div>
          <div class="detail-label">简称</div>
          <div class="detail-value">{{ partner.aliasName || '-' }}</div>
          <div class="detail-label">统一信用码</div>
          <div class="detail-value">{{ partner.creditCode || '-' }}</div>
          <div class="detail-label">状态</div>
          <div class="detail-value">{{ statusName(partner.status) }}</div>
          <div class="detail-label">主要联系人</div>
          <div class="detail-value">{{ partner.contactPerson || '-' }}{{ partner.primaryContactRole ? ` / ${partner.primaryContactRole}` : '' }}</div>
          <div class="detail-label">电话</div>
          <div class="detail-value">{{ formatPhone(partner.phone) }}</div>
          <div class="detail-label">微信 / 邮箱</div>
          <div class="detail-value">{{ [partner.wechat, partner.email].filter(Boolean).join(' / ') || '-' }}</div>
          <div class="detail-label">开始合作</div>
          <div class="detail-value">{{ formatDate(partner.cooperationStartDate) }}</div>
          <div class="detail-label">成立日</div>
          <div class="detail-value">{{ formatDate(partner.establishmentDate) }}</div>
          <div class="detail-label">来源 / 标签</div>
          <div class="detail-value">{{ [partner.source, partner.tags].filter(Boolean).join(' / ') || '-' }}</div>
          <div class="detail-label">地址</div>
          <div class="detail-value detail-value-span-3">{{ partner.address || '-' }}</div>
          <div v-if="partner.status === 'DISABLED'" class="detail-label">停用原因</div>
          <div v-if="partner.status === 'DISABLED'" class="detail-value detail-value-span-all">{{ partner.disabledReason || '-' }}</div>
          <div class="detail-label">备注</div>
          <div class="detail-value detail-value-span-all">{{ partner.memo || '-' }}</div>
        </div>
      </div>
    </div>

    <div v-if="isCustomer || isSupplier" class="panel">
      <div class="panel-header">
        <div class="panel-title">业务规则</div>
      </div>
      <div class="panel-body">
        <div class="detail-grid detail-grid-3">
          <template v-if="isCustomer">
            <div class="detail-label">客户身份</div>
            <div class="detail-value"><el-tag :type="partner.customerStatus === 'DISABLED' ? 'danger' : 'success'" size="small">{{ statusName(partner.customerStatus) }}</el-tag></div>
            <div v-if="partner.customerStatus === 'DISABLED'" class="detail-label">停用原因</div>
            <div v-if="partner.customerStatus === 'DISABLED'" class="detail-value detail-value-span-3">{{ partner.customerDisabledReason || '-' }}</div>
            <div class="detail-label">客户等级</div>
            <div class="detail-value">{{ partner.customerLevel || '-' }}</div>
            <div class="detail-label">结算方式</div>
            <div class="detail-value">{{ settlementText(partner.customerSettlementMethod, partner.customerSettlementDays) }}</div>
            <div class="detail-label">授信额度</div>
            <div class="detail-value amount">{{ money(partner.creditLimit) }}</div>
            <div class="detail-label">客户开票</div>
            <div class="detail-value">{{ invoiceText(partner.customerTaxIncluded, partner.customerInvoiceType, partner.customerTaxRate) }}</div>
            <div class="detail-label">偏好产品</div>
            <div class="detail-value detail-value-span-3">{{ partner.preferredProducts || '-' }}</div>
          </template>
          <template v-if="isSupplier">
            <div class="detail-label">供应商身份</div>
            <div class="detail-value"><el-tag :type="partner.supplierStatus === 'DISABLED' ? 'danger' : 'success'" size="small">{{ statusName(partner.supplierStatus) }}</el-tag></div>
            <div v-if="partner.supplierStatus === 'DISABLED'" class="detail-label">停用原因</div>
            <div v-if="partner.supplierStatus === 'DISABLED'" class="detail-value detail-value-span-3">{{ partner.supplierDisabledReason || '-' }}</div>
            <div class="detail-label">供应商结算</div>
            <div class="detail-value">{{ settlementText(partner.supplierSettlementMethod, partner.supplierSettlementDays) }}</div>
            <div class="detail-label">交期</div>
            <div class="detail-value">{{ partner.leadTimeDays ? `${partner.leadTimeDays} 天` : '-' }}</div>
            <div class="detail-label">最小起订额</div>
            <div class="detail-value amount">{{ money(partner.minOrderAmount) }}</div>
            <div class="detail-label">供应商开票</div>
            <div class="detail-value">{{ invoiceText(partner.supplierTaxIncluded, partner.supplierInvoiceType, partner.supplierTaxRate) }}</div>
            <div class="detail-label">资质说明</div>
            <div class="detail-value detail-value-span-3">{{ partner.qualificationMemo || '-' }}</div>
          </template>
        </div>
      </div>
    </div>

    <div v-if="isCustomer && canManageSalesRisk" class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">销售风控策略</div>
          <div class="muted">只在确认销售、订单变更和发货时控制；不会直接改动订单、库存、应收或会计数据。</div>
        </div>
        <el-button type="primary" plain @click="openSalesRiskPolicy">调整策略</el-button>
      </div>
      <div v-if="salesRiskPolicy" class="panel-body">
        <el-alert
          :type="salesRiskPolicy.enabled ? 'warning' : 'info'"
          :title="salesRiskPolicy.enabled ? '销售风险控制已启用' : '当前仅保留授信资料，销售动作不受该策略阻断'"
          :closable="false"
          show-icon
          style="margin-bottom: 14px"
        />
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="信用额度控制">{{ salesRiskPolicy.enabled && salesRiskPolicy.creditControlEnabled ? '启用' : '关闭' }}</el-descriptions-item>
          <el-descriptions-item label="授信额度">{{ money(salesRiskPolicy.creditLimit) }}</el-descriptions-item>
          <el-descriptions-item label="逾期控制">{{ salesRiskPolicy.enabled && salesRiskPolicy.overdueControlEnabled ? `超过 ${salesRiskPolicy.maxOverdueDays} 天需授权` : '关闭' }}</el-descriptions-item>
          <el-descriptions-item label="最低毛利控制">{{ salesRiskPolicy.enabled && salesRiskPolicy.marginControlEnabled ? `${Number(salesRiskPolicy.minimumMarginRate).toFixed(2)}%` : '关闭' }}</el-descriptions-item>
          <el-descriptions-item label="策略版本">V{{ salesRiskPolicy.versionNo }}</el-descriptions-item>
          <el-descriptions-item label="调整人">{{ salesRiskPolicy.updatedByName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="调整时间">{{ formatDateTime(salesRiskPolicy.updatedAt) }}</el-descriptions-item>
          <el-descriptions-item label="调整原因">{{ salesRiskPolicy.changeReason || '-' }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">资金概览</div>
      </div>
      <div class="panel-body">
        <div class="detail-grid detail-grid-3">
          <template v-for="row in fundRows" :key="row.dimension">
            <div class="detail-label">维度</div>
            <div class="detail-value">{{ row.label }}</div>
            <div class="detail-label">账户余额</div>
            <div class="detail-value amount">{{ money(row.accountBalance) }}</div>
            <div class="detail-label">冻结资金</div>
            <div class="detail-value amount">{{ money(row.frozenAmount) }}</div>
            <div class="detail-label">欠款金额</div>
            <div class="detail-value detail-value-span-all amount">{{ money(row.debtAmount) }}</div>
          </template>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">最近出入库</div>
      </div>
      <el-table :data="recentStockRecords" class="data-table" size="small" border show-overflow-tooltip max-height="260">
        <el-table-column label="类型" width="86" fixed="left">
          <template #default="{ row }">
            <el-tag :type="row.recordType === 'INBOUND' ? 'success' : 'warning'" size="small">{{ row.recordLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="单号" width="180" fixed="left">
          <template #default="{ row }">
            <router-link class="table-link" :to="row.route">{{ row.orderNo }}</router-link>
          </template>
        </el-table-column>
        <el-table-column prop="businessDate" label="日期" width="110" :formatter="formatDateColumn" />
        <el-table-column prop="warehouseName" label="仓库" min-width="140" />
        <el-table-column label="数量" width="120" align="right"><template #default="{ row }">{{ quantity(row.totalQuantity) }}</template></el-table-column>
        <el-table-column label="金额" width="130" align="right"><template #default="{ row }">{{ money(row.totalAmount) }}</template></el-table-column>
        <el-table-column label="已回/付款" width="130" align="right"><template #default="{ row }">{{ money(row.paidAmount) }}</template></el-table-column>
        <el-table-column label="回/付款状态" width="118">
          <template #default="{ row }"><el-tag :type="paymentStatusType(row.paymentStatus)" size="small">{{ paymentStatus(row.paymentStatus) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="业务状态" width="110"><template #default="{ row }">{{ businessStatusName(row.status) }}</template></el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">最近回付款</div>
      </div>
      <el-table :data="recentPaymentRecords" class="data-table" size="small" border show-overflow-tooltip max-height="260">
        <el-table-column label="类型" width="86" fixed="left">
          <template #default="{ row }">
            <el-tag :type="row.recordType === 'RECEIPT' ? 'success' : 'primary'" size="small">{{ row.recordLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="单号" width="190" fixed="left"><template #default="{ row }"><el-button link type="primary" @click="openRecentPayment(row)">{{ row.paymentNo }}</el-button></template></el-table-column>
        <el-table-column prop="businessDate" label="日期" width="110" :formatter="formatDateColumn" />
        <el-table-column label="来源单据" min-width="180"><template #default="{ row }">{{ row.sourceNo || '-' }}</template></el-table-column>
        <el-table-column label="金额" width="130" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
        <el-table-column label="方式" width="130"><template #default="{ row }">{{ paymentMethodName(row.paymentMethod) }}</template></el-table-column>
        <el-table-column label="账户" width="140"><template #default="{ row }">{{ accountTypeName(row.accountType) }}</template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{ row }">{{ verificationStatusName(row.verificationStatus) }}</template></el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">联系人</div>
        <el-button type="primary" @click="openContactCreate">新增联系人</el-button>
      </div>
      <el-table :data="contacts ?? []" class="data-table" size="small" border show-overflow-tooltip max-height="260">
        <el-table-column label="姓名" width="140" fixed="left">
          <template #default="{ row }">
            <button type="button" class="table-link text-link-button" @click="openContactDetail(row)">{{ row.name }}</button>
          </template>
        </el-table-column>
        <el-table-column label="主要" width="70"><template #default="{ row }"><el-tag v-if="row.firstChoice" type="success" size="small">主要</el-tag></template></el-table-column>
        <el-table-column prop="jobTitle" label="职务" width="120" />
        <el-table-column prop="department" label="部门" width="120" />
        <el-table-column label="电话" width="150"><template #default="{ row }">{{ formatPhone(row.phone) }}</template></el-table-column>
        <el-table-column prop="wechat" label="微信" width="130" />
        <el-table-column prop="email" label="邮箱" min-width="170" />
        <el-table-column label="首选方式" width="100"><template #default="{ row }">{{ methodTypeName(row.preferredMethod) }}</template></el-table-column>
        <el-table-column prop="birthday" label="生日" width="120" :formatter="formatDateColumn" />
        <el-table-column prop="memo" label="备注" min-width="180" />
        <el-table-column label="状态" width="90"><template #default="{ row }">{{ statusName(row.status) }}</template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click="openContactEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">业务地址</div>
        <el-button type="primary" @click="openAddressCreate">新增地址</el-button>
      </div>
      <el-table :data="addresses ?? []" class="data-table" size="small" border show-overflow-tooltip max-height="280">
        <el-table-column label="默认" width="80" fixed="left">
          <template #default="{ row }"><el-tag v-if="row.firstChoice" type="success" size="small">默认</el-tag></template>
        </el-table-column>
        <el-table-column label="类型" width="100"><template #default="{ row }">{{ addressTypeName(row.addressType) }}</template></el-table-column>
        <el-table-column prop="name" label="名称" width="140" />
        <el-table-column prop="contactPerson" label="联系人" width="120" />
        <el-table-column label="电话" width="150"><template #default="{ row }">{{ formatPhone(row.phone) }}</template></el-table-column>
        <el-table-column prop="detail" label="地址" min-width="260" />
        <el-table-column prop="memo" label="备注" min-width="160" />
        <el-table-column label="状态" width="90"><template #default="{ row }">{{ statusName(row.status) }}</template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }"><el-button text type="primary" @click="openAddressEdit(row)">编辑</el-button></template>
        </el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">银行账户</div>
        <el-button type="primary" @click="openBankAccountCreate">新增账户</el-button>
      </div>
      <el-table :data="bankAccounts ?? []" class="data-table" size="small" border show-overflow-tooltip max-height="260">
        <el-table-column label="默认" width="70"><template #default="{ row }"><el-tag v-if="row.firstChoice" type="success" size="small">默认</el-tag></template></el-table-column>
        <el-table-column prop="accountName" label="户名" min-width="160" />
        <el-table-column prop="bankName" label="开户行" min-width="180" />
        <el-table-column prop="bankAccount" label="银行账号" min-width="190" />
        <el-table-column prop="memo" label="备注" min-width="160" />
        <el-table-column label="状态" width="90"><template #default="{ row }">{{ statusName(row.status) }}</template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button text type="primary" @click="openBankAccountEdit(row)">编辑</el-button></template></el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">拜访沟通记录</div>
        <el-button type="primary" @click="openFollowupCreate">新增记录</el-button>
      </div>
      <el-table :data="followups ?? []" class="data-table" size="small" border show-overflow-tooltip max-height="280">
        <el-table-column prop="occurredAt" label="沟通时间" width="170" fixed="left" :formatter="formatDateTimeColumn" />
        <el-table-column label="类型" width="110"><template #default="{ row }">{{ followupTypeName(row.followupType) }}</template></el-table-column>
        <el-table-column label="方式" width="100"><template #default="{ row }">{{ methodTypeName(row.methodType) }}</template></el-table-column>
        <el-table-column prop="contactName" label="联系人" width="130" />
        <el-table-column label="联系人电话" width="150"><template #default="{ row }">{{ formatPhone(row.contactPhone) }}</template></el-table-column>
        <el-table-column prop="nextFollowDate" label="下次提醒" width="120" :formatter="formatDateColumn" />
        <el-table-column label="状态" width="90"><template #default="{ row }">{{ followupStatusName(row.status) }}</template></el-table-column>
        <el-table-column prop="memo" label="备注" min-width="220" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }"><el-button text type="primary" @click="openFollowupEdit(row)">编辑</el-button></template>
        </el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">特殊日期</div>
        <el-button type="primary" @click="openSpecialDateCreate">新增日期</el-button>
      </div>
      <el-table :data="specialDates ?? []" class="data-table" size="small" border show-overflow-tooltip max-height="260">
        <el-table-column label="类型" width="110" fixed="left"><template #default="{ row }">{{ specialDateTypeName(row.dateType) }}</template></el-table-column>
        <el-table-column prop="title" label="名称" width="160" />
        <el-table-column label="关联对象" width="130"><template #default="{ row }">{{ row.contactName || '单位' }}</template></el-table-column>
        <el-table-column prop="specialDate" label="日期" width="120" :formatter="formatDateColumn" />
        <el-table-column label="每年重复" width="100"><template #default="{ row }">{{ row.repeatYearly ? '是' : '否' }}</template></el-table-column>
        <el-table-column prop="remindDaysBefore" label="提前提醒(天)" width="120" />
        <el-table-column prop="memo" label="备注" min-width="200" />
        <el-table-column label="状态" width="90"><template #default="{ row }">{{ statusName(row.status) }}</template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }"><el-button text type="primary" @click="openSpecialDateEdit(row)">编辑</el-button></template>
        </el-table-column>
      </el-table>
    </div>

    <PaymentRecordDrawer v-model="paymentRecordDrawerVisible" :kind="paymentRecordDrawerKind" :record="activePaymentRecord" :origin-label="partner.name" />

    <el-dialog v-model="contactDialogVisible" :title="contactEditingId ? '编辑联系人' : '新增联系人'" width="680px">
      <el-form ref="contactFormRef" :model="contactForm" :rules="contactRules" label-width="92px">
        <el-form-item label="姓名" prop="name"><el-input v-model="contactForm.name" /></el-form-item>
        <el-form-item label="职务"><el-input v-model="contactForm.jobTitle" placeholder="如：老板、采购、财务" /></el-form-item>
        <el-form-item label="部门"><el-input v-model="contactForm.department" /></el-form-item>
        <el-form-item label="性别">
          <el-radio-group v-model="contactForm.gender">
            <el-radio :value="0">未填</el-radio>
            <el-radio :value="1">男</el-radio>
            <el-radio :value="2">女</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="电话"><el-input v-model="contactForm.phone" /></el-form-item>
        <el-form-item label="微信"><el-input v-model="contactForm.wechat" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="contactForm.email" /></el-form-item>
        <el-form-item label="首选联系">
          <el-select v-model="contactForm.preferredMethod">
            <el-option label="电话" value="PHONE" />
            <el-option label="微信" value="WECHAT" />
            <el-option label="邮件" value="EMAIL" />
            <el-option label="面谈" value="VISIT" />
          </el-select>
        </el-form-item>
        <el-form-item label="主要联系人"><el-switch v-model="contactForm.firstChoice" /></el-form-item>
        <el-form-item label="生日"><el-date-picker v-model="contactForm.birthday" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="contactForm.status">
            <el-option label="启用" value="ENABLED" />
            <el-option label="不可用" value="DISABLED" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="contactForm.memo" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="contactDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveContact">保存</el-button>
      </template>
    </el-dialog>

    <RelatedDetailDrawer
      v-model="contactDetailVisible"
      :title="`联系人 ${contactDetail?.name || ''}`"
      :subtitle="`${partner.name} · 联系人档案及关联跟进记录`"
      :trail="[{ label: partner.name }, { label: `联系人 ${contactDetail?.name || ''}` }]"
      size="760px"
      @breadcrumb="contactDetailVisible = false"
    >
      <div v-if="contactDetail" class="contact-detail">
        <div class="detail-grid detail-grid-3">
          <div class="detail-label">姓名</div>
          <div class="detail-value">{{ contactDetail.name || '-' }}</div>
          <div class="detail-label">性别</div>
          <div class="detail-value">{{ genderName(contactDetail.gender) }}</div>
          <div class="detail-label">电话</div>
          <div class="detail-value">{{ formatPhone(contactDetail.phone) }}</div>
          <div class="detail-label">职务 / 部门</div>
          <div class="detail-value">{{ [contactDetail.jobTitle, contactDetail.department].filter(Boolean).join(' / ') || '-' }}</div>
          <div class="detail-label">微信</div>
          <div class="detail-value">{{ contactDetail.wechat || '-' }}</div>
          <div class="detail-label">邮箱</div>
          <div class="detail-value">{{ contactDetail.email || '-' }}</div>
          <div class="detail-label">主要联系人</div>
          <div class="detail-value">{{ contactDetail.firstChoice ? '是' : '否' }}</div>
          <div class="detail-label">生日</div>
          <div class="detail-value">{{ formatDate(contactDetail.birthday) }}</div>
          <div class="detail-label">状态</div>
          <div class="detail-value">{{ statusName(contactDetail.status) }}</div>
          <div class="detail-label detail-empty"></div>
          <div class="detail-value detail-empty"></div>
          <div class="detail-label">备注</div>
          <div class="detail-value detail-value-span-all">{{ contactDetail.memo || '-' }}</div>
        </div>

        <div class="embedded-section">
          <div class="embedded-title">沟通记录</div>
          <el-table :data="contactFollowups" class="data-table" size="small" border show-overflow-tooltip max-height="220">
            <el-table-column prop="occurredAt" label="沟通时间" width="170" fixed="left" :formatter="formatDateTimeColumn" />
            <el-table-column label="类型" width="110"><template #default="{ row }">{{ followupTypeName(row.followupType) }}</template></el-table-column>
            <el-table-column prop="nextFollowDate" label="下次提醒" width="120" :formatter="formatDateColumn" />
            <el-table-column prop="memo" label="备注" min-width="220" />
          </el-table>
        </div>

        <div class="embedded-section">
          <div class="embedded-title">特殊日期</div>
          <el-table :data="contactSpecialDates" class="data-table" size="small" border show-overflow-tooltip max-height="220">
            <el-table-column label="类型" width="110" fixed="left"><template #default="{ row }">{{ specialDateTypeName(row.dateType) }}</template></el-table-column>
            <el-table-column prop="title" label="名称" min-width="160" />
            <el-table-column prop="specialDate" label="日期" width="120" :formatter="formatDateColumn" />
            <el-table-column label="每年重复" width="100"><template #default="{ row }">{{ row.repeatYearly ? '是' : '否' }}</template></el-table-column>
            <el-table-column prop="remindDaysBefore" label="提前提醒(天)" width="120" />
            <el-table-column prop="memo" label="备注" min-width="180" />
            <el-table-column label="状态" width="90"><template #default="{ row }">{{ statusName(row.status) }}</template></el-table-column>
          </el-table>
        </div>
      </div>
      <template #footer>
        <div class="drawer-footer">
          <el-button @click="contactDetailVisible = false">关闭</el-button>
          <el-button type="primary" :disabled="!contactDetail" @click="editContactFromDetail">编辑</el-button>
        </div>
      </template>
    </RelatedDetailDrawer>

    <el-dialog v-model="addressDialogVisible" :title="addressEditingId ? '编辑业务地址' : '新增业务地址'" width="560px">
      <el-form ref="addressFormRef" :model="addressForm" :rules="addressRules" label-width="86px">
        <el-form-item label="地址类型">
          <el-select v-model="addressForm.addressType">
            <el-option label="收货地址" value="RECEIVING" />
            <el-option label="送货地址" value="SHIPPING" />
            <el-option label="自提地址" value="PICKUP" />
            <el-option label="开票地址" value="INVOICE" />
            <el-option label="注册地址" value="REGISTERED" />
            <el-option label="办公地址" value="OFFICE" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称"><el-input v-model="addressForm.name" placeholder="如：仓库、工厂、门店" /></el-form-item>
        <el-form-item label="联系人" prop="contactPerson"><el-input v-model="addressForm.contactPerson" /></el-form-item>
        <el-form-item label="电话"><el-input v-model="addressForm.phone" /></el-form-item>
        <el-form-item label="地址" prop="detail"><el-input v-model="addressForm.detail" type="textarea" /></el-form-item>
        <el-form-item label="默认地址"><el-switch v-model="addressForm.firstChoice" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="addressForm.status">
            <el-option label="启用" value="ENABLED" />
            <el-option label="不可用" value="DISABLED" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="addressForm.memo" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addressDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveAddress">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="bankAccountDialogVisible" :title="bankAccountEditingId ? '编辑银行账户' : '新增银行账户'" width="580px">
      <el-form ref="bankAccountFormRef" :model="bankAccountForm" :rules="bankAccountRules" label-width="90px">
        <el-form-item label="账户户名" prop="accountName"><el-input v-model="bankAccountForm.accountName" /></el-form-item>
        <el-form-item label="开户行" prop="bankName"><el-input v-model="bankAccountForm.bankName" /></el-form-item>
        <el-form-item label="银行账号" prop="bankAccount"><el-input v-model="bankAccountForm.bankAccount" /></el-form-item>
        <el-form-item label="默认账户"><el-switch v-model="bankAccountForm.firstChoice" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="bankAccountForm.status">
            <el-option label="启用" value="ENABLED" />
            <el-option label="不可用" value="DISABLED" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="bankAccountForm.memo" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="bankAccountDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveBankAccount">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="followupDialogVisible" :title="followupEditingId ? '编辑沟通记录' : '新增沟通记录'" width="620px">
      <el-form ref="followupFormRef" :model="followupForm" :rules="followupRules" label-width="110px">
        <el-form-item label="类型" prop="followupType">
          <el-select v-model="followupForm.followupType">
            <el-option label="开发客户" value="DEVELOP_CUSTOMER" />
            <el-option label="拜访" value="VISIT" />
            <el-option label="电话" value="PHONE" />
            <el-option label="微信" value="WECHAT" />
            <el-option label="邮件" value="EMAIL" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="联系人">
          <el-select v-model="followupForm.contactId" clearable filterable>
            <el-option v-for="item in contacts ?? []" :key="item.id" :label="contactOptionLabel(item)" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="沟通方式">
          <el-select v-model="followupForm.methodType">
            <el-option label="面谈" value="VISIT" />
            <el-option label="电话" value="PHONE" />
            <el-option label="微信" value="WECHAT" />
            <el-option label="邮件" value="EMAIL" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="沟通时间" prop="occurredAt">
          <el-date-picker v-model="followupForm.occurredAt" type="datetime" format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
        </el-form-item>
        <el-form-item label="下次沟通提醒">
          <el-date-picker v-model="followupForm.nextFollowDate" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="跟进状态">
          <el-radio-group v-model="followupForm.status">
            <el-radio value="OPEN">待跟进</el-radio>
            <el-radio value="COMPLETED">已完成</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="followupForm.memo" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="followupDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveFollowup">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="specialDateDialogVisible" :title="specialDateEditingId ? '编辑特殊日期' : '新增特殊日期'" width="620px">
      <el-form ref="specialDateFormRef" :model="specialDateForm" :rules="specialDateRules" label-width="110px">
        <el-form-item label="类型" prop="dateType">
          <el-select v-model="specialDateForm.dateType">
            <el-option label="生日" value="BIRTHDAY" />
            <el-option label="成立日" value="ESTABLISHMENT" />
            <el-option label="纪念日" value="ANNIVERSARY" />
            <el-option label="合同日" value="CONTRACT" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称" prop="title"><el-input v-model="specialDateForm.title" placeholder="如：老板生日、合作纪念日、合同到期日" /></el-form-item>
        <el-form-item label="联系人">
          <el-select v-model="specialDateForm.contactId" clearable filterable placeholder="不选则为单位日期">
            <el-option v-for="item in contacts ?? []" :key="item.id" :label="contactOptionLabel(item)" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="日期" prop="specialDate"><el-date-picker v-model="specialDateForm.specialDate" value-format="YYYY-MM-DD" style="width: 100%" /></el-form-item>
        <el-form-item label="每年重复"><el-switch v-model="specialDateForm.repeatYearly" /></el-form-item>
        <el-form-item label="提前提醒(天)"><el-input-number v-model="specialDateForm.remindDaysBefore" :min="0" :max="365" :controls="false" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="specialDateForm.status">
            <el-option label="启用" value="ENABLED" />
            <el-option label="不可用" value="DISABLED" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="specialDateForm.memo" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="specialDateDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveSpecialDate">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="salesRiskDialogVisible" title="调整客户销售风控策略" width="650px" destroy-on-close>
      <el-alert title="启用后，风险不通过的销售确认、订单变更和发货将被阻止；只有老板或系统管理员填写原因后才能单次授权。" type="warning" :closable="false" show-icon style="margin-bottom: 16px" />
      <el-form label-width="145px">
        <el-form-item label="启用销售风控"><el-switch v-model="salesRiskForm.enabled" /></el-form-item>
        <el-form-item label="信用额度控制"><el-switch v-model="salesRiskForm.creditControlEnabled" :disabled="!salesRiskForm.enabled" /></el-form-item>
        <el-form-item label="授信额度"><el-input-number v-model="salesRiskForm.creditLimit" :min="0" :precision="2" style="width: 100%" :controls="false" /></el-form-item>
        <el-form-item label="逾期欠款控制"><el-switch v-model="salesRiskForm.overdueControlEnabled" :disabled="!salesRiskForm.enabled" /></el-form-item>
        <el-form-item label="允许逾期天数"><el-input-number v-model="salesRiskForm.maxOverdueDays" :min="0" :max="3650" :disabled="!salesRiskForm.enabled || !salesRiskForm.overdueControlEnabled" style="width: 100%" :controls="false" /></el-form-item>
        <el-form-item label="最低毛利控制"><el-switch v-model="salesRiskForm.marginControlEnabled" :disabled="!salesRiskForm.enabled" /></el-form-item>
        <el-form-item label="最低毛利率"><el-input-number v-model="salesRiskForm.minimumMarginRate" :min="0" :max="100" :precision="4" :disabled="!salesRiskForm.enabled || !salesRiskForm.marginControlEnabled" style="width: 100%" :controls="false"><template #suffix>%</template></el-input-number></el-form-item>
        <el-form-item label="调整原因" required><el-input v-model="salesRiskForm.changeReason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="说明本次额度、逾期或毛利政策变化的经营依据" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="salesRiskDialogVisible = false">取消</el-button><el-button type="primary" :loading="salesRiskSaving" @click="saveSalesRiskPolicy">保存新版本</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, type RouteLocationRaw } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import PageHeader from '@/components/PageHeader.vue'
import RelatedDetailDrawer from '@/components/RelatedDetailDrawer.vue'
import PaymentRecordDrawer from '@/components/PaymentRecordDrawer.vue'
import { api, type Address, type BankAccount, type Contact, type Followup, type InboundRow, type OutboundRow, type Partner, type PaymentRow, type SalesRiskPolicySave, type SpecialDate, type SupplierPaymentRow } from '@/api/client'
import { accountTypeName, currentDateText, currentDateTimeText, formatDate, formatDateColumn, formatDateTime, formatDateTimeColumn, formatPhone, money, paymentMethodName, paymentStatus, paymentStatusType, quantity, statusName } from '@/api/format'
import { relatedDetailRoute, returnPath } from '@/utils/navigation'
import { useAppStore } from '@/stores/app'

type StockRecord = {
  id: InboundRow['id'] | OutboundRow['id']
  recordType: 'INBOUND' | 'OUTBOUND'
  recordLabel: string
  route: RouteLocationRaw
  orderNo: string
  businessDate: string
  warehouseName: string
  totalQuantity: number
  totalAmount: number
  paidAmount: number
  paymentStatus: string
  status: string
}

type PaymentRecord = {
  id: PaymentRow['id'] | SupplierPaymentRow['id']
  recordType: 'RECEIPT' | 'PAYMENT'
  recordLabel: string
  paymentNo: string
  businessDate: string
  sourceNo: string
  amount: number
  paymentMethod: string
  accountType: string
  verificationStatus: string
  raw: PaymentRow | SupplierPaymentRow
}

type FundDimension = 'CUSTOMER' | 'SUPPLIER'

type FundRow = {
  dimension: FundDimension
  label: string
  accountBalance: number
  frozenAmount: number
  debtAmount: number
}

const route = useRoute()
const queryClient = useQueryClient()
const appStore = useAppStore()
const partnerId = Number(route.params.id)
const backPath = computed(() => returnPath(route, '/partners'))

const { data: partner } = useQuery({ queryKey: ['partner', partnerId], queryFn: () => api.partner(partnerId) })
const { data: contacts } = useQuery({ queryKey: ['contacts', partnerId], queryFn: () => api.contacts(partnerId) })
const { data: addresses } = useQuery({ queryKey: ['addresses', partnerId], queryFn: () => api.addresses(partnerId) })
const { data: bankAccounts } = useQuery({ queryKey: ['bank-accounts', partnerId], queryFn: () => api.bankAccounts(partnerId) })
const { data: followups } = useQuery({ queryKey: ['followups', partnerId], queryFn: () => api.followups(partnerId) })
const { data: specialDates } = useQuery({ queryKey: ['special-dates', partnerId], queryFn: () => api.specialDates(partnerId) })
const { data: partnerAccounts } = useQuery({ queryKey: ['partner-accounts', partnerId], queryFn: () => api.partnerAccounts(partnerId) })
const { data: inbounds } = useQuery({ queryKey: ['inbounds', 'partner', partnerId], queryFn: () => api.inbounds({ supplierId: partnerId }) })
const { data: outbounds } = useQuery({ queryKey: ['outbounds', 'partner', partnerId], queryFn: () => api.outbounds({ customerId: partnerId }) })
const { data: customerPayments } = useQuery({ queryKey: ['payments', 'partner', partnerId], queryFn: () => api.payments({ customerId: partnerId }) })
const { data: supplierPayments } = useQuery({ queryKey: ['supplier-payments', 'partner', partnerId], queryFn: () => api.supplierPayments({ supplierId: partnerId }) })
const { data: receivableReport } = useQuery({
  queryKey: ['settlement-report', 'receivable', 'partner', partnerId],
  queryFn: () => api.receivableReport({ partnerId, scope: 'open', pageNo: 1, pageSize: 1 }),
})
const { data: payableReport } = useQuery({
  queryKey: ['settlement-report', 'payable', 'partner', partnerId],
  queryFn: () => api.payableReport({ partnerId, scope: 'open', pageNo: 1, pageSize: 1 }),
})
const isCustomer = computed(() => Boolean(partner.value && (partner.value.typeMask & 1) === 1))
const isSupplier = computed(() => Boolean(partner.value && (partner.value.typeMask & 2) === 2))
const canManageSalesRisk = computed(() => appStore.hasPermission('sales.risk.policy.manage'))
const { data: salesRiskPolicy } = useQuery({
  queryKey: ['sales-risk-policy', partnerId],
  queryFn: () => api.salesRiskPolicy(partnerId),
  enabled: computed(() => isCustomer.value && canManageSalesRisk.value),
})
const fundRows = computed<FundRow[]>(() => {
  const rows: FundRow[] = []
  if (isCustomer.value) rows.push(makeFundRow('CUSTOMER'))
  if (isSupplier.value) rows.push(makeFundRow('SUPPLIER'))
  return rows
})
const recentStockRecords = computed<StockRecord[]>(() => {
  const records: StockRecord[] = []
  if (isSupplier.value) {
    records.push(
      ...(inbounds.value ?? [])
        .map((item) => ({
          id: item.id,
          recordType: 'INBOUND' as const,
          recordLabel: '入库',
          route: detailRoute(`/inbounds/${item.id}`),
          orderNo: item.orderNo,
          businessDate: item.businessDate,
          warehouseName: item.warehouseName,
          totalQuantity: item.totalQuantity,
          totalAmount: item.totalAmount,
          paidAmount: item.paidAmount,
          paymentStatus: item.paymentStatus,
          status: item.status,
        })),
    )
  }
  if (isCustomer.value) {
    records.push(
      ...(outbounds.value ?? [])
        .map((item) => ({
          id: item.id,
          recordType: 'OUTBOUND' as const,
          recordLabel: '出库',
          route: detailRoute(`/outbounds/${item.id}`),
          orderNo: item.orderNo,
          businessDate: item.businessDate,
          warehouseName: item.warehouseName,
          totalQuantity: item.totalQuantity,
          totalAmount: item.totalAmount,
          paidAmount: item.paidAmount,
          paymentStatus: item.paymentStatus,
          status: item.status,
        })),
    )
  }
  return sortRecent(records, (item) => item.businessDate).slice(0, 5)
})
const recentPaymentRecords = computed<PaymentRecord[]>(() => {
  const records: PaymentRecord[] = []
  if (isCustomer.value) {
    records.push(
      ...(customerPayments.value ?? []).map((item) => ({
        id: item.id,
        recordType: 'RECEIPT' as const,
        recordLabel: '回款',
        paymentNo: item.paymentNo,
        businessDate: item.receivedDate,
        sourceNo: [item.salesOrderNo, item.outboundNo].filter(Boolean).join(' / '),
        amount: item.amount,
        paymentMethod: item.paymentMethod,
        accountType: item.accountType,
        verificationStatus: item.verificationStatus,
        raw: item,
      })),
    )
  }
  if (isSupplier.value) {
    records.push(
      ...(supplierPayments.value ?? []).map((item) => ({
        id: item.id,
        recordType: 'PAYMENT' as const,
        recordLabel: '付款',
        paymentNo: item.paymentNo,
        businessDate: item.paidDate,
        sourceNo: [item.purchaseOrderNo, item.inboundNo].filter(Boolean).join(' / '),
        amount: item.amount,
        paymentMethod: item.paymentMethod,
        accountType: item.accountType,
        verificationStatus: item.verificationStatus,
        raw: item,
      })),
    )
  }
  return sortRecent(records, (item) => item.businessDate).slice(0, 5)
})

const contactDialogVisible = ref(false)
const paymentRecordDrawerVisible = ref(false)
const paymentRecordDrawerKind = ref<'RECEIPT' | 'PAYMENT'>('RECEIPT')
const activePaymentRecord = ref<PaymentRow | SupplierPaymentRow>()
const contactDetailVisible = ref(false)
const contactEditingId = ref<number>()
const contactDetailId = ref<number>()
const contactFormRef = ref<FormInstance>()
const contactForm = reactive<Partial<Contact>>({ partnerId, name: '', jobTitle: '', department: '', gender: 0, phone: '', wechat: '', email: '', preferredMethod: 'PHONE', firstChoice: false, birthday: null, memo: '', status: 'ENABLED' })
const contactRules: FormRules = { name: [{ required: true, message: '请填写联系人姓名', trigger: 'blur' }] }
const contactDetail = computed(() => (contacts.value ?? []).find((item) => item.id === contactDetailId.value))
const contactFollowups = computed(() => (followups.value ?? []).filter((item) => item.contactId === contactDetailId.value))
const contactSpecialDates = computed(() => (specialDates.value ?? []).filter((item) => item.contactId === contactDetailId.value))

const addressDialogVisible = ref(false)
const addressEditingId = ref<number>()
const addressFormRef = ref<FormInstance>()
const addressForm = reactive<Partial<Address>>({ partnerId, name: '', addressType: 'RECEIVING', detail: '', contactPerson: '', phone: '', firstChoice: false, memo: '', status: 'ENABLED' })
const addressRules: FormRules = {
  contactPerson: [{ required: true, message: '请填写联系人', trigger: 'blur' }],
  detail: [{ required: true, message: '请填写详细地址', trigger: 'blur' }],
}

const bankAccountDialogVisible = ref(false)
const bankAccountEditingId = ref<number>()
const bankAccountFormRef = ref<FormInstance>()
const bankAccountForm = reactive<Partial<BankAccount>>({ partnerId, accountName: '', bankName: '', bankAccount: '', firstChoice: false, status: 'ENABLED', memo: '' })
const bankAccountRules: FormRules = {
  accountName: [{ required: true, message: '请填写账户户名', trigger: 'blur' }],
  bankName: [{ required: true, message: '请填写开户行', trigger: 'blur' }],
  bankAccount: [{ required: true, message: '请填写银行账号', trigger: 'blur' }],
}

const followupDialogVisible = ref(false)
const followupEditingId = ref<number>()
const followupFormRef = ref<FormInstance>()
const followupForm = reactive<Partial<Followup>>({ partnerId, contactId: null, followupType: 'VISIT', methodType: 'VISIT', occurredAt: currentDateTimeText(), nextFollowDate: null, status: 'OPEN', memo: '' })
const followupRules: FormRules = {
  followupType: [{ required: true, message: '请选择沟通类型', trigger: 'change' }],
  occurredAt: [{ required: true, message: '请选择沟通时间', trigger: 'change' }],
}

const specialDateDialogVisible = ref(false)
const specialDateEditingId = ref<number>()
const specialDateFormRef = ref<FormInstance>()
const specialDateForm = reactive<Partial<SpecialDate>>({ partnerId, contactId: null, dateType: 'OTHER', title: '', specialDate: currentDateText(), repeatYearly: true, remindDaysBefore: 7, memo: '', status: 'ENABLED' })
const specialDateRules: FormRules = {
  dateType: [{ required: true, message: '请选择日期类型', trigger: 'change' }],
  title: [{ required: true, message: '请填写日期名称', trigger: 'blur' }],
  specialDate: [{ required: true, message: '请选择日期', trigger: 'change' }],
}

const salesRiskDialogVisible = ref(false)
const salesRiskForm = reactive<SalesRiskPolicySave>({
  enabled: false, creditControlEnabled: true, creditLimit: 0,
  overdueControlEnabled: true, maxOverdueDays: 0,
  marginControlEnabled: false, minimumMarginRate: 0, changeReason: '',
})
const salesRiskMutation = useMutation({
  mutationFn: (payload: SalesRiskPolicySave) => api.updateSalesRiskPolicy(partnerId, payload),
  onSuccess: async () => {
    ElMessage.success('销售风控策略新版本已生效')
    salesRiskDialogVisible.value = false
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['sales-risk-policy', partnerId] }),
      queryClient.invalidateQueries({ queryKey: ['partner', partnerId] }),
      queryClient.invalidateQueries({ queryKey: ['partner-summaries'] }),
    ])
  },
})
const salesRiskSaving = computed(() => salesRiskMutation.isPending.value)

function openSalesRiskPolicy() {
  if (!salesRiskPolicy.value) return
  Object.assign(salesRiskForm, {
    enabled: salesRiskPolicy.value.enabled,
    creditControlEnabled: salesRiskPolicy.value.creditControlEnabled,
    creditLimit: Number(salesRiskPolicy.value.creditLimit),
    overdueControlEnabled: salesRiskPolicy.value.overdueControlEnabled,
    maxOverdueDays: salesRiskPolicy.value.maxOverdueDays,
    marginControlEnabled: salesRiskPolicy.value.marginControlEnabled,
    minimumMarginRate: Number(salesRiskPolicy.value.minimumMarginRate),
    changeReason: '',
  })
  salesRiskDialogVisible.value = true
}

function saveSalesRiskPolicy() {
  if (salesRiskForm.enabled && !salesRiskForm.creditControlEnabled && !salesRiskForm.overdueControlEnabled && !salesRiskForm.marginControlEnabled) {
    ElMessage.warning('启用销售风控时至少选择一项控制')
    return
  }
  if (salesRiskForm.changeReason.trim().length < 4) {
    ElMessage.warning('请填写至少4个字的调整原因')
    return
  }
  salesRiskMutation.mutate({ ...salesRiskForm, changeReason: salesRiskForm.changeReason.trim() })
}

const contactMutation = useMutation({
  mutationFn: (payload: Partial<Contact>) => (contactEditingId.value ? api.updateContact(contactEditingId.value, payload) : api.createContact(payload)),
  onSuccess: async () => {
    ElMessage.success('联系人已保存')
    contactDialogVisible.value = false
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['contacts', partnerId] }),
      queryClient.invalidateQueries({ queryKey: ['partner', partnerId] }),
      queryClient.invalidateQueries({ queryKey: ['partner-summaries'] }),
    ])
  },
})
const addressMutation = useMutation({
  mutationFn: (payload: Partial<Address>) => (addressEditingId.value ? api.updateAddress(addressEditingId.value, payload) : api.createAddress(payload)),
  onSuccess: async () => {
    ElMessage.success('业务地址已保存')
    addressDialogVisible.value = false
    await queryClient.invalidateQueries({ queryKey: ['addresses', partnerId] })
  },
})
const bankAccountMutation = useMutation({
  mutationFn: (payload: Partial<BankAccount>) => (bankAccountEditingId.value ? api.updateBankAccount(bankAccountEditingId.value, payload) : api.createBankAccount(payload)),
  onSuccess: async () => {
    ElMessage.success('银行账户已保存')
    bankAccountDialogVisible.value = false
    await queryClient.invalidateQueries({ queryKey: ['bank-accounts', partnerId] })
  },
})
const followupMutation = useMutation({
  mutationFn: (payload: Partial<Followup>) => (followupEditingId.value ? api.updateFollowup(followupEditingId.value, payload) : api.createFollowup(payload)),
  onSuccess: async () => {
    ElMessage.success('沟通记录已保存')
    followupDialogVisible.value = false
    await queryClient.invalidateQueries({ queryKey: ['followups', partnerId] })
  },
})
const specialDateMutation = useMutation({
  mutationFn: (payload: Partial<SpecialDate>) => (specialDateEditingId.value ? api.updateSpecialDate(specialDateEditingId.value, payload) : api.createSpecialDate(payload)),
  onSuccess: async () => {
    ElMessage.success('特殊日期已保存')
    specialDateDialogVisible.value = false
    await queryClient.invalidateQueries({ queryKey: ['special-dates', partnerId] })
  },
})

function openContactCreate() {
  contactEditingId.value = undefined
  Object.assign(contactForm, { partnerId, name: '', jobTitle: '', department: '', gender: 0, phone: '', wechat: '', email: '', preferredMethod: 'PHONE', firstChoice: false, birthday: null, memo: '', status: 'ENABLED' })
  contactDialogVisible.value = true
}

function openRecentPayment(row: PaymentRecord) {
  paymentRecordDrawerKind.value = row.recordType
  activePaymentRecord.value = row.raw
  paymentRecordDrawerVisible.value = true
}

function openContactDetail(row: Contact) {
  contactDetailId.value = row.id
  contactDetailVisible.value = true
}

function openContactEdit(row: Contact) {
  contactEditingId.value = row.id
  Object.assign(contactForm, row)
  contactDialogVisible.value = true
}

function editContactFromDetail() {
  if (!contactDetail.value) return
  const row = contactDetail.value
  contactDetailVisible.value = false
  openContactEdit(row)
}

async function saveContact() {
  const valid = await contactFormRef.value?.validate().catch(() => false)
  if (!valid) return
  contactMutation.mutate({ ...contactForm, partnerId })
}

function openAddressCreate() {
  addressEditingId.value = undefined
  Object.assign(addressForm, { partnerId, name: '', addressType: 'RECEIVING', detail: '', contactPerson: '', phone: '', firstChoice: false, memo: '', status: 'ENABLED' })
  addressDialogVisible.value = true
}

function openAddressEdit(row: Address) {
  addressEditingId.value = row.id
  Object.assign(addressForm, row)
  addressDialogVisible.value = true
}

async function saveAddress() {
  const valid = await addressFormRef.value?.validate().catch(() => false)
  if (!valid) return
  addressMutation.mutate({ ...addressForm, partnerId })
}

function openBankAccountCreate() {
  bankAccountEditingId.value = undefined
  Object.assign(bankAccountForm, { partnerId, accountName: '', bankName: '', bankAccount: '', firstChoice: false, status: 'ENABLED', memo: '' })
  bankAccountDialogVisible.value = true
}

function openBankAccountEdit(row: BankAccount) {
  bankAccountEditingId.value = row.id
  Object.assign(bankAccountForm, row)
  bankAccountDialogVisible.value = true
}

async function saveBankAccount() {
  const valid = await bankAccountFormRef.value?.validate().catch(() => false)
  if (!valid) return
  bankAccountMutation.mutate({ ...bankAccountForm, partnerId })
}

function openFollowupCreate() {
  followupEditingId.value = undefined
  Object.assign(followupForm, { partnerId, contactId: null, followupType: 'VISIT', methodType: 'VISIT', occurredAt: currentDateTimeText(), nextFollowDate: null, status: 'OPEN', memo: '' })
  followupDialogVisible.value = true
}

function openFollowupEdit(row: Followup) {
  followupEditingId.value = row.id
  Object.assign(followupForm, row)
  followupDialogVisible.value = true
}

async function saveFollowup() {
  const valid = await followupFormRef.value?.validate().catch(() => false)
  if (!valid) return
  followupMutation.mutate({ ...followupForm, partnerId, contactId: followupForm.contactId || null })
}

function openSpecialDateCreate() {
  specialDateEditingId.value = undefined
  Object.assign(specialDateForm, { partnerId, contactId: null, dateType: 'OTHER', title: '', specialDate: currentDateText(), repeatYearly: true, remindDaysBefore: 7, memo: '', status: 'ENABLED' })
  specialDateDialogVisible.value = true
}

function openSpecialDateEdit(row: SpecialDate) {
  specialDateEditingId.value = row.id
  Object.assign(specialDateForm, row)
  specialDateDialogVisible.value = true
}

async function saveSpecialDate() {
  const valid = await specialDateFormRef.value?.validate().catch(() => false)
  if (!valid) return
  specialDateMutation.mutate({ ...specialDateForm, partnerId, contactId: specialDateForm.contactId || null })
}

function typeName(row: Partner) {
  if ((row.typeMask & 1) === 1 && (row.typeMask & 2) === 2) return '客户，供应商'
  return (row.typeMask & 2) === 2 ? '供应商' : '客户'
}

function entityTypeName(value: string) {
  const names: Record<string, string> = { COMPANY: '企业', SOLE_PROPRIETOR: '个体工商户', INDIVIDUAL: '个人' }
  return names[value] ?? value
}

function settlementText(method: string, days: number) {
  const names: Record<string, string> = { PER_ORDER: '一单一结', CREDIT_TERM: '账期结算', CASH: '现款现货', MONTHLY: '月结', PERIOD: '账期', PREPAY: '预付款', PREPAID: '预付', OTHER: '其他' }
  const label = names[method] ?? method
  return [label, days > 0 ? `${days} 天` : ''].filter(Boolean).join(' / ') || '-'
}

function invoiceText(taxIncluded: boolean, invoiceType: string, taxRate: number) {
  const names: Record<string, string> = { NONE: '不开票', NORMAL: '普票', VAT_NORMAL: '增值税普通发票', SPECIAL: '专票', VAT_SPECIAL: '增值税专用发票', OTHER: '其他' }
  const rate = Number(taxRate || 0)
  return [taxIncluded ? '含税' : '未含税', names[invoiceType] ?? invoiceType, rate > 0 ? `${rate}%` : ''].filter(Boolean).join(' / ')
}

function genderName(value: number) {
  if (value === 1) return '男'
  if (value === 2) return '女'
  return '-'
}

function followupTypeName(value: string) {
  const names: Record<string, string> = { DEVELOP_CUSTOMER: '开发客户', VISIT: '拜访', PHONE: '电话', WECHAT: '微信', EMAIL: '邮件', OTHER: '其他' }
  return names[value] ?? value
}

function methodTypeName(value: string) {
  const names: Record<string, string> = { VISIT: '面谈', PHONE: '电话', WECHAT: '微信', EMAIL: '邮件', OTHER: '其他' }
  return (names[value] ?? value) || '-'
}

function followupStatusName(value: string) {
  return value === 'COMPLETED' ? '已完成' : '待跟进'
}

function addressTypeName(value: string) {
  const names: Record<string, string> = { RECEIVING: '收货', SHIPPING: '送货', PICKUP: '自提', INVOICE: '开票', REGISTERED: '注册', OFFICE: '办公', OTHER: '其他' }
  return names[value] ?? value
}

function specialDateTypeName(value: string) {
  const names: Record<string, string> = { BIRTHDAY: '生日', ESTABLISHMENT: '成立日', ANNIVERSARY: '纪念日', CONTRACT: '合同日', OTHER: '其他' }
  return names[value] ?? value
}

function makeFundRow(dimension: FundDimension): FundRow {
  const accounts = accountsByDimension(dimension)
  return {
    dimension,
    label: dimension === 'CUSTOMER' ? '客户' : '供应商',
    accountBalance: sumAmount(accounts, (item) => item.balance),
    frozenAmount: sumAmount(accounts, (item) => item.frozenAmount),
    debtAmount:
      dimension === 'CUSTOMER'
        ? Number(receivableReport.value?.summary.unpaidAmount ?? 0)
        : Number(payableReport.value?.summary.unpaidAmount ?? 0),
  }
}

function accountsByDimension(dimension: FundDimension) {
  const accountPrefix = dimension === 'CUSTOMER' ? 'CUSTOMER_' : 'SUPPLIER_'
  return (partnerAccounts.value ?? []).filter((item) => item.partnerType === dimension || item.accountType?.startsWith(accountPrefix))
}

function sumAmount<T>(rows: T[] | undefined, selector: (item: T) => number | string | null | undefined) {
  return (rows ?? []).reduce((total, item) => total + Number(selector(item) ?? 0), 0)
}

function sortRecent<T extends { id: number }>(rows: T[], dateSelector: (item: T) => string | null | undefined) {
  return [...rows].sort((a, b) => {
    const dateDiff = dateTime(dateSelector(b)) - dateTime(dateSelector(a))
    return dateDiff || b.id - a.id
  })
}

function detailRoute(path: string): RouteLocationRaw {
  return relatedDetailRoute(route, path)
}

function dateTime(value: string | null | undefined) {
  if (!value) return 0
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? 0 : parsed
}

function businessStatusName(value: string) {
  const names: Record<string, string> = {
    PENDING: '待审核',
    STOCKED: '已入库',
    CANCELLED: '已取消',
    OUT: '已出完',
    AVAILABLE: '可用',
  }
  return names[value] ?? statusName(value)
}

function verificationStatusName(value: string) {
  const names: Record<string, string> = {
    VERIFIED: '已核销',
    UNAPPLIED: '未核销',
  }
  return names[value] ?? statusName(value)
}

function contactOptionLabel(item: Contact) {
  return [item.name, item.phone ? formatPhone(item.phone) : ''].filter(Boolean).join(' / ')
}
</script>
