<template>
  <div class="page bank-page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">银行对账</div>
          <div class="muted bank-subtitle">银行流水、系统收付款和会计总账分别保留；通过逐笔匹配及未达项形成余额调节表，不直接改余额。</div>
        </div>
        <div class="header-actions">
          <el-button @click="bankDialog = true">企业账户</el-button>
          <el-button v-if="canManage" type="primary" @click="openSessionDialog">新建对账</el-button>
          <el-button :loading="loading" @click="refresh">刷新</el-button>
        </div>
      </div>

      <div class="list-tools">
        <el-input-number v-model="fiscalYear" :min="2000" :max="2200" :controls="false" />
        <el-select v-model="periodId" clearable placeholder="全部期间" @change="selectedId = 0">
          <el-option v-for="period in periods" :key="period.id" :label="period.periodName" :value="period.id" />
        </el-select>
        <span class="muted">每个企业银行账户每个期间只能有一张对账单。</span>
      </div>

      <el-table v-loading="sessionQuery.isLoading.value" :data="sessions" border size="small" class="data-table session-table" highlight-current-row @current-change="selectSession">
        <el-table-column prop="periodName" label="期间" width="130" fixed="left" />
        <el-table-column label="银行账户" min-width="220"><template #default="{ row }"><strong>{{ row.bankAccountName }}</strong><div class="multiline-secondary">{{ row.maskedAccountNo }}</div></template></el-table-column>
        <el-table-column prop="reconciliationNo" label="对账编号" min-width="205" />
        <el-table-column label="银行期末余额" width="145" align="right"><template #default="{ row }">{{ money(row.statementClosingBalance) }}</template></el-table-column>
        <el-table-column label="总账期末余额" width="145" align="right"><template #default="{ row }">{{ money(row.ledgerClosingBalance) }}</template></el-table-column>
        <el-table-column label="调节后差额" width="135" align="right"><template #default="{ row }"><span :class="{ 'amount-danger': !isZero(row.difference) }">{{ money(row.difference) }}</span></template></el-table-column>
        <el-table-column label="状态" width="105"><template #default="{ row }"><el-tag size="small" :type="sessionTag(row.status)">{{ sessionStatus(row.status) }}</el-tag></template></el-table-column>
      </el-table>
    </div>

    <div v-if="detail" class="panel detail-panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">{{ detail.session.periodName }} · {{ detail.session.bankAccountName }}</div>
          <div class="muted">{{ detail.session.statementFrom }} 至 {{ detail.session.statementTo }} · {{ detail.session.reconciliationNo }}</div>
        </div>
        <div class="header-actions">
          <el-button plain :loading="printing" @click="printReconciliation">打印 / 导出余额调节表</el-button>
          <el-dropdown v-if="editable" @command="downloadStatementTemplate"><el-button>下载导入模板</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="xlsx">Excel 模板</el-dropdown-item><el-dropdown-item command="csv">CSV 模板</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
          <el-button v-if="editable" type="primary" plain @click="openImportDialog">导入流水</el-button>
          <el-button v-if="editable" type="success" :disabled="!isZero(detail.session.difference)" @click="confirmSession">确认对账</el-button>
          <el-button v-if="canManage && detail.session.status === 'CONFIRMED'" type="warning" @click="reopenSession">重新打开</el-button>
        </div>
      </div>

      <div class="balance-strip">
        <div><span>银行期初</span><strong>{{ money(detail.session.openingBalance) }}</strong></div>
        <div><span>银行期末</span><strong>{{ money(detail.session.statementClosingBalance) }}</strong></div>
        <div><span>总账期末</span><strong>{{ money(detail.session.ledgerClosingBalance) }}</strong></div>
        <div><span>调节后银行</span><strong>{{ money(detail.session.adjustedStatementBalance) }}</strong></div>
        <div><span>调节后总账</span><strong>{{ money(detail.session.adjustedLedgerBalance) }}</strong></div>
        <div :class="{ danger: !isZero(detail.session.difference) }"><span>调节差额</span><strong>{{ money(detail.session.difference) }}</strong></div>
      </div>

      <el-tabs v-model="detailTab">
        <el-tab-pane :label="`银行流水 ${detail.statementLines.length}`" name="statement">
          <el-table :data="detail.statementLines" border size="small" class="data-table" max-height="420">
            <el-table-column prop="lineNo" label="#" width="55" />
            <el-table-column prop="transactionDate" label="交易日期" width="112" />
            <el-table-column label="方向" width="80"><template #default="{ row }">{{ directionName(row.direction) }}</template></el-table-column>
            <el-table-column label="金额" width="125" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
            <el-table-column label="流水余额" width="130" align="right"><template #default="{ row }">{{ money(row.balance) }}</template></el-table-column>
            <el-table-column prop="counterpartyName" label="对方户名" min-width="150" />
            <el-table-column prop="purpose" label="摘要" min-width="180" />
            <el-table-column label="匹配建议 / 未匹配原因" min-width="245"><template #default="{ row }"><template v-if="row.status === 'MATCHED'"><span class="match-success">已完成逐笔匹配</span><div v-if="row.importBatchNo" class="multiline-secondary">{{ row.importBatchNo }}</div></template><template v-else-if="row.suggestedSourceNo"><strong>{{ row.suggestedSourceNo }} · {{ row.suggestedScore }}分</strong><div class="multiline-secondary">{{ row.unmatchedReason || row.suggestedReason }}</div></template><template v-else><span :class="{ 'reason-danger': row.unmatchedReason }">{{ row.unmatchedReason || '-' }}</span></template></template></el-table-column>
            <el-table-column label="已处理 / 剩余" width="150" align="right"><template #default="{ row }"><span>{{ money(Number(row.matchedAmount) + Number(row.explainedAmount)) }}</span><div class="multiline-secondary">剩余 {{ money(row.remainingAmount) }}</div></template></el-table-column>
            <el-table-column label="状态" width="95"><template #default="{ row }"><el-tag size="small" :type="lineTag(row.status)">{{ lineStatus(row.status) }}</el-tag></template></el-table-column>
            <el-table-column v-if="editable" label="操作" width="178" fixed="right"><template #default="{ row }"><template v-if="row.status !== 'VOID' && Number(row.remainingAmount) >= 0.01"><el-button link type="primary" @click="openMatch(row)">匹配</el-button><el-button link type="warning" @click="openDifference(row)">差异</el-button></template><el-button v-if="row.status !== 'VOID'" link type="danger" @click="voidLine(row)">作废</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`系统资金 ${detail.fundCandidates.length}`" name="candidates">
          <el-alert type="info" :closable="false" title="系统资金未出现在本期银行流水时，应登记为银行未达项并说明原因；不能直接忽略。" />
          <el-table :data="detail.fundCandidates" border size="small" class="data-table candidate-table">
            <el-table-column prop="sourceDate" label="业务日期" width="112" />
            <el-table-column prop="sourceNo" label="来源单号" min-width="200" />
            <el-table-column prop="partnerName" label="往来单位" min-width="150" />
            <el-table-column label="方向" width="80"><template #default="{ row }">{{ directionName(row.direction) }}</template></el-table-column>
            <el-table-column label="金额" width="125" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
            <el-table-column label="待匹配" width="125" align="right"><template #default="{ row }">{{ money(row.remainingAmount) }}</template></el-table-column>
            <el-table-column v-if="editable" label="操作" width="110"><template #default="{ row }"><el-button link type="warning" @click="markPending(row)">登记未达</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`匹配记录 ${detail.matches.length}`" name="matches">
          <el-table :data="detail.matches" border size="small" class="data-table">
            <el-table-column prop="sourceNo" label="系统单号" min-width="190" />
            <el-table-column prop="sourceDate" label="日期" width="112" />
            <el-table-column label="金额" width="125" align="right"><template #default="{ row }">{{ money(row.matchedAmount) }}</template></el-table-column>
            <el-table-column label="现金流分类" min-width="170"><template #default="{ row }">{{ cashCategoryName(row.cashFlowCategory) }}</template></el-table-column>
            <el-table-column label="匹配方式" width="120"><template #default="{ row }"><el-tag v-if="row.matchingMethod === 'RULE'" size="small" type="success">自动 {{ row.matchScore }}分</el-tag><span v-else>人工</span></template></el-table-column>
            <el-table-column prop="matchedByName" label="操作人" width="120" />
            <el-table-column label="状态" width="90"><template #default="{ row }">{{ row.status === 'ACTIVE' ? '有效' : '已撤销' }}</template></el-table-column>
            <el-table-column v-if="editable" label="操作" width="85"><template #default="{ row }"><el-button v-if="row.status === 'ACTIVE'" link type="danger" @click="reverseMatch(row.id)">撤销</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`差异与未达 ${detail.differences.length + detail.pendingSystemItems.length}`" name="differences">
          <div class="subsection-title">银行侧差异</div>
          <el-table :data="detail.differences" border size="small" class="data-table">
            <el-table-column label="类型" width="135"><template #default="{ row }">{{ differenceName(row.differenceType) }}</template></el-table-column>
            <el-table-column label="金额" width="125" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
            <el-table-column prop="reason" label="说明" min-width="220" />
            <el-table-column label="凭证" min-width="180"><template #default="{ row }"><RouterLink v-if="row.accountingVoucherId" class="business-link" :to="`/accounting/vouchers/${row.accountingVoucherId}`">{{ row.accountingVoucherNo || `#${row.accountingVoucherId}` }} · {{ row.accountingVoucherStatus }}</RouterLink><span v-else>-</span></template></el-table-column>
            <el-table-column label="状态" width="90"><template #default="{ row }">{{ row.status === 'REVERSED' ? '已撤销' : '有效' }}</template></el-table-column>
            <el-table-column v-if="editable" label="操作" width="85"><template #default="{ row }"><el-button v-if="row.status !== 'REVERSED'" link type="danger" @click="reverseDifference(row.id)">撤销</el-button></template></el-table-column>
          </el-table>
          <div class="subsection-title pending-title">系统侧银行未达</div>
          <el-table :data="detail.pendingSystemItems" border size="small" class="data-table">
            <el-table-column prop="sourceNo" label="系统单号" min-width="190" />
            <el-table-column prop="sourceDate" label="日期" width="112" />
            <el-table-column label="方向" width="80"><template #default="{ row }">{{ directionName(row.direction) }}</template></el-table-column>
            <el-table-column label="金额" width="125" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
            <el-table-column prop="reason" label="未达原因" min-width="220" />
            <el-table-column label="状态" width="90"><template #default="{ row }">{{ row.status === 'OPEN' ? '未达' : '已撤销' }}</template></el-table-column>
            <el-table-column v-if="editable" label="操作" width="85"><template #default="{ row }"><el-button v-if="row.status === 'OPEN'" link type="danger" @click="reversePending(row.id)">撤销</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`导入批次 ${importBatches.length}`" name="imports">
          <el-alert type="info" :closable="false" title="导入批次保留原文件名、文件哈希、逐行重复依据和自动匹配数量；重复和错误行不会写入银行流水。" />
          <el-table :data="importBatches" border size="small" class="data-table candidate-table">
            <el-table-column prop="batchNo" label="批次号" min-width="205" />
            <el-table-column prop="sourceFileName" label="原文件" min-width="190" />
            <el-table-column label="预检结果" min-width="235"><template #default="{ row }"><span>有效 {{ row.validRows }} · 重复 {{ row.duplicateRows }} · 错误 {{ row.errorRows }}</span><div class="multiline-secondary">自动 {{ row.autoMatchRows }} · 待确认 {{ row.reviewRows }} · 未匹配 {{ row.unmatchedRows }}</div></template></el-table-column>
            <el-table-column label="提交结果" min-width="175"><template #default="{ row }">导入 {{ row.importedRows }} · 自动匹配 {{ row.appliedAutoMatchRows }}</template></el-table-column>
            <el-table-column prop="importedByName" label="操作人" width="110" />
            <el-table-column label="时间" width="165"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
            <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag size="small" :type="row.status === 'COMPLETED' ? 'success' : 'warning'">{{ row.status === 'COMPLETED' ? '已提交' : '待提交' }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="80"><template #default="{ row }"><el-button link type="primary" @click="showImportBatch(row.id)">查看</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="审计轨迹" name="events">
          <el-timeline class="event-timeline"><el-timeline-item v-for="event in detail.events" :key="event.id" :timestamp="formatDateTime(event.createdAt)" placement="top"><strong>{{ eventAction(event.action) }}</strong> · {{ event.operatorName || '系统' }}<div class="multiline-secondary">{{ event.reason || '-' }}</div></el-timeline-item></el-timeline>
        </el-tab-pane>
      </el-tabs>
    </div>
    <el-empty v-else class="panel detail-panel" description="请选择或新建一张银行对账单" />

    <section v-if="detail" class="print-sheet a4-print-sheet">
      <div class="document-print-heading">
        <div class="document-print-company">{{ appStore.settings.companyName }}</div>
        <div class="document-print-title">银行余额调节表</div>
        <div class="document-print-subtitle"><span>会计期间：{{ detail.session.periodName }}</span><span>打印时间：{{ printTime }}</span></div>
      </div>
      <table class="document-print-meta"><tbody>
        <tr><td>对账编号：{{ detail.session.reconciliationNo }}</td><td>银行账户：{{ detail.session.bankAccountName }}</td><td>账号：{{ detail.session.maskedAccountNo }}</td></tr>
        <tr><td>对账区间：{{ detail.session.statementFrom }} 至 {{ detail.session.statementTo }}</td><td>状态：{{ sessionStatus(detail.session.status) }}</td><td>确认人：{{ detail.session.confirmedByName || '-' }}</td></tr>
      </tbody></table>
      <table class="document-print-table"><thead><tr><th>#</th><th>调节项目</th><th>银行侧</th><th>总账侧</th><th>说明</th></tr></thead><tbody>
        <tr><td class="center">1</td><td>期末余额</td><td class="right">{{ money(detail.session.statementClosingBalance) }}</td><td class="right">{{ money(detail.session.ledgerClosingBalance) }}</td><td>银行对账单与银行存款总账原始余额</td></tr>
        <tr v-for="(row, index) in detail.differences.filter((item) => item.status !== 'REVERSED')" :key="`difference-${row.id}`"><td class="center">{{ index + 2 }}</td><td>{{ differenceName(row.differenceType) }}</td><td class="right">-</td><td class="right">{{ signedMoney(row.direction, row.amount) }}</td><td>银行已{{ directionName(row.direction) }}、系统待处理 · {{ row.reason }}</td></tr>
        <tr v-for="(row, index) in detail.pendingSystemItems.filter((item) => item.status === 'OPEN')" :key="`pending-${row.id}`"><td class="center">{{ detail.differences.filter((item) => item.status !== 'REVERSED').length + index + 2 }}</td><td>系统已记账、银行未达</td><td class="right">{{ signedMoney(row.direction, row.amount) }}</td><td class="right">-</td><td>{{ row.sourceNo }} · 系统已{{ directionName(row.direction) }} · {{ row.reason }}</td></tr>
        <tr><td class="center">=</td><td><strong>调节后余额</strong></td><td class="right"><strong>{{ money(detail.session.adjustedStatementBalance) }}</strong></td><td class="right"><strong>{{ money(detail.session.adjustedLedgerBalance) }}</strong></td><td>差额 {{ money(detail.session.difference) }}</td></tr>
      </tbody></table>
      <table class="document-print-total"><tbody><tr><td>银行流水：{{ detail.statementLines.length }} 笔</td><td>有效匹配：{{ detail.matches.filter((item) => item.status === 'ACTIVE').length }} 笔</td><td>银行侧差异：{{ detail.differences.filter((item) => item.status !== 'REVERSED').length }} 笔</td><td>系统未达：{{ detail.pendingSystemItems.filter((item) => item.status === 'OPEN').length }} 笔</td></tr></tbody></table>
      <div class="document-print-audit-title">审计轨迹</div>
      <table class="document-print-table document-print-audit"><thead><tr><th>#</th><th>动作</th><th>状态变化</th><th>操作人 / 时间</th><th>原因</th></tr></thead><tbody>
        <tr v-for="(event, index) in detail.events" :key="event.id"><td class="center">{{ index + 1 }}</td><td>{{ eventAction(event.action) }}</td><td>{{ event.fromStatus || '-' }} → {{ event.toStatus || '-' }}</td><td>{{ event.operatorName || '系统' }}<br>{{ formatDateTime(event.createdAt) }}</td><td>{{ event.reason || '-' }}</td></tr>
      </tbody></table>
      <div class="document-print-hash">打印数据摘要（SHA-256）：{{ snapshotHash }}</div>
      <div class="document-print-signatures"><span>出纳：</span><span>会计：</span><span>复核：</span><span>负责人：</span></div>
    </section>
    <EvidenceAttachments
      v-if="detail"
      document-type="BANK_RECONCILIATION"
      :document-id="selectedId"
      title="余额调节表、银行回单与签字盖章归档"
      default-evidence-type="SIGNED_STAMPED_COPY"
      show-output-history
    />

    <el-dialog v-model="bankDialog" title="企业银行账户" width="900px" destroy-on-close>
      <el-alert type="warning" :closable="false" title="企业自有银行账户必须一一对应末级银行存款科目；已有对账记录后不能更换对应科目。" />
      <el-table :data="bankAccounts" border size="small" class="data-table bank-account-table">
        <el-table-column prop="accountCode" label="编码" width="100" />
        <el-table-column prop="accountName" label="账户名称" min-width="150" />
        <el-table-column prop="bankName" label="开户银行" min-width="150" />
        <el-table-column prop="maskedAccountNo" label="银行账号" min-width="165" />
        <el-table-column label="总账科目" min-width="155"><template #default="{ row }">{{ row.ledgerAccountCode }} {{ row.ledgerAccountName }}</template></el-table-column>
        <el-table-column label="状态" width="80"><template #default="{ row }">{{ row.status === 'ENABLED' ? '启用' : '停用' }}</template></el-table-column>
        <el-table-column v-if="canManageBankAccounts" label="操作" width="70"><template #default="{ row }"><el-button link type="primary" @click="openBankForm(row)">编辑</el-button></template></el-table-column>
      </el-table>
      <el-button v-if="canManageBankAccounts" class="add-bank" @click="openBankForm()">新增银行账户</el-button>
    </el-dialog>

    <el-dialog v-model="bankFormDialog" :title="bankForm.id ? '编辑企业银行账户' : '新增企业银行账户'" width="680px" destroy-on-close>
      <el-form label-position="top"><div class="form-grid form-grid-2">
        <el-form-item label="账户编码" required><el-input v-model="bankForm.accountCode" maxlength="32" /></el-form-item>
        <el-form-item label="账户名称" required><el-input v-model="bankForm.accountName" maxlength="128" /></el-form-item>
        <el-form-item label="开户银行" required><el-input v-model="bankForm.bankName" maxlength="128" /></el-form-item>
        <el-form-item label="完整银行账号" required><el-input v-model="bankForm.bankAccountNo" placeholder="保存后列表仅展示掩码" maxlength="128" /></el-form-item>
        <el-form-item label="银行存款科目" required><el-select v-model="bankForm.ledgerAccountId" filterable><el-option v-for="item in bankLedgerAccounts" :key="item.id" :label="`${item.accountCode} ${item.accountName}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="启用日期" required><el-date-picker v-model="bankForm.openingDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="启用日余额" required><el-input-number v-model="bankForm.openingBalance" :precision="2" :controls="false" /></el-form-item>
        <el-form-item label="状态"><el-select v-model="bankForm.status"><el-option label="启用" value="ENABLED" /><el-option label="停用" value="DISABLED" /></el-select></el-form-item>
        <el-form-item v-if="bankForm.status === 'DISABLED'" label="停用原因" required class="full"><el-input v-model="bankForm.disabledReason" type="textarea" maxlength="500" /></el-form-item>
      </div></el-form>
      <template #footer><el-button @click="bankFormDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveBankAccount">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="sessionDialog" title="新建银行对账" width="580px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="企业银行账户" required><el-select v-model="sessionForm.bankAccountId" filterable><el-option v-for="item in reconcilableBankAccounts" :key="item.id" :label="`${item.accountName} ${item.maskedAccountNo}${item.status === 'DISABLED' ? '（已停用，仅限历史对账）' : ''}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="会计期间" required><el-select v-model="sessionForm.periodId"><el-option v-for="item in periods" :key="item.id" :label="`${item.periodName}（${item.status === 'OPEN' ? '开放' : '已结账'}）`" :value="item.id" :disabled="item.status !== 'OPEN'" /></el-select></el-form-item>
        <div class="form-grid form-grid-2"><el-form-item label="银行对账单期初余额" required><el-input-number v-model="sessionForm.openingBalance" :precision="2" :controls="false" /></el-form-item><el-form-item label="银行对账单期末余额" required><el-input-number v-model="sessionForm.statementClosingBalance" :precision="2" :controls="false" /></el-form-item></div>
      </el-form>
      <template #footer><el-button @click="sessionDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="createSession">建立对账单</el-button></template>
    </el-dialog>

    <el-dialog v-model="importDialog" title="导入银行流水" width="1180px" destroy-on-close>
      <el-alert type="info" :closable="false" show-icon title="先预检，再提交：重复或错误行不会入账；只有金额一致、日期接近、户名/用途证据充分且候选唯一时才自动匹配。导入和匹配都不会创建收付款或修改余额。" />
      <div class="import-actions">
        <el-upload :auto-upload="false" :limit="1" accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" :on-change="previewStatementFile" :on-remove="resetImportPreview"><el-button :loading="previewing">选择 CSV / Excel 并预检</el-button></el-upload>
        <el-dropdown @command="downloadStatementTemplate"><el-button>下载标准模板</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="xlsx">Excel 模板</el-dropdown-item><el-dropdown-item command="csv">CSV 模板</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
      </div>
      <template v-if="importPreview">
        <div class="import-summary">
          <div><span>总行数</span><strong>{{ importPreview.batch.totalRows }}</strong></div>
          <div class="summary-success"><span>可导入</span><strong>{{ importPreview.batch.validRows }}</strong></div>
          <div><span>自动匹配</span><strong>{{ importPreview.batch.autoMatchRows }}</strong></div>
          <div class="summary-warning"><span>待确认</span><strong>{{ importPreview.batch.reviewRows }}</strong></div>
          <div class="summary-warning"><span>未匹配</span><strong>{{ importPreview.batch.unmatchedRows }}</strong></div>
          <div class="summary-muted"><span>重复跳过</span><strong>{{ importPreview.batch.duplicateRows }}</strong></div>
          <div :class="{ 'summary-danger': importPreview.batch.errorRows }"><span>错误跳过</span><strong>{{ importPreview.batch.errorRows }}</strong></div>
        </div>
        <el-checkbox v-if="importPreview.batch.status === 'PREVIEWED'" v-model="applyAutoMatches" class="auto-match-check">提交时应用唯一高置信自动匹配（推荐）</el-checkbox>
        <el-table :data="importPreview.rows" border size="small" class="data-table import-preview-table" max-height="430">
          <el-table-column prop="sourceRowNo" label="文件行" width="75" fixed="left" />
          <el-table-column prop="transactionDate" label="交易日期" width="110" />
          <el-table-column label="方向" width="75"><template #default="{ row }">{{ row.direction ? directionName(row.direction) : '-' }}</template></el-table-column>
          <el-table-column label="金额" width="115" align="right"><template #default="{ row }">{{ row.amount == null ? '-' : money(row.amount) }}</template></el-table-column>
          <el-table-column prop="counterpartyName" label="对方户名" min-width="145" />
          <el-table-column prop="purpose" label="用途/摘要" min-width="155" show-overflow-tooltip />
          <el-table-column label="校验" width="105"><template #default="{ row }"><el-tag size="small" :type="validationTag(row.validationStatus)">{{ validationName(row.validationStatus) }}</el-tag></template></el-table-column>
          <el-table-column label="匹配判断" min-width="165"><template #default="{ row }"><strong v-if="row.suggestedSourceNo">{{ row.suggestedSourceNo }} · {{ row.suggestedScore }}分</strong><span v-else>{{ matchActionName(row.matchAction) }}</span><div class="multiline-secondary">{{ row.suggestedReason || row.unmatchedReason || row.validationMessage || '-' }}</div></template></el-table-column>
        </el-table>
      </template>
      <el-empty v-else description="请选择标准 CSV 或 Excel 文件，系统会先显示重复、错误和匹配建议" />
      <template #footer><el-button @click="importDialog = false">关闭</el-button><el-button v-if="importPreview?.batch.status === 'PREVIEWED'" type="primary" :loading="saving" :disabled="!importPreview.batch.validRows" @click="commitStatementImport">确认导入{{ applyAutoMatches ? '并自动匹配' : '' }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="matchDialog" title="匹配系统资金" width="680px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="银行流水"><el-input :model-value="selectedLine ? `${selectedLine.transactionDate} ${directionName(selectedLine.direction)} ${money(selectedLine.remainingAmount)} ${selectedLine.counterpartyName}` : ''" disabled /></el-form-item>
        <el-form-item label="系统资金" required><el-select v-model="matchForm.candidateKey" filterable><el-option v-for="item in matchingCandidates" :key="candidateKey(item)" :label="`${item.sourceDate} ${item.sourceNo} ${item.partnerName} · 待匹配 ${money(item.remainingAmount)}`" :value="candidateKey(item)" /></el-select></el-form-item>
        <div class="form-grid form-grid-2"><el-form-item label="匹配金额" required><el-input-number v-model="matchForm.amount" :min="0.01" :precision="2" :controls="false" /></el-form-item><el-form-item label="现金流量分类" required><el-select v-model="matchForm.category"><el-option v-for="item in categoryOptions(selectedLine?.direction)" :key="`${item.value}:${item.direction}`" :label="item.label" :value="item.value" /></el-select></el-form-item></div>
        <el-form-item label="匹配说明"><el-input v-model="matchForm.reason" maxlength="500" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="matchDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveMatch">确认匹配</el-button></template>
    </el-dialog>

    <el-dialog v-model="differenceDialog" title="登记银行侧差异" width="650px" destroy-on-close>
      <el-alert type="warning" :closable="false" title="差异说明不是调平工具。银行手续费和利息可生成凭证草稿；其他差异仍需查明并以正确科目处理，期末勾稽不平时不能结账。" />
      <el-form label-position="top"><div class="form-grid form-grid-2">
        <el-form-item label="差异类型" required><el-select v-model="differenceForm.type"><el-option v-for="item in differenceOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="金额" required><el-input-number v-model="differenceForm.amount" :min="0.01" :precision="2" :controls="false" /></el-form-item>
        <el-form-item label="现金流量分类" required><el-select v-model="differenceForm.category"><el-option v-for="item in categoryOptions(selectedLine?.direction)" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="生成凭证草稿"><el-switch v-model="differenceForm.createVoucher" :disabled="!['BANK_FEE', 'INTEREST'].includes(differenceForm.type)" /></el-form-item>
        <el-form-item label="差异原因" required class="full"><el-input v-model="differenceForm.reason" type="textarea" :rows="3" maxlength="500" /></el-form-item>
      </div></el-form>
      <template #footer><el-button @click="differenceDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveDifference">登记差异</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import { api, type BankFundCandidate, type BankStatementImportPreview, type BankStatementLine, type BusinessBankAccount } from '@/api/client'
import { formatDateTime, money } from '@/api/format'
import EvidenceAttachments from '@/components/EvidenceAttachments.vue'
import { useAuditedDocumentPrint } from '@/composables/useAuditedDocumentPrint'
import { printFileName } from '@/composables/useDocumentPrint'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
const route = useRoute()
const queryClient = useQueryClient()
const requestedFiscalYear = Number(route.query.fiscalYear)
const fiscalYear = ref(Number.isInteger(requestedFiscalYear) && requestedFiscalYear >= 2000 ? requestedFiscalYear : new Date().getFullYear())
const periodId = ref<number>()
const selectedId = ref(0)
const detailTab = ref('statement')
const bankDialog = ref(false)
const bankFormDialog = ref(false)
const sessionDialog = ref(false)
const importDialog = ref(false)
const matchDialog = ref(false)
const differenceDialog = ref(false)
const saving = ref(false)
const previewing = ref(false)
const importPreview = ref<BankStatementImportPreview>()
const applyAutoMatches = ref(true)
const selectedLine = ref<BankStatementLine>()
const canManage = computed(() => appStore.hasPermission('financial.bank.manage'))
const canManageBankAccounts = computed(() => appStore.hasPermission('financial.bank.account.manage'))
const editable = computed(() => canManage.value && Boolean(detail.value) && detail.value?.session.status !== 'CONFIRMED')
const periodQuery = useQuery({ queryKey: computed(() => ['accounting-periods', fiscalYear.value]), queryFn: () => api.accountingPeriods(fiscalYear.value) })
const bankQuery = useQuery({ queryKey: ['business-bank-accounts', 'all'], queryFn: () => api.businessBankAccounts(true) })
const ledgerQuery = useQuery({ queryKey: ['accounting-accounts', 'bank-reconciliation'], queryFn: () => api.accountingAccounts(false) })
const sessionQuery = useQuery({ queryKey: computed(() => ['bank-reconciliations', periodId.value]), queryFn: () => api.bankReconciliations(periodId.value) })
const detailQuery = useQuery({ queryKey: computed(() => ['bank-reconciliation', selectedId.value]), queryFn: () => api.bankReconciliation(selectedId.value), enabled: computed(() => selectedId.value > 0) })
const importBatchQuery = useQuery({ queryKey: computed(() => ['bank-statement-import-batches', selectedId.value]), queryFn: () => api.bankStatementImportBatches(selectedId.value), enabled: computed(() => selectedId.value > 0) })
const periods = computed(() => periodQuery.data.value || [])
const bankAccounts = computed(() => bankQuery.data.value || [])
const reconcilableBankAccounts = computed(() => bankAccounts.value)
const bankLedgerAccounts = computed(() => (ledgerQuery.data.value || []).filter((item) => item.category === 'ASSET' && item.normalDirection === 'DEBIT' && item.leaf))
const sessions = computed(() => sessionQuery.data.value || [])
const detail = computed(() => detailQuery.data.value)
const importBatches = computed(() => importBatchQuery.data.value || [])
const loading = computed(() => periodQuery.isFetching.value || bankQuery.isFetching.value || sessionQuery.isFetching.value || detailQuery.isFetching.value)
const { printTime, snapshotHash, printing, printAndRecord } = useAuditedDocumentPrint('BANK_RECONCILIATION', () => selectedId.value)
const bankForm = reactive({ id: 0, accountCode: '', accountName: '', bankName: '', bankAccountNo: '', currency: 'CNY', ledgerAccountId: 0, openingDate: `${fiscalYear.value}-01-01`, openingBalance: 0, status: 'ENABLED' as 'ENABLED' | 'DISABLED', disabledReason: '' })
const sessionForm = reactive({ bankAccountId: 0, periodId: 0, openingBalance: 0, statementClosingBalance: 0 })
const matchForm = reactive({ candidateKey: '', amount: 0, category: '', reason: '' })
const differenceForm = reactive({ type: 'BANK_NOT_BOOKED', amount: 0, category: '', reason: '', createVoucher: false })
const matchingCandidates = computed(() => (detail.value?.fundCandidates || []).filter((item) => item.direction === selectedLine.value?.direction))
const cashCategories = [
  { value: 'SALES_COLLECTION', label: '销售商品收到的现金', direction: 'CREDIT' },
  { value: 'OTHER_OPERATING_IN', label: '收到其他经营活动现金', direction: 'CREDIT' },
  { value: 'INVESTING_IN', label: '投资活动现金流入', direction: 'CREDIT' },
  { value: 'FINANCING_IN', label: '筹资活动现金流入', direction: 'CREDIT' },
  { value: 'PURCHASE_PAYMENT', label: '购买商品支付的现金', direction: 'DEBIT' },
  { value: 'EMPLOYEE_PAYMENT', label: '支付给职工的现金', direction: 'DEBIT' },
  { value: 'TAX_PAYMENT', label: '支付的税费', direction: 'DEBIT' },
  { value: 'BANK_FEE', label: '支付银行手续费', direction: 'DEBIT' },
  { value: 'OTHER_OPERATING_OUT', label: '支付其他经营活动现金', direction: 'DEBIT' },
  { value: 'INVESTING_OUT', label: '投资活动现金流出', direction: 'DEBIT' },
  { value: 'FINANCING_OUT', label: '筹资活动现金流出', direction: 'DEBIT' },
  { value: 'INTERNAL_TRANSFER', label: '内部账户转账（转出）', direction: 'DEBIT' },
  { value: 'INTERNAL_TRANSFER', label: '内部账户转账（转入）', direction: 'CREDIT' },
]
const differenceOptions = [{ value: 'BANK_NOT_BOOKED', label: '银行已收付、系统未记账' }, { value: 'BANK_FEE', label: '银行手续费' }, { value: 'INTEREST', label: '银行利息收入' }, { value: 'OTHER', label: '其他待查差异' }]

function signedMoney(direction: 'DEBIT' | 'CREDIT', amount: number) {
  return `${direction === 'CREDIT' ? '+' : '-'}${money(amount)}`
}

async function printReconciliation() {
  if (!detail.value) return
  await printAndRecord(
    printFileName(detail.value.session.bankAccountName, '银行余额调节表', detail.value.session.reconciliationNo),
    detail.value,
    'copper-bank-reconciliation-print/v1',
  )
}

watch([periods, () => route.query.periodId], ([rows, requested]) => {
  const requestedId = Number(requested)
  if (Number.isInteger(requestedId) && requestedId > 0 && rows.some((item) => item.id === requestedId)) periodId.value = requestedId
}, { immediate: true })
watch([sessions, () => route.query.scope], ([rows, scope]) => {
  if (!rows.length) { selectedId.value = 0; return }
  if (scope === 'unmatched') {
    selectedId.value = rows.find((item) => item.status !== 'CONFIRMED')?.id || rows[0].id
    detailTab.value = 'statement'
    return
  }
  if (!selectedId.value || !rows.some((item) => item.id === selectedId.value)) selectedId.value = rows[0].id
}, { immediate: true })
function selectSession(row?: { id: number }) { if (row) selectedId.value = row.id }
function openSessionDialog() { Object.assign(sessionForm, { bankAccountId: reconcilableBankAccounts.value.find((item) => item.status === 'ENABLED')?.id || reconcilableBankAccounts.value[0]?.id || 0, periodId: periodId.value || periods.value.find((item) => item.status === 'OPEN')?.id || 0, openingBalance: 0, statementClosingBalance: 0 }); sessionDialog.value = true }
function openBankForm(row?: BusinessBankAccount) { Object.assign(bankForm, row ? { ...row, bankAccountNo: row.bankAccountNo || '' } : { id: 0, accountCode: '', accountName: '', bankName: '', bankAccountNo: '', currency: 'CNY', ledgerAccountId: bankLedgerAccounts.value.find((item) => item.accountCode === '1002')?.id || 0, openingDate: `${fiscalYear.value}-01-01`, openingBalance: 0, status: 'ENABLED', disabledReason: '' }); bankFormDialog.value = true }
async function saveBankAccount() { if (!bankForm.accountCode.trim() || !bankForm.accountName.trim() || !bankForm.bankName.trim() || !bankForm.bankAccountNo.trim() || !bankForm.ledgerAccountId || !bankForm.openingDate) { ElMessage.warning('请完整填写账户、银行账号、总账科目和启用日期'); return } if (bankForm.status === 'DISABLED' && !bankForm.disabledReason.trim()) { ElMessage.warning('停用账户必须填写原因'); return } saving.value = true; try { const payload = { accountCode: bankForm.accountCode.trim(), accountName: bankForm.accountName.trim(), bankName: bankForm.bankName.trim(), bankAccountNo: bankForm.bankAccountNo.trim(), currency: 'CNY', ledgerAccountId: bankForm.ledgerAccountId, openingDate: bankForm.openingDate, openingBalance: Number(bankForm.openingBalance), status: bankForm.status, disabledReason: bankForm.disabledReason.trim() }; if (bankForm.id) await api.updateBusinessBankAccount(bankForm.id, payload); else await api.createBusinessBankAccount(payload); ElMessage.success('企业银行账户已保存'); bankFormDialog.value = false; await bankQuery.refetch() } finally { saving.value = false } }
async function createSession() { if (!sessionForm.bankAccountId || !sessionForm.periodId) { ElMessage.warning('请选择银行账户和会计期间'); return } saving.value = true; try { const id = await api.createBankReconciliation({ ...sessionForm }); ElMessage.success('银行对账单已建立'); sessionDialog.value = false; periodId.value = sessionForm.periodId; await sessionQuery.refetch(); selectedId.value = id } finally { saving.value = false } }
function openImportDialog() { importPreview.value = undefined; applyAutoMatches.value = true; importDialog.value = true }
function resetImportPreview() { importPreview.value = undefined }
async function downloadStatementTemplate(command: string) { const format = command === 'csv' ? 'csv' : 'xlsx'; const result = await api.downloadBankStatementImportTemplate(format); downloadBlob(result.blob, result.fileName) }
async function previewStatementFile(file: UploadFile) { if (!selectedId.value || !file.raw) return; previewing.value = true; try { importPreview.value = await api.previewBankStatementImport(selectedId.value, file.raw); ElMessage.success('预检完成，请核对重复、错误和匹配建议后再提交') } finally { previewing.value = false } }
async function showImportBatch(batchId: number) { importPreview.value = await api.bankStatementImport(selectedId.value, batchId); importDialog.value = true }
async function commitStatementImport() { if (!importPreview.value || !selectedId.value) return; saving.value = true; try { const result = await api.commitBankStatementImport(selectedId.value, importPreview.value.batch.id, applyAutoMatches.value); ElMessage.success(`已导入 ${result.importedRows} 条，自动匹配 ${result.appliedAutoMatchRows} 条；重复 ${result.duplicateRows} 条、错误 ${result.errorRows} 条已跳过`); importDialog.value = false; await Promise.all([reloadDetail(), importBatchQuery.refetch()]) } finally { saving.value = false } }
function downloadBlob(blob: Blob, fileName: string) { const url = URL.createObjectURL(blob); const anchor = document.createElement('a'); anchor.href = url; anchor.download = fileName; anchor.click(); URL.revokeObjectURL(url) }
function openMatch(line: BankStatementLine) { selectedLine.value = line; const suggested = (detail.value?.fundCandidates || []).find((item) => item.sourceType === line.suggestedSourceType && item.sourceId === line.suggestedSourceId); const candidate = suggested || (detail.value?.fundCandidates || []).find((item) => item.direction === line.direction); Object.assign(matchForm, { candidateKey: candidate ? candidateKey(candidate) : '', amount: Math.min(Number(line.remainingAmount), Number(candidate?.remainingAmount || 0)), category: line.direction === 'CREDIT' ? 'SALES_COLLECTION' : candidate?.sourceType === 'SUPPLIER_PAYMENT' ? 'PURCHASE_PAYMENT' : 'OTHER_OPERATING_OUT', reason: suggested ? `按导入建议人工确认：${line.suggestedReason}` : '' }); matchDialog.value = true }
async function saveMatch() { const candidate = matchingCandidates.value.find((item) => candidateKey(item) === matchForm.candidateKey); if (!selectedLine.value || !candidate || Number(matchForm.amount) <= 0 || !matchForm.category) { ElMessage.warning('请选择系统资金、金额和现金流量分类'); return } saving.value = true; try { await api.matchBankStatement(selectedId.value, { statementLineId: selectedLine.value.id, sourceType: candidate.sourceType, sourceId: candidate.sourceId, matchedAmount: Number(matchForm.amount), cashFlowCategory: matchForm.category, reason: matchForm.reason.trim() }); ElMessage.success('逐笔匹配已保存'); matchDialog.value = false; await reloadDetail() } finally { saving.value = false } }
function openDifference(line: BankStatementLine) { selectedLine.value = line; Object.assign(differenceForm, { type: line.direction === 'DEBIT' ? 'BANK_FEE' : 'INTEREST', amount: Number(line.remainingAmount), category: line.direction === 'DEBIT' ? 'BANK_FEE' : 'OTHER_OPERATING_IN', reason: '', createVoucher: false }); differenceDialog.value = true }
async function saveDifference() { if (!selectedLine.value || Number(differenceForm.amount) <= 0 || !differenceForm.category || !differenceForm.reason.trim()) { ElMessage.warning('请完整填写差异类型、金额、分类和原因'); return } saving.value = true; try { await api.createBankDifference(selectedId.value, { statementLineId: selectedLine.value.id, differenceType: differenceForm.type, direction: selectedLine.value.direction, amount: Number(differenceForm.amount), cashFlowCategory: differenceForm.category, reason: differenceForm.reason.trim(), createVoucher: differenceForm.createVoucher }); ElMessage.success('差异说明已登记'); differenceDialog.value = false; await reloadDetail() } finally { saving.value = false } }
async function markPending(candidate: BankFundCandidate) { const { value } = await ElMessageBox.prompt('请说明该笔系统资金本期尚未在银行入账的原因', '登记银行未达项', { inputValidator: (text) => Boolean(String(text).trim()) || '必须填写未达原因' }); const category = candidate.direction === 'CREDIT' ? 'SALES_COLLECTION' : 'PURCHASE_PAYMENT'; await api.createBankPendingSystem(selectedId.value, { sourceType: candidate.sourceType, sourceId: candidate.sourceId, cashFlowCategory: category, reason: value }); ElMessage.success('系统侧未达项已登记'); await reloadDetail() }
async function reverseMatch(id: number) { const reason = await promptReason('撤销逐笔匹配'); await api.reverseBankMatch(id, reason); ElMessage.success('匹配已撤销'); await reloadDetail() }
async function reverseDifference(id: number) { const reason = await promptReason('撤销差异说明'); await api.reverseBankDifference(id, reason); ElMessage.success('差异说明已撤销，关联凭证已按状态作废或冲销'); await reloadDetail() }
async function reversePending(id: number) { const reason = await promptReason('撤销银行未达项'); await api.reverseBankPendingSystem(id, reason); ElMessage.success('未达项已撤销'); await reloadDetail() }
async function voidLine(line: BankStatementLine) { const reason = await promptReason('作废错误银行流水'); await api.voidBankStatementLine(line.id, reason); ElMessage.success('银行流水已保留痕迹并标记作废'); await reloadDetail() }
async function confirmSession() { const reason = await promptReason('确认银行对账'); await api.confirmBankReconciliation(selectedId.value, reason); ElMessage.success('银行对账已确认'); await reloadAll() }
async function reopenSession() { const reason = await promptReason('重新打开银行对账'); await api.reopenBankReconciliation(selectedId.value, reason); ElMessage.success('银行对账已重新打开'); await reloadAll() }
async function promptReason(title: string) { const { value } = await ElMessageBox.prompt('该操作会写入审计轨迹，请填写原因', title, { inputValidator: (text) => Boolean(String(text).trim()) || '必须填写原因' }); return String(value).trim() }
async function reloadDetail() { await queryClient.invalidateQueries({ queryKey: ['bank-reconciliation', selectedId.value] }); await detailQuery.refetch() }
async function reloadAll() { await Promise.all([sessionQuery.refetch(), reloadDetail()]) }
async function refresh() { await Promise.all([periodQuery.refetch(), bankQuery.refetch(), sessionQuery.refetch(), selectedId.value ? detailQuery.refetch() : Promise.resolve(), selectedId.value ? importBatchQuery.refetch() : Promise.resolve()]) }
function categoryOptions(direction?: string) { return cashCategories.filter((item) => item.direction === direction) }
function candidateKey(item: BankFundCandidate) { return `${item.sourceType}:${item.sourceId}` }
function cashCategoryName(value: string) { return cashCategories.find((item) => item.value === value)?.label || value }
function differenceName(value: string) { return differenceOptions.find((item) => item.value === value)?.label || value }
function directionName(value?: string) { return value === 'CREDIT' ? '收入' : '支出' }
function sessionStatus(value: string) { return ({ DRAFT: '草稿', CONFIRMED: '已确认', REOPENED: '已重开' } as Record<string, string>)[value] || value }
function sessionTag(value: string) { return value === 'CONFIRMED' ? 'success' : value === 'REOPENED' ? 'warning' : 'info' }
function lineStatus(value: string) { return ({ UNMATCHED: '未匹配', PARTIAL: '部分', MATCHED: '已匹配', EXPLAINED: '已说明', VOID: '已作废' } as Record<string, string>)[value] || value }
function lineTag(value: string) { return value === 'MATCHED' ? 'success' : value === 'EXPLAINED' ? 'warning' : value === 'VOID' ? 'info' : value === 'PARTIAL' ? 'warning' : 'danger' }
function validationName(value: string) { return ({ VALID: '可导入', DUPLICATE: '重复跳过', ERROR: '错误跳过' } as Record<string, string>)[value] || value }
function validationTag(value: string) { return value === 'VALID' ? 'success' : value === 'DUPLICATE' ? 'info' : 'danger' }
function matchActionName(value: string) { return ({ AUTO_MATCH: '将自动匹配', REVIEW: '建议待确认', UNMATCHED: '暂无候选', DUPLICATE: '重复跳过', INVALID: '错误跳过' } as Record<string, string>)[value] || value }
function eventAction(value: string) { return ({ CREATE: '建立对账单', IMPORT_STATEMENT: '导入银行流水', MATCH: '匹配系统资金', REVERSE_MATCH: '撤销匹配', EXPLAIN_DIFFERENCE: '登记差异', REVERSE_DIFFERENCE: '撤销差异', MARK_PENDING_SYSTEM: '登记系统未达', REVERSE_PENDING_SYSTEM: '撤销系统未达', VOID_STATEMENT_LINE: '作废流水', CONFIRM: '确认对账', REOPEN: '重新打开' } as Record<string, string>)[value] || value }
function isZero(value: number) { return Math.abs(Number(value || 0)) < 0.01 }
</script>

<style scoped>
.bank-subtitle { margin-top: 5px; font-size: 12px; }
.header-actions { display: flex; gap: 8px; }
.session-table { max-height: 310px; }
.detail-panel { margin-top: 14px; }
.balance-strip { display: grid; grid-template-columns: repeat(6, minmax(135px, 1fr)); gap: 8px; margin-bottom: 12px; }
.balance-strip > div { padding: 10px 12px; border: 1px solid #dfe6ea; border-radius: 7px; background: #fafcfd; }
.balance-strip span { display: block; color: #76858e; font-size: 11px; }
.balance-strip strong { display: block; margin-top: 5px; font-size: 15px; }
.balance-strip .danger { border-color: #efb4b4; background: #fff5f5; color: #c33232; }
.amount-danger { color: #d13b3b; font-weight: 600; }
.candidate-table, .bank-account-table { margin-top: 12px; }
.subsection-title { margin: 2px 0 8px; font-weight: 600; }
.pending-title { margin-top: 18px; }
.event-timeline { padding: 16px 12px 2px; }
.add-bank { margin-top: 12px; }
.import-actions { display: flex; gap: 8px; margin: 12px 0; }
.import-summary { display: grid; grid-template-columns: repeat(7, minmax(105px, 1fr)); gap: 8px; margin: 12px 0; }
.import-summary > div { padding: 10px 12px; border: 1px solid #dfe6ea; border-radius: 7px; background: #fafcfd; }
.import-summary span { display: block; color: #76858e; font-size: 11px; }
.import-summary strong { display: block; margin-top: 4px; font-size: 17px; }
.import-summary .summary-success { border-color: #b8dfc2; background: #f2fbf4; }
.import-summary .summary-warning { border-color: #ead29e; background: #fff9ec; }
.import-summary .summary-muted { color: #73818c; }
.import-summary .summary-danger { border-color: #efb4b4; background: #fff5f5; color: #c33232; }
.auto-match-check { margin-bottom: 10px; }
.import-preview-table { margin-top: 2px; }
.match-success { color: #238a45; font-weight: 600; }
.reason-danger { color: #c33232; }
.full { grid-column: 1 / -1; }
@media (max-width: 1200px) { .balance-strip { grid-template-columns: repeat(3, minmax(135px, 1fr)); } .import-summary { grid-template-columns: repeat(4, minmax(105px, 1fr)); } }
</style>
