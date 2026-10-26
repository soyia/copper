<template>
  <div class="page" v-if="detail">
    <div class="panel">
      <PageHeader :title="`销售订单 ${detail.orderNo}`" :back-to="backPath">
        <el-button v-if="detail.status !== 'CANCELLED'" type="primary" plain @click="printSalesOrder">打印销售订单</el-button>
        <el-button v-if="Number(detail.receivableAmount) > 0" plain @click="$router.push({ path: '/receivable-adjustments', query: { salesOrderId: id } })">应收调整</el-button>
        <el-button v-if="Number(detail.receivableAmount) > 0" plain @click="$router.push({ path: '/acceptance-bills', query: { salesOrderId: id } })">登记承兑</el-button>
        <el-button v-if="['OPEN', 'PARTIAL_SHIPPED'].includes(detail.status)" type="primary" @click="$router.push({ path: '/outbounds/new', query: { salesOrderId: id } })">安排出库</el-button>
        <el-button v-if="detail.status === 'DRAFT'" @click="$router.push(`/sales-orders/${id}/edit`)">编辑草稿</el-button>
        <el-button v-if="['OPEN', 'PARTIAL_SHIPPED'].includes(detail.status) && appStore.hasPermission('sales.amend')" type="primary" plain @click="$router.push(`/sales-orders/${id}/amend`)">变更订单</el-button>
        <el-button v-if="['DRAFT', 'OPEN'].includes(detail.status)" type="danger" plain :loading="cancelling" @click="cancelOrder">{{ detail.status === 'DRAFT' ? '作废草稿' : '取消订单' }}</el-button>
        <el-button
          v-if="detail.status === 'DRAFT'"
          type="primary"
          :loading="confirming"
          :disabled="Boolean(confirmBlockedReason)"
          :title="confirmBlockedReason"
          @click="confirmOrder"
        >确认销售</el-button>
      </PageHeader>
      <div class="panel-body">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="客户">{{ detail.customerName }}</el-descriptions-item>
          <el-descriptions-item label="标题">{{ detail.title || '-' }}</el-descriptions-item>
          <el-descriptions-item label="订单日期">{{ formatDate(detail.businessDate) }}</el-descriptions-item>
          <el-descriptions-item label="计划交付">{{ formatDate(detail.deliveryPlanDate) }}</el-descriptions-item>
          <el-descriptions-item label="履约仓库">{{ detail.fulfillmentWarehouseName || '确认前待指定' }}</el-descriptions-item>
          <el-descriptions-item label="收货地址" :span="2">{{ formatPhone(detail.addressSnapshot) }}</el-descriptions-item>
          <el-descriptions-item label="已发货应收">{{ money(detail.receivableAmount) }}</el-descriptions-item>
          <el-descriptions-item label="回款/票据核销">{{ money(detail.receivedAmount) }}</el-descriptions-item>
          <el-descriptions-item label="应收调整">{{ money(detail.adjustedAmount) }}</el-descriptions-item>
          <el-descriptions-item label="待收金额">{{ money(detail.outstandingAmount) }}</el-descriptions-item>
          <el-descriptions-item label="实收资金">{{ money(detail.cashReceivedAmount) }}</el-descriptions-item>
          <el-descriptions-item label="价格口径">{{ detail.priceIncludesTax ? '含税价' : '未税价' }} / {{ detail.taxRateSummary }}</el-descriptions-item>
          <el-descriptions-item label="未税金额">{{ money(detail.amountExcludingTax) }}</el-descriptions-item>
          <el-descriptions-item label="税额">{{ money(detail.taxAmount) }}</el-descriptions-item>
          <el-descriptions-item label="结清状态">
            <el-tag v-if="['DRAFT', 'CANCELLED'].includes(detail.status)" type="info" size="small">不适用</el-tag>
            <el-tag v-else :type="paymentStatusType(detail.paymentStatus)" size="small">{{ paymentStatus(detail.paymentStatus) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="状态"><el-tag :type="salesOrderStatusType(detail.status)" size="small">{{ salesOrderStatus(detail.status) }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="收款条件">{{ paymentTermName(detail.paymentTerm) }}</el-descriptions-item>
          <el-descriptions-item label="发货前到账">{{ shipmentPaymentPolicyName(detail.shipmentPaymentPolicy) }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.paymentTerm !== 'POSTPAID'" label="要求预付">{{ money(detail.requiredDepositAmount) }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.paymentTerm !== 'POSTPAID'" label="已落实预付">{{ money(detail.reservedPrepaymentAmount) }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="3">{{ detail.memo || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.status === 'CANCELLED'" label="作废原因" :span="2">{{ detail.cancelReason || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.status === 'CANCELLED'" label="作废时间">{{ formatDateTime(detail.cancelledAt) }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </div>

    <SalesRiskControlPanel document-type="SALES_ORDER" :document-id="id" />
    <AtpOrderPanel :order-id="id" :status="detail.status" />

    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">发货进度</div>
        <el-tag :type="fulfillmentStatus.type" size="small">{{ fulfillmentStatus.label }}</el-tag>
      </div>
      <div class="panel-body">
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="订单数量">{{ quantity(fulfillmentTotals.ordered) }}</el-descriptions-item>
          <el-descriptions-item label="已发数量">{{ quantity(fulfillmentTotals.shipped) }}</el-descriptions-item>
          <el-descriptions-item label="待发数量">{{ quantity(fulfillmentTotals.remaining) }}</el-descriptions-item>
          <el-descriptions-item label="关联出库单">{{ relatedOutbounds?.length ?? 0 }} 张</el-descriptions-item>
        </el-descriptions>
      </div>
      <el-table :data="detail.items" class="data-table" size="small" border show-overflow-tooltip max-height="260">
        <el-table-column prop="sku" label="SKU" width="150" fixed="left" />
        <el-table-column prop="productName" label="商品" min-width="180" />
        <el-table-column prop="spec" label="规格摘要" min-width="220" />
        <el-table-column label="订单数量" width="130" align="right"><template #default="{ row }">{{ quantity(row.quantity) }} {{ row.baseUnit || row.unit }}</template></el-table-column>
        <el-table-column label="已发数量" width="130" align="right"><template #default="{ row }">{{ quantity(row.shippedQuantity) }} {{ row.baseUnit || row.unit }}</template></el-table-column>
        <el-table-column label="待发数量" width="130" align="right"><template #default="{ row }">{{ quantity(Math.max(0, Number(row.quantity) - Number(row.shippedQuantity))) }} {{ row.baseUnit || row.unit }}</template></el-table-column>
      </el-table>
      <div class="panel-header fulfillment-outbound-header"><div class="panel-title">关联出库单</div></div>
      <el-table :data="relatedOutbounds ?? []" class="data-table" size="small" border show-overflow-tooltip max-height="240">
        <el-table-column label="出库单号" min-width="190" fixed="left">
          <template #default="{ row }"><RouterLink class="business-link" :to="outboundRoute(row)">{{ row.orderNo }}</RouterLink></template>
        </el-table-column>
        <el-table-column prop="warehouseName" label="仓库" min-width="130" />
        <el-table-column prop="businessDate" label="出库日期" width="120" :formatter="formatDateColumn" />
        <el-table-column label="出库数量" width="130" align="right"><template #default="{ row }">{{ quantity(row.totalQuantity) }}</template></el-table-column>
        <el-table-column label="金额" width="130" align="right"><template #default="{ row }">{{ money(row.totalAmount) }}</template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{ row }">{{ outboundStatusName(row.status) }}</template></el-table-column>
      </el-table>
      <el-empty v-if="!(relatedOutbounds?.length)" description="尚未生成出库单" :image-size="54" />
    </div>

    <div class="panel">
      <div class="panel-header">
        <div><div class="panel-title">订单版本与承诺过账</div><div class="muted">已确认订单的变更不会覆盖历史；系统先冲回原承诺，再按新版本重新过账。</div></div>
      </div>
      <el-table :data="revisions || []" border size="small" class="data-table" empty-text="暂无版本记录">
        <el-table-column label="版本" width="90"><template #default="{ row }">V{{ row.revisionNo }}</template></el-table-column>
        <el-table-column label="类型" width="120"><template #default="{ row }">{{ revisionTypeName(row.changeType) }}</template></el-table-column>
        <el-table-column prop="changeReason" label="原因" min-width="260" />
        <el-table-column prop="operatorName" label="操作人" width="120" />
        <el-table-column label="时间" width="175"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
        <el-table-column label="过账批次" min-width="220">
          <template #default="{ row }">
            <RouterLink v-if="row.postedBatchId && appStore.hasPermission('posting.view')" class="business-link" :to="`/business-postings/${row.postedBatchId}`">新版本过账</RouterLink><span v-else-if="row.postedBatchId">已过账</span>
            <span v-if="row.postedBatchId && row.reversedPostingBatchId"> · </span>
            <RouterLink v-if="row.reversedPostingBatchId && appStore.hasPermission('posting.view')" class="business-link" :to="`/business-postings/${row.reversedPostingBatchId}`">原版本冲回</RouterLink><span v-else-if="row.reversedPostingBatchId">原版本已冲回</span>
            <span v-if="!row.postedBatchId && !row.reversedPostingBatchId">-</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="detail.paymentTerm !== 'POSTPAID' || detail.shipmentPaymentPolicy !== 'NONE'" class="panel">
      <div class="panel-header">
        <div class="panel-title">订单预付款</div>
        <div v-if="prepaymentEligible" class="panel-actions">
          <el-button :disabled="Number(detail.customerAdvanceAvailable) <= 0" @click="openReserveDialog">使用预收余额</el-button>
          <el-button type="primary" @click="openReceiveDialog">登记新预付款</el-button>
        </div>
      </div>
      <div class="panel-body">
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="订单金额">{{ money(detail.totalAmount) }}</el-descriptions-item>
          <el-descriptions-item label="要求预付">{{ money(detail.requiredDepositAmount) }}</el-descriptions-item>
          <el-descriptions-item :label="detail.status === 'DRAFT' ? '已占用' : '已落实（含待用）'">{{ money(detail.reservedPrepaymentAmount) }}</el-descriptions-item>
          <el-descriptions-item label="尚差">
            <strong :class="Number(detail.prepaymentShortfall) > 0 ? 'text-danger' : 'text-success'">{{ money(detail.prepaymentShortfall) }}</strong>
          </el-descriptions-item>
          <el-descriptions-item label="客户预收余额">{{ money(detail.customerAdvanceBalance) }}</el-descriptions-item>
          <el-descriptions-item label="其他订单已占用">{{ money(Math.max(0, Number(detail.customerAdvanceBalance) - Number(detail.customerAdvanceAvailable) - Number(detail.reservedPrepaymentAmount))) }}</el-descriptions-item>
          <el-descriptions-item label="当前可用">{{ money(detail.customerAdvanceAvailable) }}</el-descriptions-item>
          <el-descriptions-item label="确认条件">{{ Number(detail.prepaymentShortfall) > 0 ? '未满足' : '已满足' }}</el-descriptions-item>
          <el-descriptions-item label="发货控制">{{ shipmentPaymentPolicyName(detail.shipmentPaymentPolicy) }}</el-descriptions-item>
          <el-descriptions-item label="放货要求资金">{{ money(detail.shipmentRequiredAmount) }}</el-descriptions-item>
          <el-descriptions-item label="已落实放货资金">{{ money(detail.shipmentFundedAmount) }}</el-descriptions-item>
          <el-descriptions-item label="当前放货差额">
            <strong :class="Number(detail.shipmentPaymentShortfall) > 0 ? 'text-danger' : 'text-success'">{{ money(detail.shipmentPaymentShortfall) }}</strong>
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <el-table :data="prepaymentSummary?.allocations ?? []" class="data-table" size="small" border show-overflow-tooltip max-height="220">
        <el-table-column prop="createdAt" label="时间" width="170" :formatter="formatDateTimeColumn" />
        <el-table-column label="来源类型" width="150">
          <template #default="{ row }">{{ row.sourcePaymentId ? '新收预付款' : '已有预收余额' }}</template>
        </el-table-column>
        <el-table-column label="来源单号" width="210">
          <template #default="{ row }">
            <el-button
              v-if="row.sourcePaymentId"
              link
              type="primary"
              :loading="sourcePaymentLoadingId === row.sourcePaymentId"
              @click="openSourcePaymentDetail(row)"
            >{{ row.sourcePaymentNo }}</el-button>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="130" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
        <el-table-column label="订单使用状态" width="130"><template #default="{ row }">{{ allocationStatusName(row.status) }}</template></el-table-column>
        <el-table-column prop="memo" label="备注" min-width="180" />
        <el-table-column v-if="prepaymentEligible" label="操作" width="90" fixed="right">
          <template #default="{ row }"><el-button v-if="row.status === 'RESERVED'" text type="danger" @click="releaseAllocation(row.id)">释放</el-button></template>
        </el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">商品明细</div>
        <el-segmented v-model="itemViewMode" :options="documentItemViewOptions" size="small" />
      </div>
      <div class="order-total-bar">
        <span>合计金额：{{ money(detail.originalAmount) }}</span>
        <span>折扣比例：{{ percentage(detail.discountRate) }}</span>
        <span>折扣金额：-{{ money(detail.discountAmount) }}</span>
        <span>调整金额：{{ signedMoney(detail.adjustmentAmount) }}</span>
        <span>未税：{{ money(detail.amountExcludingTax) }}</span>
        <span>税额：{{ money(detail.taxAmount) }}</span>
        <span class="order-total-final">实际金额：{{ money(detail.totalAmount) }}</span>
      </div>
      <DocumentItemCompactTable v-if="itemViewMode === 'COMPACT'" :items="detail.items" kind="SALES" />
      <DocumentItemMultilineView v-else-if="itemViewMode === 'MULTILINE'" :items="detail.items" kind="SALES" />
      <el-table v-else :data="detail.items" class="data-table" size="small" border show-overflow-tooltip max-height="calc(100vh - 475px)">
        <el-table-column prop="sku" label="SKU" width="150" fixed="left" />
        <el-table-column label="商品" min-width="220" fixed="left">
          <template #default="{ row }">{{ productTitle(row) }}</template>
        </el-table-column>
        <el-table-column prop="spec" label="规格摘要" min-width="260" />
        <el-table-column label="数量" width="120" align="right"><template #default="{ row }">{{ quantity(row.quantity) }}</template></el-table-column>
        <el-table-column prop="priceUnit" label="计价单位" width="100" />
        <el-table-column label="预计包装" width="120"><template #default="{ row }">{{ quantity(row.packageQuantity) }} {{ row.packageUnit || '-' }}</template></el-table-column>
        <el-table-column label="计量包装快照" min-width="210">
          <template #default="{ row }"><PackagingSnapshotCell :snapshot-json="row.packagingPlanJson" :requested-quantity="row.requestedQuantity" :requested-unit="row.requestedUnit" :quantity="row.quantity" :base-unit="row.baseUnit || row.unit" :pricing-quantity="row.pricingQuantity" :price-unit="row.priceUnit" :snapshot-hash="row.packagingSnapshotHash" /></template>
        </el-table-column>
        <el-table-column label="赠品" width="80"><template #default="{ row }">{{ row.noCharge ? '是' : '否' }}</template></el-table-column>
        <el-table-column label="单价 / 来源" width="170" align="right">
          <template #default="{ row }">
            <div class="price-audit-cell">
              <span>{{ money(row.unitPrice, 4) }}</span>
              <span class="price-audit-source" :title="row.priceSnapshotJson || row.priceSourceName">{{ row.priceSourceName || '手工录入' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="税率" width="90" align="right"><template #default="{ row }">{{ row.taxRate }}%</template></el-table-column>
        <el-table-column label="未税" width="120" align="right"><template #default="{ row }">{{ money(row.amountExcludingTax) }}</template></el-table-column>
        <el-table-column label="税额" width="110" align="right"><template #default="{ row }">{{ money(row.taxAmount) }}</template></el-table-column>
        <el-table-column label="结算金额" width="130" align="right" fixed="right"><template #default="{ row }">{{ money(row.settlementTotalAmount) }}</template></el-table-column>
      </el-table>
    </div>

    <div v-if="!['DRAFT', 'CANCELLED'].includes(detail.status)" class="panel">
      <div class="panel-header">
        <div class="panel-title">回款情况</div>
        <el-button type="primary" :disabled="remainingAmount <= 0" @click="paymentDialogVisible = true">收款</el-button>
      </div>
      <div class="panel-body">
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="已发货应收">{{ money(detail.receivableAmount) }}</el-descriptions-item>
          <el-descriptions-item label="回款/票据">{{ money(detail.receivedAmount) }}</el-descriptions-item>
          <el-descriptions-item label="应收调整">{{ money(detail.adjustedAmount) }}</el-descriptions-item>
          <el-descriptions-item label="待收金额">{{ money(remainingAmount) }}</el-descriptions-item>
          <el-descriptions-item label="实收资金">{{ money(detail.cashReceivedAmount) }}</el-descriptions-item>
          <el-descriptions-item label="结清状态">
            <el-tag :type="paymentStatusType(detail.paymentStatus)" size="small">{{ paymentStatus(detail.paymentStatus) }}</el-tag>
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <el-table :data="payments ?? []" class="data-table" size="small" border show-overflow-tooltip max-height="220">
        <el-table-column label="回款单号" width="190" fixed="left"><template #default="{ row }"><el-button link type="primary" @click="openPaymentRecord(row)">{{ row.paymentNo }}</el-button></template></el-table-column>
        <el-table-column prop="receivedDate" label="日期" width="110" :formatter="formatDateColumn" />
        <el-table-column label="金额" width="130" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
        <el-table-column label="方式" width="130"><template #default="{ row }">{{ paymentMethodName(row.paymentMethod) }}</template></el-table-column>
        <el-table-column label="账户" width="130"><template #default="{ row }">{{ accountTypeName(row.accountType) }}</template></el-table-column>
        <el-table-column label="核销状态" width="110"><template #default="{ row }">{{ verificationStatusName(row.verificationStatus) }}</template></el-table-column>
      </el-table>
    </div>

    <div v-else-if="detail.status === 'DRAFT'" class="panel">
      <div class="panel-body">
        <el-alert title="当前订单为暂存；确认销售只形成待发承诺，不扣库存、不形成应收。实际出库后按发货金额形成应收。" type="info" :closable="false" show-icon />
      </div>
    </div>

    <div v-else class="panel">
      <div class="panel-body">
        <el-alert :title="`该草稿已作废，不会提交给客户。原因：${detail.cancelReason || '-'}`" type="info" :closable="false" show-icon />
      </div>
    </div>

    <el-dialog v-model="paymentDialogVisible" title="回款核销" width="920px">
      <PaymentForm
        v-if="detail"
        compact
        lock-source
        show-cancel
        submit-label="保存回款"
        :initial="{ customerId: detail.customerId, sourceType: 'SALES_ORDER', sourceId: id }"
        :balance-amount="remainingAmount"
        :prefill-source-amount="false"
        @cancel="paymentDialogVisible = false"
        @success="handlePaymentSuccess"
      />
    </el-dialog>

    <el-dialog v-model="receiveDialogVisible" title="登记订单预付款" width="520px">
      <el-form label-width="92px">
        <el-form-item label="本次收款" required><el-input v-model="receiveForm.amount" inputmode="decimal" placeholder="请输入到账金额" /></el-form-item>
        <el-form-item label="结算方式" required><el-select v-model="receiveForm.paymentMethod" placeholder="请选择结算方式"><el-option v-for="item in cashSettlementMethodOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item v-if="receiveForm.paymentMethod === 'BANK'" label="收款账户" required><BusinessBankAccountSelect v-model="receiveForm.businessBankAccountId" /></el-form-item>
        <el-form-item label="到账日期" required><el-date-picker v-model="receiveForm.receivedDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="receiveForm.memo" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="receiveDialogVisible = false">取消</el-button><el-button type="primary" :loading="receivingPrepayment" @click="receivePrepayment">保存预付款</el-button></template>
    </el-dialog>

    <el-dialog v-model="reserveDialogVisible" title="使用客户预收余额" width="500px">
      <el-alert :title="`当前可用预收余额 ${money(detail.customerAdvanceAvailable)}，订单尚差 ${money(detail.prepaymentShortfall)}`" type="info" :closable="false" style="margin-bottom: 14px" />
      <el-form label-width="92px">
        <el-form-item label="占用金额">
          <el-input v-model="reserveForm.amount" inputmode="decimal"><template #append><el-button @click="fillReserveAmount">全部可用</el-button></template></el-input>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="reserveForm.memo" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="reserveDialogVisible = false">取消</el-button><el-button type="primary" :loading="reservingPrepayment" @click="reservePrepayment">确认占用</el-button></template>
    </el-dialog>

    <RelatedDetailDrawer
      v-model="sourcePaymentDrawerVisible"
      :title="sourcePaymentDrawerTitle"
      :subtitle="sourcePaymentDrawerSubtitle"
      :trail="sourcePaymentTrail"
      :show-back="sourcePaymentDrawerPage === 'APPLIED'"
      size="900px"
      @back="sourcePaymentDrawerPage = 'SOURCE'"
      @breadcrumb="handleSourcePaymentBreadcrumb"
    >
      <el-descriptions v-if="activeSourcePaymentDetail" class="fixed-detail-descriptions" :column="2" label-width="140px" border>
        <el-descriptions-item label="回款单号"><span class="related-record-no">{{ activeSourcePaymentDetail.paymentNo }}</span></el-descriptions-item>
        <el-descriptions-item label="客户">{{ activeSourcePaymentDetail.customerName }}</el-descriptions-item>
        <el-descriptions-item label="金额">{{ money(activeSourcePaymentDetail.amount) }}</el-descriptions-item>
        <el-descriptions-item label="结算方式">{{ paymentMethodName(activeSourcePaymentDetail.paymentMethod) }}</el-descriptions-item>
        <el-descriptions-item label="收款账户">{{ accountTypeName(activeSourcePaymentDetail.accountType) }}</el-descriptions-item>
        <el-descriptions-item label="到账日期">{{ formatDate(activeSourcePaymentDetail.receivedDate) }}</el-descriptions-item>
        <template v-if="sourcePaymentDrawerPage === 'SOURCE'">
          <el-descriptions-item label="到账处理">{{ sourcePaymentHandlingName(activeSourcePaymentDetail) }}</el-descriptions-item>
          <el-descriptions-item label="使用订单">{{ detail.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="订单使用状态">{{ allocationStatusName(sourcePaymentAllocation?.status || '') }}</el-descriptions-item>
          <el-descriptions-item label="应收核销单号" :span="2">
            <el-button v-if="appliedPaymentDetail" link type="primary" @click="openAppliedPaymentDetail">{{ appliedPaymentDetail.paymentNo }}</el-button>
            <span v-else>-</span>
          </el-descriptions-item>
        </template>
        <template v-else>
          <el-descriptions-item label="应收核销状态">{{ verificationStatusName(activeSourcePaymentDetail.verificationStatus) }}</el-descriptions-item>
          <el-descriptions-item label="核销销售订单"><span class="related-record-no">{{ activeSourcePaymentDetail.salesOrderNo || detail.orderNo }}</span></el-descriptions-item>
          <el-descriptions-item label="核销来源">客户预收款</el-descriptions-item>
        </template>
      </el-descriptions>
      <template #footer>
        <el-button @click="sourcePaymentDrawerVisible = false">返回销售订单</el-button>
        <el-button v-if="sourcePaymentDrawerPage === 'APPLIED'" @click="sourcePaymentDrawerPage = 'SOURCE'">返回来源回款</el-button>
        <el-button v-else-if="appliedPaymentDetail" type="primary" @click="openAppliedPaymentDetail">查看应收核销</el-button>
      </template>
    </RelatedDetailDrawer>

    <PaymentRecordDrawer v-model="paymentRecordDrawerVisible" kind="RECEIPT" :record="activePaymentRecord" :origin-label="`销售订单 ${detail.orderNo}`" />

    <section class="print-sheet dot-print-sheet sales-order-print-sheet">
      <div class="sales-order-print-heading">
        <span class="dot-print-company">{{ appStore.settings.companyName }}</span>
        <span class="dot-print-title">销售订单{{ detail.status === 'DRAFT' ? '（草稿）' : '' }}</span>
      </div>
      <div class="dot-print-subtitle">
        <span v-if="appStore.settings.companyPhone">供方电话：{{ appStore.settings.companyPhone }}</span>
        <span>打印时间：{{ printTime }}</span>
      </div>

      <table class="dot-print-meta">
        <tbody>
          <tr>
            <td>订单编号：{{ detail.orderNo }}</td>
            <td>订单日期：{{ formatDate(detail.businessDate) }}</td>
            <td>交货日期：{{ formatDate(detail.deliveryPlanDate) }}</td>
          </tr>
          <tr>
            <td>购方：{{ detail.customerName }}</td>
            <td>订单标题：{{ detail.title || '-' }}</td>
            <td>制单人：{{ appStore.currentUserName }}</td>
          </tr>
          <tr>
            <td colspan="3">交货地址：{{ printAddress(detail.addressSnapshot) }}</td>
          </tr>
        </tbody>
      </table>

      <table class="dot-print-table sales-order-print-table">
        <thead>
          <tr>
            <th>序号</th>
            <th>商品</th>
            <th>规格</th>
            <th>包装</th>
            <th>计价数量</th>
            <th>单位</th>
            <th>单价</th>
            <th>金额</th>
            <th>备注</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in salesOrderPrintRows" :key="row.key">
            <td class="center">{{ index + 1 }}</td>
            <td>{{ row.product }}</td>
            <td>{{ row.spec }}</td>
            <td class="center">{{ row.packaging }}</td>
            <td class="right">{{ quantity(row.pricingQuantity, 6) }}</td>
            <td class="center">{{ row.priceUnit }}</td>
            <td class="right">{{ money(row.unitPrice, 4) }}</td>
            <td class="right">{{ money(row.amount) }}</td>
            <td>{{ row.memo }}</td>
          </tr>
        </tbody>
      </table>

      <table class="dot-print-total">
        <tbody>
          <tr>
            <td>原金额：{{ money(detail.originalAmount) }}</td>
            <td>优惠/调整：{{ signedMoney(detail.adjustmentAmount - detail.discountAmount) }}</td>
            <td>订单金额：{{ money(detail.totalAmount) }}</td>
          </tr>
          <tr>
            <td colspan="3">金额大写：{{ uppercaseRmb(detail.totalAmount) }}</td>
          </tr>
          <tr>
            <td colspan="3">约定说明：本订单按上述商品、包装及交付信息执行；如有变更，以双方确认的书面记录为准。</td>
          </tr>
          <tr>
            <td colspan="3">备注：{{ detail.memo || '无' }}</td>
          </tr>
        </tbody>
      </table>

      <div class="dot-print-signatures sales-order-print-signatures">
        <span>供方经办：</span>
        <span>购方经办：</span>
        <span>审核：</span>
        <span>盖章：</span>
      </div>
    </section>
    <SalesContributionPanel
      v-if="!['DRAFT', 'CANCELLED'].includes(detail.status) && appStore.hasPermission('operations.insight.view')"
      source-type="SALES_ORDER"
      :source-id="id"
    />
    <ExternalConfirmationPanel
      v-if="detail.status !== 'CANCELLED'"
      packet-type="DELIVERY_SUMMARY"
      :source-id="id"
    />
    <DocumentSharePanel
      v-if="detail.status !== 'CANCELLED' && appStore.hasPermission('external.share.manage')"
      source-type="SALES_ORDER"
      :source-id="id"
    />
    <EvidenceAttachments
      document-type="SALES_ORDER"
      :document-id="id"
      title="客户确认、合同与订单签字凭据"
      default-evidence-type="ORIGINAL_DOCUMENT"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type OutboundRow, type PaymentRow, type SalesOrderAdvanceAllocation, type SalesOrderItemDetail } from '@/api/client'
import { accountTypeName, currentDateText, formatDate, formatDateColumn, formatDateTime, formatDateTimeColumn, formatPhone, money, paymentMethodName, paymentStatus, paymentStatusType, quantity, salesOrderStatus, salesOrderStatusType } from '@/api/format'
import PageHeader from '@/components/PageHeader.vue'
import PaymentForm from '@/components/PaymentForm.vue'
import PackagingSnapshotCell from '@/components/PackagingSnapshotCell.vue'
import RelatedDetailDrawer from '@/components/RelatedDetailDrawer.vue'
import PaymentRecordDrawer from '@/components/PaymentRecordDrawer.vue'
import BusinessBankAccountSelect from '@/components/BusinessBankAccountSelect.vue'
import DocumentItemMultilineView from '@/components/DocumentItemMultilineView.vue'
import DocumentItemCompactTable from '@/components/DocumentItemCompactTable.vue'
import EvidenceAttachments from '@/components/EvidenceAttachments.vue'
import SalesContributionPanel from '@/components/SalesContributionPanel.vue'
import DocumentSharePanel from '@/components/DocumentSharePanel.vue'
import ExternalConfirmationPanel from '@/components/ExternalConfirmationPanel.vue'
import SalesRiskControlPanel from '@/components/SalesRiskControlPanel.vue'
import AtpOrderPanel from '@/components/AtpOrderPanel.vue'
import { useDocumentItemView } from '@/composables/useDocumentItemView'
import { printFileName, useDocumentPrint } from '@/composables/useDocumentPrint'
import { returnPath } from '@/utils/navigation'
import { useAppStore } from '@/stores/app'
import { cashSettlementMethodOptions, type SettlementMethod } from '@/utils/settlementMethods'
import { relatedDetailRoute } from '@/utils/navigation'
import { resolveSalesRiskOverride } from '@/utils/salesRisk'

const route = useRoute()
const queryClient = useQueryClient()
const appStore = useAppStore()
const id = Number(route.params.id)
const { data: detail, refetch } = useQuery({ queryKey: ['sales-order-detail', id], queryFn: () => api.salesOrderDetail(id) })
const { data: payments } = useQuery({ queryKey: ['payments', 'sales-order', id], queryFn: () => api.payments({ salesOrderId: id }) })
const { data: prepaymentSummary } = useQuery({ queryKey: ['sales-order-prepayments', id], queryFn: () => api.salesOrderPrepayments(id) })
const { data: relatedOutbounds } = useQuery({ queryKey: ['outbounds', 'sales-order', id], queryFn: () => api.outbounds({ salesOrderId: id }) })
const { data: revisions } = useQuery({ queryKey: ['business-order-revisions', 'SALES_ORDER', id], queryFn: () => api.salesOrderRevisions(id) })
const paymentDialogVisible = ref(false)
const receiveDialogVisible = ref(false)
const reserveDialogVisible = ref(false)
const receivingPrepayment = ref(false)
const reservingPrepayment = ref(false)
const sourcePaymentDrawerVisible = ref(false)
const sourcePaymentLoadingId = ref<number | null>(null)
const sourcePaymentDetail = ref<PaymentRow | null>(null)
const sourcePaymentAllocation = ref<SalesOrderAdvanceAllocation | null>(null)
const appliedPaymentDetail = ref<PaymentRow | null>(null)
const sourcePaymentDrawerPage = ref<'SOURCE' | 'APPLIED'>('SOURCE')
const paymentRecordDrawerVisible = ref(false)
const activePaymentRecord = ref<PaymentRow>()
const receiveForm = reactive({ amount: '', paymentMethod: '' as SettlementMethod, businessBankAccountId: undefined as number | undefined, receivedDate: currentDateText(), memo: '' })
watch(() => receiveForm.paymentMethod, (value) => { if (value !== 'BANK') receiveForm.businessBankAccountId = undefined })
const reserveForm = reactive({ amount: '', memo: '' })
const { printTime, printDocument } = useDocumentPrint(['is-sales-order-printing'])
const backPath = computed(() => returnPath(route, '/sales-orders'))
const remainingAmount = computed(() => Math.max(0, Number(detail.value?.outstandingAmount ?? 0)))
const prepaymentEligible = computed(() => ['DRAFT', 'OPEN', 'PARTIAL_SHIPPED'].includes(detail.value?.status || ''))
const confirmBlockedReason = computed(() => {
  if (!detail.value) return ''
  if (!detail.value.fulfillmentWarehouseId) return '请先编辑草稿并选择履约仓库'
  if (!detail.value.deliveryPlanDate) return '请先编辑草稿并填写计划交付日期'
  if (detail.value.paymentTerm !== 'POSTPAID' && Number(detail.value.prepaymentShortfall) > 0) return `预付款还差 ${money(detail.value.prepaymentShortfall)}`
  return ''
})
const { itemViewMode, documentItemViewOptions } = useDocumentItemView()
const activeSourcePaymentDetail = computed(() => sourcePaymentDrawerPage.value === 'APPLIED' ? appliedPaymentDetail.value : sourcePaymentDetail.value)
const sourcePaymentDrawerTitle = computed(() => sourcePaymentDrawerPage.value === 'APPLIED' ? '应收核销详情' : '来源回款详情')
const sourcePaymentDrawerSubtitle = computed(() => sourcePaymentDrawerPage.value === 'APPLIED' ? '客户预收款最终冲减销售订单应收的记录' : '原始到账及其在本订单中的使用情况')
const sourcePaymentTrail = computed(() => [
  { label: `销售订单 ${detail.value?.orderNo || ''}` },
  { label: `来源回款 ${sourcePaymentDetail.value?.paymentNo || ''}` },
  ...(sourcePaymentDrawerPage.value === 'APPLIED' ? [{ label: `应收核销 ${appliedPaymentDetail.value?.paymentNo || ''}` }] : []),
])
const salesOrderPrintRows = computed(() => (detail.value?.items ?? []).flatMap((item) => splitPrintItemByPackaging(item)))
const fulfillmentTotals = computed(() => {
  const items = detail.value?.items ?? []
  const ordered = items.reduce((total, item) => total + Number(item.quantity || 0), 0)
  const shipped = items.reduce((total, item) => total + Number(item.shippedQuantity || 0), 0)
  return { ordered, shipped, remaining: Math.max(0, ordered - shipped) }
})
const fulfillmentStatus = computed(() => {
  if (detail.value?.status === 'CANCELLED') return { label: '已作废', type: 'info' as const }
  if (fulfillmentTotals.value.shipped <= 0) return { label: '未发货', type: 'info' as const }
  if (fulfillmentTotals.value.remaining > 0) return { label: '部分发货', type: 'warning' as const }
  return { label: '已发完', type: 'success' as const }
})
const confirmMutation = useMutation({
  mutationFn: (authorization: { reason?: string; approvalId?: number }) =>
    api.confirmSalesOrder(id, authorization.reason, authorization.approvalId),
  onSuccess: async () => {
    ElMessage.success('销售订单已确认销售')
    await Promise.all([
      refetch(),
      queryClient.invalidateQueries({ queryKey: ['sales-orders'] }),
      queryClient.invalidateQueries({ queryKey: ['receivables'] }),
      queryClient.invalidateQueries({ queryKey: ['partner-summaries'] }),
      queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
      queryClient.invalidateQueries({ queryKey: ['payments'] }),
      queryClient.invalidateQueries({ queryKey: ['partner-accounts'] }),
      queryClient.invalidateQueries({ queryKey: ['customer-advances'] }),
      queryClient.invalidateQueries({ queryKey: ['sales-order-prepayments', id] }),
      queryClient.invalidateQueries({ queryKey: ['sales-risk', 'SALES_ORDER', id] }),
      queryClient.invalidateQueries({ queryKey: ['sales-risk-history', 'SALES_ORDER', id] }),
      queryClient.invalidateQueries({ queryKey: ['sales-order-atp', id] }),
      queryClient.invalidateQueries({ queryKey: ['sales-order-atp-history', id] }),
      queryClient.invalidateQueries({ queryKey: ['inventory-atp'] }),
    ])
  },
})
const confirming = computed(() => confirmMutation.isPending.value)
const cancelMutation = useMutation({
  mutationFn: (reason: string) => api.cancelSalesOrder(id, reason),
  onSuccess: async () => {
    ElMessage.success(detail.value?.status === 'DRAFT' ? '销售订单草稿已作废' : '销售订单已取消，承诺过账已冲回')
    await Promise.all([
      refetch(),
      queryClient.invalidateQueries({ queryKey: ['sales-orders'] }),
      queryClient.invalidateQueries({ queryKey: ['receivables'] }),
      queryClient.invalidateQueries({ queryKey: ['partner-summaries'] }),
      queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
      queryClient.invalidateQueries({ queryKey: ['sales-order-atp', id] }),
      queryClient.invalidateQueries({ queryKey: ['sales-order-atp-history', id] }),
      queryClient.invalidateQueries({ queryKey: ['inventory-atp'] }),
    ])
  },
})
const cancelling = computed(() => cancelMutation.isPending.value)

async function confirmOrder() {
  if (!detail.value || detail.value.status !== 'DRAFT') return
  const risk = await api.salesOrderRisk(id)
  const riskDecision = await resolveSalesRiskOverride(risk, '确认销售')
  if (!riskDecision.allowed) return
  const atp = await api.salesOrderAtp(id)
  if (!atp.canPromise) {
    ElMessage.error(atp.issues.map((item) => item.message).join('；') || '当前库存与在途供应不足，不能承诺该交期')
    return
  }
  const confirmed = await ElMessageBox.confirm('确认后订单成为正式待发承诺，并固化本次 ATP 计算快照；不会立即扣库存或形成应收。是否继续？', '确认销售', {
    type: 'warning',
    confirmButtonText: '确认销售',
    cancelButtonText: '继续编辑',
  }).then(() => true).catch(() => false)
  if (!confirmed) return
  confirmMutation.mutate(riskDecision)
}

async function cancelOrder() {
  if (!detail.value || !['DRAFT', 'OPEN'].includes(detail.value.status)) return
  const draft = detail.value.status === 'DRAFT'
  const result = await ElMessageBox.prompt(
    draft ? '作废后不能再编辑或确认销售，且该订单不会提交给客户。请填写作废原因。' : '取消会冲回销售承诺并释放尚未使用的预收占用；已有出库或回款时系统会拒绝。请填写取消原因。',
    draft ? '作废销售订单草稿' : '取消已确认销售订单',
    {
      type: 'warning',
      confirmButtonText: '确认作废',
      cancelButtonText: '取消',
      inputPlaceholder: '请输入作废原因',
      inputValidator: (value) => {
        const reason = String(value || '').trim()
        if (!reason) return '请填写作废原因'
        if (reason.length > 500) return '作废原因不能超过500字'
        return true
      },
    },
  ).catch(() => null)
  if (!result) return
  cancelMutation.mutate(result.value.trim())
}

function revisionTypeName(value: string) {
  return ({ INITIAL: '初始版本', AMENDMENT: '订单变更', CANCEL: '订单取消' } as Record<string, string>)[value] || value
}

function productTitle(row: SalesOrderItemDetail) {
  return [row.productName, row.unit ? `单位:${row.unit}` : ''].filter(Boolean).join(' / ')
}

function outboundRoute(row: OutboundRow) {
  return relatedDetailRoute(route, `/outbounds/${row.id}`)
}

function outboundStatusName(value: string) {
  if (value === 'DRAFT') return '草稿'
  if (value === 'SHIPPED') return '已发货'
  if (value === 'REVERSED') return '已冲正'
  if (value === 'CANCELLED') return '已作废'
  return value
}

function signedMoney(value: number) {
  const amount = Number(value || 0)
  return `${amount >= 0 ? '+' : '-'}${money(Math.abs(amount))}`
}

function percentage(value: number) {
  return `${Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}%`
}

function paymentTermName(value: string) {
  if (value === 'PREPAYMENT_REQUIRED') return '需预付款'
  if (value === 'FULL_PREPAYMENT') return '全额预付'
  return '确认后收款'
}

function shipmentPaymentPolicyName(value: string) {
  if (value === 'SHIPMENT_PAID') return '本次发货控制金额须全额到账'
  if (value === 'ORDER_PAID') return '整张销售订单须全额到账'
  return '不校验到账金额（可零/部分/全额）'
}

function allocationStatusName(value: string) {
  if (value === 'APPLIED') return '已用于本订单'
  if (value === 'RELEASED') return '已释放'
  return '已占用'
}

function sourcePaymentHandlingName(payment: PaymentRow) {
  if (payment.verificationStatus === 'REVERSED') return '已冲正'
  if (payment.verificationStatus === 'VERIFIED') return '已直接核销应收'
  if (payment.accountType === 'CUSTOMER_ADVANCE') return '已转客户预收款'
  return '待处理'
}

function verificationStatusName(value: string) {
  if (value === 'VERIFIED') return '已核销'
  if (value === 'REVERSED') return '已冲正'
  if (value === 'UNAPPLIED') return '未核销'
  return value
}

function openReceiveDialog() {
  receiveForm.amount = ''
  receiveForm.paymentMethod = ''
  receiveForm.businessBankAccountId = undefined
  receiveForm.memo = ''
  receiveForm.receivedDate = currentDateText()
  receiveDialogVisible.value = true
}

function openReserveDialog() {
  reserveForm.amount = ''
  reserveForm.memo = ''
  reserveDialogVisible.value = true
}

function fillReserveAmount() {
  const shortfall = Math.max(Number(detail.value?.prepaymentShortfall || 0), Number(detail.value?.shipmentPaymentShortfall || 0))
  const amount = Math.min(Number(detail.value?.customerAdvanceAvailable || 0), shortfall)
  reserveForm.amount = amount.toFixed(2)
}

async function refreshPrepayments() {
  await Promise.all([
    refetch(),
    queryClient.invalidateQueries({ queryKey: ['sales-order-prepayments', id] }),
    queryClient.invalidateQueries({ queryKey: ['partner-accounts'] }),
    queryClient.invalidateQueries({ queryKey: ['partner-account-logs'] }),
    queryClient.invalidateQueries({ queryKey: ['customer-advances'] }),
    queryClient.invalidateQueries({ queryKey: ['payments'] }),
  ])
}

async function receivePrepayment() {
  const amount = Number(receiveForm.amount)
  if (!Number.isFinite(amount) || amount <= 0) { ElMessage.warning('请填写大于0的到账金额'); return }
  if (!receiveForm.paymentMethod) { ElMessage.warning('请选择结算方式'); return }
  if (receiveForm.paymentMethod === 'BANK' && !receiveForm.businessBankAccountId) { ElMessage.warning('请选择实际收款的企业银行账户'); return }
  receivingPrepayment.value = true
  try {
    await api.receiveSalesOrderPrepayment(id, { ...receiveForm, amount })
    ElMessage.success('订单预付款已到账并占用')
    receiveDialogVisible.value = false
    await refreshPrepayments()
  } finally { receivingPrepayment.value = false }
}

async function reservePrepayment() {
  const amount = Number(reserveForm.amount)
  if (!Number.isFinite(amount) || amount <= 0) { ElMessage.warning('请填写大于0的占用金额'); return }
  reservingPrepayment.value = true
  try {
    await api.reserveSalesOrderPrepayment(id, { ...reserveForm, amount })
    ElMessage.success('客户预收余额已占用')
    reserveDialogVisible.value = false
    await refreshPrepayments()
  } finally { reservingPrepayment.value = false }
}

async function releaseAllocation(allocationId: number) {
  const confirmed = await ElMessageBox.confirm('释放后金额仍保留在客户预收账户，可供其他订单使用。', '释放预收款占用', { type: 'warning' }).then(() => true).catch(() => false)
  if (!confirmed) return
  await api.releaseSalesOrderPrepayment(id, allocationId)
  ElMessage.success('预收款占用已释放')
  await refreshPrepayments()
}

async function openSourcePaymentDetail(allocation: SalesOrderAdvanceAllocation) {
  if (!detail.value || sourcePaymentLoadingId.value !== null) return
  const paymentId = allocation.sourcePaymentId
  sourcePaymentLoadingId.value = paymentId
  try {
    const customerPayments = await api.payments({ customerId: detail.value.customerId })
    const payment = customerPayments.find((item) => item.id === paymentId)
    if (!payment) {
      ElMessage.warning('来源回款记录不存在或已不可用')
      return
    }
    sourcePaymentDetail.value = payment
    sourcePaymentAllocation.value = allocation
    appliedPaymentDetail.value = allocation.appliedPaymentId
      ? customerPayments.find((item) => item.id === allocation.appliedPaymentId) ?? null
      : null
    sourcePaymentDrawerPage.value = 'SOURCE'
    sourcePaymentDrawerVisible.value = true
  } finally {
    sourcePaymentLoadingId.value = null
  }
}

function openAppliedPaymentDetail() {
  if (!appliedPaymentDetail.value) {
    ElMessage.warning('应收核销记录不存在或已不可用')
    return
  }
  sourcePaymentDrawerPage.value = 'APPLIED'
}

function handleSourcePaymentBreadcrumb(index: number) {
  if (index === 0) {
    sourcePaymentDrawerVisible.value = false
    return
  }
  sourcePaymentDrawerPage.value = 'SOURCE'
}

function openPaymentRecord(payment: PaymentRow) {
  activePaymentRecord.value = payment
  paymentRecordDrawerVisible.value = true
}

type PackagingComponentSnapshot = {
  unit?: string
  quantity?: number
  baseQuantity?: number
}

type SalesOrderPrintRow = {
  key: string
  product: string
  spec: string
  packaging: string
  pricingQuantity: number
  priceUnit: string
  unitPrice: number
  amount: number
  memo: string
}

function splitPrintItemByPackaging(item: SalesOrderItemDetail): SalesOrderPrintRow[] {
  const components = packagingComponents(item)
  if (components.length <= 1) {
    return [makePrintRow(item, components[0], 0, 1)]
  }
  return components.map((component, index) => makePrintRow(item, component, index, components.length, components))
}

function makePrintRow(
  item: SalesOrderItemDetail,
  component: PackagingComponentSnapshot | undefined,
  index: number,
  count: number,
  components: PackagingComponentSnapshot[] = component ? [component] : [],
): SalesOrderPrintRow {
  const useBaseQuantity = components.some((current) => positiveNumber(current.baseQuantity) > 0)
  const componentWeight = (current: PackagingComponentSnapshot) => positiveNumber(useBaseQuantity ? current.baseQuantity : current.quantity)
  const totalWeight = components.reduce((sum, current) => sum + componentWeight(current), 0)
  const ratio = count > 1 && totalWeight > 0 ? componentWeight(component || {}) / totalWeight : 1
  const previousRatio = count > 1 && totalWeight > 0
    ? components.slice(0, index).reduce((sum, current) => sum + componentWeight(current), 0) / totalWeight
    : 0
  const pricingTotal = positiveNumber(item.pricingQuantity ?? item.requestedQuantity ?? item.quantity)
  const amountTotal = Number(item.amount || 0)
  const pricingQuantity = count > 1 && index === count - 1
    ? Math.max(0, pricingTotal - components.slice(0, index).reduce((sum, current) => sum + pricingTotal * componentWeight(current) / totalWeight, 0))
    : pricingTotal * ratio
  const amount = count > 1 && index === count - 1
    ? roundMoney(amountTotal - components.slice(0, index).reduce((sum, current) => sum + roundMoney(amountTotal * componentWeight(current) / totalWeight), 0))
    : roundMoney(amountTotal * ratio)
  const fallbackPackaging = item.requestedQuantity && item.requestedUnit
    ? `${quantity(item.requestedQuantity)}${item.requestedUnit}`
    : item.packageUnit || '-'
  const declaredPackaging = Number(item.packageQuantity) > 0
    ? `${quantity(item.packageQuantity)}${item.packageUnit || ''}`
    : ''

  return {
    key: `${item.id}-${index}-${previousRatio}`,
    product: [item.sku, item.productName].filter(Boolean).join(' / '),
    spec: item.spec || '-',
    packaging: declaredPackaging
      ? (index === 0 ? declaredPackaging : '-')
      : component ? `${quantity(component.quantity)}${component.unit || ''}` : fallbackPackaging,
    pricingQuantity,
    priceUnit: item.priceUnit || item.unit || '-',
    unitPrice: Number(item.unitPrice || 0),
    amount,
    memo: item.noCharge ? '赠品' : '',
  }
}

function packagingComponents(item: SalesOrderItemDetail): PackagingComponentSnapshot[] {
  try {
    const snapshot = JSON.parse(item.packagingPlanJson || '{}')
    const components = snapshot?.calculation?.components ?? snapshot?.components
    if (!Array.isArray(components)) return []
    return components.filter((component) => positiveNumber(component?.quantity) > 0 && String(component?.unit || '').trim())
  } catch {
    return []
  }
}

function printAddress(value: string | null | undefined) {
  const address = String(value || '').replace(/^\s*主要地址\s*[：:]?\s*/, '')
  return formatPhone(address) || '-'
}

function positiveNumber(value: unknown) {
  const number = Number(value || 0)
  return Number.isFinite(number) && number > 0 ? number : 0
}

function roundMoney(value: number) {
  return Math.round((Number(value || 0) + Number.EPSILON) * 100) / 100
}

function uppercaseRmb(value: number) {
  const amountInFen = Math.round(Math.abs(Number(value || 0)) * 100)
  if (!amountInFen) return '人民币零元整'
  const digits = '零壹贰叁肆伍陆柒捌玖'
  const sectionUnits = ['', '万', '亿', '兆']
  const integer = Math.floor(amountInFen / 100)
  const jiao = Math.floor(amountInFen / 10) % 10
  const fen = amountInFen % 10
  let result = ''
  let remaining = integer
  let sectionIndex = 0
  let emptySectionBetween = false

  while (remaining > 0) {
    const section = remaining % 10000
    if (section === 0) {
      emptySectionBetween = Boolean(result)
    } else {
      const needsZero = Boolean(result) && (emptySectionBetween || section < 1000)
      result = `${uppercaseSection(section, digits)}${sectionUnits[sectionIndex] || ''}${needsZero ? '零' : ''}${result}`
      emptySectionBetween = false
    }
    remaining = Math.floor(remaining / 10000)
    sectionIndex += 1
  }

  const integerText = result || '零'
  const decimalText = jiao
    ? `${digits[jiao]}角${fen ? `${digits[fen]}分` : ''}`
    : fen ? `零${digits[fen]}分` : '整'
  return `人民币${integerText}元${decimalText}`
}

function uppercaseSection(value: number, digits: string) {
  const units = ['', '拾', '佰', '仟']
  let remaining = value
  let unitIndex = 0
  let result = ''
  let zeroPending = false
  while (remaining > 0) {
    const digit = remaining % 10
    if (digit === 0) {
      zeroPending = Boolean(result)
    } else {
      result = `${digits[digit]}${units[unitIndex]}${zeroPending ? '零' : ''}${result}`
      zeroPending = false
    }
    remaining = Math.floor(remaining / 10)
    unitIndex += 1
  }
  return result
}

async function printSalesOrder() {
  if (!detail.value || detail.value.status === 'CANCELLED') return
  await printDocument(printFileName(detail.value.customerName || '客户', '销售订单', detail.value.orderNo || '未编号'))
}

async function handlePaymentSuccess() {
  paymentDialogVisible.value = false
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['sales-order-detail', id] }),
    queryClient.invalidateQueries({ queryKey: ['payments', 'sales-order', id] }),
  ])
}
</script>

<style scoped>
.price-audit-cell {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.35;
}

.price-audit-source {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
