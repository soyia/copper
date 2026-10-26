import axios, { type AxiosResponse } from 'axios'

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export interface Partner {
  id: number
  businessNo: string
  type: 'CUSTOMER' | 'SUPPLIER' | 'BOTH'
  typeMask: number
  entityType: 'COMPANY' | 'SOLE_PROPRIETOR' | 'INDIVIDUAL'
  name: string
  aliasName: string
  creditCode: string
  contactPerson: string
  primaryContactRole: string
  phone: string
  wechat: string
  email: string
  address: string
  creditLimit: number
  topFixed: boolean
  cooperationStartDate: string | null
  establishmentDate: string | null
  source: string
  tags: string
  status: string
  disabledReason: string
  disabledAt: string | null
  memo: string
  createdAt: string
  customerStatus: string
  customerDisabledReason: string
  customerDisabledAt: string | null
  customerLevel: string
  customerSettlementMethod: string
  customerSettlementDays: number
  customerTaxIncluded: boolean
  customerInvoiceType: string
  customerTaxRate: number
  preferredProducts: string
  supplierStatus: string
  supplierDisabledReason: string
  supplierDisabledAt: string | null
  supplierSettlementMethod: string
  supplierSettlementDays: number
  leadTimeDays: number
  minOrderAmount: number
  supplierTaxIncluded: boolean
  supplierInvoiceType: string
  supplierTaxRate: number
  qualificationMemo: string
}

export interface PartnerSummary {
  id: number
  businessNo: string
  type: 'CUSTOMER' | 'SUPPLIER' | 'BOTH'
  typeMask: number
  entityType: string
  name: string
  aliasName: string
  tags: string
  topFixed: boolean
  status: string
  customerStatus: string
  supplierStatus: string
  primaryContactName: string
  primaryContactRole: string
  primaryContactPhone: string
  primaryContactWechat: string
  primaryAddress: string
  customerReceivable: number
  customerOverdue: number
  customerCreditLimit: number
  customerAvailableCredit: number
  customerAdvance: number
  supplierPayable: number
  supplierPrepay: number
  customerLastBusinessDate: string | null
  supplierLastBusinessDate: string | null
  customerLastPaymentDate: string | null
  supplierLastPaymentDate: string | null
  lastFollowupAt: string | null
  nextFollowDate: string | null
  suppliedProducts: string
}

export interface SalesRiskPolicy {
  partnerId: number
  partnerName: string
  enabled: boolean
  creditControlEnabled: boolean
  creditLimit: number
  overdueControlEnabled: boolean
  maxOverdueDays: number
  marginControlEnabled: boolean
  minimumMarginRate: number
  versionNo: number
  changeReason: string
  updatedBy: number
  updatedByName: string
  updatedAt: string | null
}

export interface SalesRiskPolicySave {
  enabled: boolean
  creditControlEnabled: boolean
  creditLimit: number
  overdueControlEnabled: boolean
  maxOverdueDays: number
  marginControlEnabled: boolean
  minimumMarginRate: number
  changeReason: string
}

export interface SalesRiskExposure {
  receivableAmount: number
  confirmedUnshippedAmount: number
  currentDocumentAmount: number
  grossExposureAmount: number
  advanceCreditAmount: number
  acceptanceSecuredAmount: number
  netExposureAmount: number
  creditLimitAmount: number
  availableCreditAmount: number
  overLimitAmount: number
  overdueAmount: number
  maximumOverdueDays: number
}

export interface SalesRiskMargin {
  costAvailable: boolean
  revenueExcludingTax: number | null
  estimatedCostAmount: number | null
  estimatedGrossProfit: number | null
  estimatedMarginRate: number | null
  minimumMarginRate: number | null
  costBasis: string
}

export interface SalesRiskIssue {
  code: string
  title: string
  message: string
}

export interface SalesRiskEvaluation {
  documentType: 'SALES_ORDER' | 'OUTBOUND'
  documentId: number
  documentNo: string
  action: string
  policyEnabled: boolean
  policyVersion: number
  decision: 'NOT_CONTROLLED' | 'PASS' | 'BLOCK' | 'OVERRIDDEN'
  blocked: boolean
  canOverride: boolean
  marginVisible: boolean
  exposure: SalesRiskExposure
  margin: SalesRiskMargin
  issues: SalesRiskIssue[]
  evaluatedAt: string
}

export interface SalesRiskSnapshot {
  id: number
  documentType: 'SALES_ORDER' | 'OUTBOUND'
  documentId: number
  documentNo: string
  action: string
  sourceRevisionNo: number
  customerId: number
  policyVersion: number
  exposure: SalesRiskExposure
  margin: SalesRiskMargin
  issueCodes: string[]
  decision: 'PASS' | 'OVERRIDDEN'
  overrideReason: string
  operatorId: number
  operatorName: string
  createdAt: string
}

export type RiskExceptionStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | 'EXPIRED' | 'CONSUMED' | 'INVALIDATED'
export type RiskExceptionType = 'SPECIAL_PRICE' | 'SALES_RISK' | 'LARGE_PAYMENT' | 'FUND_ADJUSTMENT' | 'INVENTORY_ADJUSTMENT' | 'HIGH_RISK_REVERSAL'

export interface RiskExceptionPolicy {
  entityId: number
  largePaymentThreshold: number
  inventoryAdjustmentThreshold: number
  highRiskReversalThreshold: number
  approvalValidHours: number
  versionNo: number
  changeReason: string
  updatedByName: string
  updatedAt: string
}

export interface RiskExceptionRequest {
  id: number
  requestNo: string
  policyVersion: number
  exceptionType: RiskExceptionType
  riskLevel: string
  sourceType: string
  sourceId: number
  sourceNo: string
  actionCode: string
  sourceVersion: number
  sourceAmount: number
  currency: string
  title: string
  requestReason: string
  snapshotHash: string
  status: RiskExceptionStatus
  assignedEmployeeId: number
  assignedEmployeeName: string
  requestedEmployeeId: number
  requestedByName: string
  requestedAt: string
  expiresAt: string
  approvedEmployeeId: number
  approvedByName: string
  approvedAt: string | null
  decisionReason: string
  rejectedByName: string
  rejectedAt: string | null
  withdrawnByName: string
  withdrawnAt: string | null
  consumedByName: string
  consumedAt: string | null
  resultSourceType: string
  resultSourceId: number
  resultSourceNo: string
  versionNo: number
  createdAt: string
  updatedAt: string
}

export interface RiskExceptionEvent {
  id: number
  action: string
  fromStatus: string
  toStatus: string
  reason: string
  operatorEmployeeId: number
  operatorName: string
  createdAt: string
}

export interface RiskExceptionDetail {
  header: RiskExceptionRequest
  snapshotJson: string
  events: RiskExceptionEvent[]
}

export interface RiskExceptionSourceStatus {
  required: boolean
  reason: string
  canDirectApprove: boolean
  activeRequest: RiskExceptionRequest | null
}

export interface RiskExceptionDashboard {
  pendingCount: number
  approvedWaitingExecutionCount: number
  expiringWithin24HoursCount: number
  requestedByMeCount: number
}

export interface RiskExceptionApprover {
  employeeId: number
  employeeNo: string
  employeeName: string
  departmentName: string
  jobTitle: string
}

export type PartnerType = 'CUSTOMER' | 'SUPPLIER'

export type PartnerQuery = {
  type?: PartnerType
  keyword?: string
  status?: string
  limit?: number
  id?: number
}

export interface Contact {
  id: number
  partnerId: number
  name: string
  jobTitle: string
  department: string
  gender: number
  phone: string
  wechat: string
  email: string
  preferredMethod: string
  firstChoice: boolean
  birthday: string | null
  memo: string
  status: string
}

export interface Address {
  id: number
  partnerId: number
  name: string
  addressType: string
  provinceCode: string
  cityCode: string
  districtCode: string
  detail: string
  contactPerson: string
  phone: string
  firstChoice: boolean
  memo: string
  status: string
}

export interface Followup {
  id: number
  partnerId: number
  contactId: number | null
  contactName: string | null
  contactPhone: string | null
  followupType: string
  methodType: string
  occurredAt: string
  nextFollowDate: string | null
  status: string
  completedAt: string | null
  memo: string
}

export interface BankAccount {
  id: number
  partnerId: number
  accountName: string
  bankName: string
  bankAccount: string
  firstChoice: boolean
  status: string
  memo: string
}

export interface SpecialDate {
  id: number
  partnerId: number
  contactId: number | null
  contactName: string | null
  dateType: string
  title: string
  specialDate: string
  repeatYearly: boolean
  remindDaysBefore: number
  memo: string
  status: string
}

export interface Product {
  id: number
  categoryId: number
  categoryName: string
  productSeriesId: number
  productSeriesName: string
  specPresetId: number
  specSignature?: string
  sku: string
  name: string
  grade: string
  temper: string
  standard: string
  spec: string
  orderSpecText: string
  attributeJson: string
  barcode: string
  originalNumber: string
  unit: string
  defaultPrice: number
  status: string
  fieldValues: ProductFieldValue[]
  masterDataComplete: boolean
  missingRequiredFields: string[]
  identitySchemaVersionSnapshot?: number
  identityStatus?: 'PUBLISHED' | 'PENDING_REVIEW' | 'INCOMPLETE'
  identitySignature?: string
}

export type ProductDuplicateMatchType = 'EXACT' | 'SKU_CONFLICT' | 'INCOMPLETE' | 'NONE'

export interface ProductDuplicateCheck {
  result: ProductDuplicateMatchType
  existingProductId: number | null
  existingSku: string
  existingName: string
  identitySignature?: string
  schemaVersion?: number
  complete: boolean
  warnings: string[]
}

export interface ProductIdentityPublishResult {
  productId: number
  identitySchemaVersion: number
  identityStatus: 'PUBLISHED'
  identitySignature: string
}

export interface ProductDuplicateCheckRequest extends Partial<Product> {
  excludeProductId?: number
}

export interface ProductPackagingVariant {
  id: number
  productId: number
  variantCode: string
  name: string
  currentRevisionId: number
  revisionNo: number
  packageUnit: string
  conversionMode: 'FIXED' | 'ACTUAL_WEIGHT' | 'THEORETICAL'
  fixedBaseQuantity: number | null
  referenceBaseQuantity: number | null
  innerSpec: string
  netWeight: number | null
  grossWeight: number | null
  tareWeight: number | null
  lengthMm: number | null
  widthMm: number | null
  heightMm: number | null
  barcode: string
  supplierId: number | null
  supplierName?: string
  sourceDocumentId: number | null
  sourceDocumentRevisionId: number | null
  status: string
  changeReason: string
  publishedAt: string
}

export interface ProductPackagingVariantSave {
  variantCode?: string
  name: string
  packageUnit: string
  conversionMode: 'FIXED' | 'ACTUAL_WEIGHT' | 'THEORETICAL'
  fixedBaseQuantity?: number | null
  referenceBaseQuantity?: number | null
  innerSpec?: string
  netWeight?: number | null
  grossWeight?: number | null
  tareWeight?: number | null
  lengthMm?: number | null
  widthMm?: number | null
  heightMm?: number | null
  barcode?: string
  supplierId?: number | null
  sourceDocumentRevisionId?: number | null
  status: string
  changeReason: string
  expectedRevisionNo?: number
}

export interface ProductPackagingVariantRevision {
  id: number
  packagingVariantId: number
  productId: number
  variantCodeSnapshot: string | null
  nameSnapshot: string | null
  headStatusSnapshot: string | null
  headSnapshotAvailable: boolean
  revisionNo: number
  packageUnit: string
  conversionMode: 'FIXED' | 'ACTUAL_WEIGHT' | 'THEORETICAL'
  fixedBaseQuantity: number | null
  referenceBaseQuantity: number | null
  innerSpec: string
  netWeight: number | null
  grossWeight: number | null
  tareWeight: number | null
  lengthMm: number | null
  widthMm: number | null
  heightMm: number | null
  barcode: string
  supplierId: number | null
  sourceDocumentId: number | null
  sourceDocumentRevisionId: number | null
  contentHash: string
  revisionStatus: string
  changeReason: string
  publishedBy: number | null
  publishedByName: string
  publishedAt: string
  currentRevision: boolean
}

export interface SupplierProductOffer {
  id: number
  productId: number
  supplierId: number
  supplierName?: string
  supplierProductCode: string
  supplierModel: string
  currentRevisionId: number
  revisionNo: number
  purchaseUnit: string
  priceUnit: string
  quotedUnitPrice: number | null
  currencyCode: string
  priceIncludesTax: boolean
  taxRate: number
  minimumOrderQuantity: number | null
  orderMultiple: number | null
  leadTimeDays: number | null
  effectiveFrom: string
  effectiveTo: string | null
  packagingVariantRevisionId: number | null
  sourceDocumentId: number | null
  sourceDocumentRevisionId: number | null
  commercialTermsJson: string
  status: string
  changeReason: string
  publishedAt: string
}

export interface SupplierProductOfferRevision {
  id: number
  supplierProductOfferId: number
  supplierId: number
  supplierNameSnapshot: string | null
  productId: number
  supplierProductCodeSnapshot: string | null
  supplierModelSnapshot: string | null
  headStatusSnapshot: string | null
  headSnapshotAvailable: boolean
  revisionNo: number
  purchaseUnit: string
  priceUnit: string
  quotedUnitPrice: number | null
  currencyCode: string
  priceIncludesTax: boolean
  taxRate: number
  minimumOrderQuantity: number | null
  orderMultiple: number | null
  leadTimeDays: number | null
  effectiveFrom: string
  effectiveTo: string | null
  packagingVariantRevisionId: number | null
  sourceDocumentId: number | null
  sourceDocumentRevisionId: number | null
  commercialTermsJson: string
  contentHash: string
  revisionStatus: string
  changeReason: string
  publishedBy: number | null
  publishedByName: string
  publishedAt: string
  currentRevision: boolean
}

export interface SupplierProductOfferSave {
  supplierId?: number
  supplierProductCode?: string
  supplierModel?: string
  purchaseUnit: string
  priceUnit: string
  quotedUnitPrice?: number | null
  currencyCode: string
  priceIncludesTax: boolean
  taxRate: number
  minimumOrderQuantity?: number | null
  orderMultiple?: number | null
  leadTimeDays?: number | null
  effectiveFrom: string
  effectiveTo?: string | null
  packagingVariantRevisionId?: number | null
  sourceDocumentRevisionId?: number | null
  commercialTermsJson?: string
  status: string
  changeReason: string
  expectedRevisionNo?: number
}

export interface ProductPort {
  id: number
  productId: number
  portNo: number
  portRole: string
  connectionType: string
  gender: string
  nominalSize: string
  nominalSizeMm: number | null
  pressureClass: string
  angleDegree: number | null
  standardCode: string
  materialCode: string
  sortNo: number
  status: string
}

export type ProductPortSave = Omit<ProductPort, 'id' | 'productId'>

export interface ProductConductorComponent {
  id: number
  layerNo: number
  componentType: string
  materialCode: string
  strandCount: number | null
  diameterMm: number | null
  thicknessMm: number | null
  layDirection: string
  layPitchMm: number | null
  sortNo: number
  status: string
}

export type ProductConductorComponentSave = Omit<ProductConductorComponent, 'id'>

export interface ProductConductorConstruction {
  id: number
  productId: number
  constructionType: string
  nominalCrossSectionMm2: number | null
  strandCount: number | null
  singleWireDiameterMm: number | null
  conductorWidthMm: number | null
  conductorThicknessMm: number | null
  layDirection: string
  layPitchMm: number | null
  thermalClass: string
  insulationSystemCode: string
  constructionSignature: string
  status: string
  components: ProductConductorComponent[]
}

export interface ProductConductorConstructionSave {
  constructionType: string
  nominalCrossSectionMm2?: number | null
  strandCount?: number | null
  singleWireDiameterMm?: number | null
  conductorWidthMm?: number | null
  conductorThicknessMm?: number | null
  layDirection?: string
  layPitchMm?: number | null
  thermalClass?: string
  insulationSystemCode?: string
  status?: string
  components: ProductConductorComponentSave[]
}

export interface ProductTechnicalCharacteristic {
  id: number
  characteristicCode: string
  name: string
  valueType: string
  targetText: string
  minimumValue: number | null
  targetValue: number | null
  maximumValue: number | null
  unitName: string
  testMethod: string
  testCondition: string
  batchRequired: boolean
  sortNo: number
}

export type ProductTechnicalCharacteristicSave = Omit<ProductTechnicalCharacteristic, 'id'>

export interface ProductTechnicalSpec {
  id: number
  productId: number
  revisionNo: number
  standardCode: string
  effectiveFrom: string
  effectiveTo: string | null
  sourceDocumentId: number | null
  sourceDocumentRevisionId: number | null
  status: string
  changeReason: string
  publishedAt: string
  characteristics: ProductTechnicalCharacteristic[]
}

export interface ProductTechnicalSpecSave {
  standardCode?: string
  effectiveFrom: string
  effectiveTo?: string | null
  sourceDocumentRevisionId?: number | null
  changeReason: string
  characteristics: ProductTechnicalCharacteristicSave[]
  expectedRevisionNo?: number
}

export interface ProductBomItem {
  id: number
  componentProductId: number
  componentSku: string
  componentName: string
  quantity: number
  unit: string
  scrapRate: number
  issueMethod: string
  sortNo: number
}

export interface ProductBomItemSave {
  componentProductId: number
  quantity: number
  unit: string
  scrapRate?: number
  issueMethod?: string
  sortNo?: number
}

export interface ProductBom {
  id: number
  parentProductId: number
  bomCode: string
  name: string
  currentRevisionId: number
  revisionNo: number
  outputQuantity: number
  outputUnit: string
  scrapRate: number
  effectiveFrom: string
  effectiveTo: string | null
  status: string
  changeReason: string
  publishedAt: string
  items: ProductBomItem[]
}

export interface ProductBomRevision {
  id: number
  bomId: number
  parentProductId: number
  bomCodeSnapshot: string | null
  nameSnapshot: string | null
  headStatusSnapshot: string | null
  headSnapshotAvailable: boolean
  revisionNo: number
  outputQuantity: number
  outputUnit: string
  outputBaseQuantity: number
  outputBaseUnit: string
  outputEffectiveRevisionId: number | null
  outputConversionSnapshotJson: string
  scrapRate: number
  effectiveFrom: string
  effectiveTo: string | null
  contentHash: string
  revisionStatus: string
  changeReason: string
  publishedBy: number | null
  publishedByName: string
  publishedAt: string
  currentRevision: boolean
  items: ProductBomItem[]
}

export interface ProductBomSave {
  bomCode?: string
  name: string
  outputQuantity: number
  outputUnit: string
  scrapRate?: number
  effectiveFrom: string
  effectiveTo?: string | null
  status?: string
  changeReason: string
  items: ProductBomItemSave[]
}

export interface ProductSourceDocument {
  id: number
  documentCode: string
  title: string
  sourceType: string
  currentRevisionId: number
  currentRevisionNo: number
  status: string
  sourceUrl: string
  fileName: string
  fileSha256: string
  mimeType: string
  fileSize: number | null
  publicationDate: string | null
  internalVersion: string
  downloadedAt: string | null
  contentSnapshotJson: string
  changeReason: string
  publishedAt: string
}

export interface ProductSourceDocumentSave {
  expectedRevisionNo?: number
  documentCode?: string
  title: string
  sourceType: string
  sourceUrl?: string
  fileName?: string
  fileSha256?: string
  mimeType?: string
  fileSize?: number | null
  publicationDate?: string | null
  internalVersion?: string
  downloadedAt?: string | null
  contentSnapshotJson?: string
  status?: string
  changeReason: string
}

export interface ProductSourceDocumentRevision {
  id: number
  documentId: number
  documentCode: string
  revisionNo: number
  titleSnapshot: string
  sourceTypeSnapshot: string
  sourceUrl: string
  fileName: string
  fileSha256: string
  mimeType: string
  fileSize: number | null
  publicationDate: string | null
  internalVersion: string
  downloadedAt: string | null
  contentSnapshotJson: string
  headStatusSnapshot: string | null
  headSnapshotAvailable: boolean
  revisionStatus: string
  changeReason: string
  publishedBy: number | null
  publishedByName: string
  publishedAt: string
  currentRevision: boolean
}

export interface ProductSourceLink {
  id: number
  documentRevisionId: number
  documentId: number
  documentTitle: string
  documentRevisionNo: number
  targetType: string
  targetId: number
  pageNo: number | null
  sourceLocator: string
  rawValue: string
  normalizedValue: string
  confidence: number | null
  reviewStatus: string
  reviewComment: string
  reviewedBy: number | null
  reviewedByName: string
  reviewedAt: string | null
}

export interface ProductSourceLinkSave {
  documentRevisionId: number
  targetType: string
  targetId: number
  pageNo?: number | null
  sourceLocator?: string
  rawValue?: string
  normalizedValue?: string
  confidence?: number | null
}

export interface ProductSourceLinkReview {
  reviewStatus: 'APPROVED' | 'REJECTED'
  reviewComment?: string
}

export interface ProductSourceLinkReviewHistory {
  id: number
  sourceLinkId: number
  previousStatus: string
  reviewStatus: string
  reviewComment: string
  reviewedBy: number | null
  reviewedByName: string
  reviewedAt: string
}

export interface ProductQualityResult {
  id: number
  technicalCharacteristicId: number | null
  characteristicCode: string
  measuredText: string
  measuredValue: number | null
  unitName: string
  conclusion: string
  testMethodSnapshot: string
  testedAt: string | null
}

export type ProductQualityResultSave = Omit<ProductQualityResult, 'id'>

export interface ProductQualityCertificate {
  id: number
  inventoryBatchId: number
  certificateNo: string
  certificateType: string
  supplierId: number | null
  manufacturerName: string
  manufacturerBatchNo: string
  heatNo: string
  coilNo: string
  issueDate: string | null
  sourceDocumentId: number | null
  sourceDocumentRevisionId: number | null
  fileArchiveId: number | null
  verificationStatus: string
  status: string
  lockVersion: number
  reviewComment: string
  reviewedBy: number | null
  reviewedByName: string
  reviewedAt: string | null
  results: ProductQualityResult[]
}

export interface ProductQualityCertificateSave {
  certificateNo: string
  certificateType?: string
  supplierId?: number | null
  manufacturerName?: string
  manufacturerBatchNo?: string
  heatNo?: string
  coilNo?: string
  issueDate?: string | null
  sourceDocumentRevisionId?: number | null
  fileArchiveId?: number | null
  status?: string
  results: ProductQualityResultSave[]
  expectedLockVersion?: number
}

export interface ProductQualityCertificateReviewSave {
  reviewStatus: 'VERIFIED' | 'REJECTED' | 'VOID'
  reviewComment: string
  expectedLockVersion: number
}

export interface ProductQualityCertificateReviewHistory {
  id: number
  certificateId: number
  previousStatus: string
  reviewStatus: string
  reviewComment: string
  reviewedBy: number | null
  reviewedByName: string
  reviewedAt: string
}

export type ProductImportAction = 'CREATE' | 'CREATE_PENDING' | 'SAFE_UPDATE' | 'SKIP' | 'CONFLICT'
export type ProductImportRowResult = ProductImportAction | 'INVALID'

export interface ProductImportTemplateField {
  key: string
  label: string
  required: boolean
  description?: string
}

export interface ProductImportSeriesField {
  seriesFieldId: number
  key: string
  name: string
  displayName: string
  fieldType: string
  referenceType: ProductReferenceType | null
  unitName: string
  required: boolean
  skuDimension: boolean
  includeInSpec: boolean
  minValue: number | null
  maxValue: number | null
  decimalScale: number
  options: string[]
}

export interface ProductImportSeriesOption {
  id: number
  name: string
  categoryName: string
  aliases: string[]
  status: string
  fields: ProductImportSeriesField[]
}

export interface ProductImportUnitOption {
  id: number
  name: string
  abbreviation: string
  canonicalValue: string
  categoryName: string
}

export interface ProductImportTemplateInfo {
  templateVersion: string
  maxRows: number
  maxFileSize: number
  columns: ProductImportTemplateField[]
  series: ProductImportSeriesOption[]
  units: ProductImportUnitOption[]
}

export interface ProductImportConflict {
  id: number
  jobId: number
  rowId: number
  conflictType: string
  fieldKey: string
  sourceValue: string
  targetValue: string
  severity: string
  resolutionStatus: string
  resolutionNote: string
  message: string
}

export interface ProductImportRow {
  id: number
  jobId: number
  sourceRowNo: number
  rawData: Record<string, string>
  normalizedData: Record<string, unknown>
  sourceHash: string
  productSignature: string
  validationStatus: string
  validationMessage: string
  proposedAction: ProductImportAction
  decidedAction?: ProductImportAction | null
  decisionReason: string
  targetProductId: number | null
  targetProductSku: string
  commitStatus: 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED' | 'BLOCKED'
  commitError: string
  importedProductId: number | null
  rowVersion: number
}

export interface ProductImportJob {
  id: number
  jobNo: string
  templateVersion: string
  sourceFileName: string
  sourceFileFormat: string
  sourceFileSha256: string
  sourceFileSize: number
  mapping: Record<string, string>
  status: string
  recoverable: boolean
  leaseExpiresAt: string | null
  totalRows: number
  createRows: number
  safeUpdateRows: number
  skipRows: number
  conflictRows: number
  invalidRows: number
  committedRows: number
  failedRows: number
  lockVersion: number
  importedByName: string
  createdAt: string
  completedAt: string | null
}

export interface ProductImportPreview {
  job: ProductImportJob
  rows: ProductImportRow[]
  conflicts: ProductImportConflict[]
  idempotentReplay?: boolean
}

export interface ProductImportDecision {
  action: ProductImportAction
  targetProductId?: number | null
  reason?: string
  expectedRowVersion: number
}

export interface ProductImportCommitResult {
  jobId: number
  status: string
  createdRows: number
  updatedRows: number
  skippedRows: number
  failedRows: number
  lockVersion: number
  importedProductIds: number[]
}

export interface ProductFieldValue {
  seriesFieldId: number
  fieldId?: number
  productSeriesId?: number
  fieldName?: string
  displayName?: string
  value: string | number | null
  displayValue?: string
  fieldType?: string
  unitId?: number
  unitName?: string
  skuDimension?: boolean
  includeInSpec?: boolean
  searchable?: boolean
  orderVisible?: boolean
  seriesFieldStatus?: string
}

export interface ProductSeries {
  id: number
  categoryId: number
  categoryName: string
  code?: string
  name: string
  aliasNames: string
  packageUnitCategoryId: number
  priceUnitCategoryId: number
  identitySchemaVersion?: number
  status: string
}

export type ProductIdentitySourceType = 'SERIES_FIELD' | 'LEGACY_COLUMN' | 'STRUCTURED'

export interface ProductIdentityRule {
  id?: number
  dimensionKey: string
  sourceType: ProductIdentitySourceType
  seriesFieldId: number | null
  referenceType: ProductReferenceType | null
  required: boolean
  sortNo: number
  status: string
}

export interface ProductIdentitySchema {
  productSeriesId: number
  productSeriesName: string
  versionNo: number
  rules: ProductIdentityRule[]
  contentHash: string
  changeReason: string
  publishedAt: string
}

export interface ProductIdentitySchemaSave {
  expectedVersion: number
  rules: ProductIdentityRule[]
  changeReason: string
}

export type ProductReferenceType = 'GRADE' | 'TEMPER' | 'STANDARD'

export interface ProductReferenceValue {
  id: number
  referenceType: ProductReferenceType
  code: string
  name: string
  englishName: string
  issuingBody: string
  edition: string
  effectiveFrom: string | null
  effectiveTo: string | null
  aliases: string[]
  status: string
  versionNo: number
  changeReason: string
}

export interface ProductReferenceValueSave {
  expectedVersionNo?: number
  referenceType: ProductReferenceType
  code: string
  name: string
  englishName?: string
  issuingBody?: string
  edition?: string
  effectiveFrom?: string | null
  effectiveTo?: string | null
  aliases?: string[]
  status?: string
  changeReason: string
}

export interface ProductReferenceResolution {
  referenceValueId: number | null
  referenceType: ProductReferenceType
  sourceValue: string
  normalizedCode: string
  name: string
  resolved: boolean
  warnings: string[]
}

export type ProductUnitPurpose = 'INVENTORY' | 'PURCHASE' | 'PRICE' | 'PACKAGING' | 'SALES'

export interface ProductAllowedUnit {
  id: number
  productSeriesId: number
  purpose: ProductUnitPurpose
  unitId: number
  unitName: string
  abbreviation: string
  defaultUnit: boolean
  sortNo: number
  status: string
}

export interface ProductAllowedUnitSave {
  purpose: ProductUnitPurpose
  unitId: number
  defaultUnit?: boolean
  sortNo?: number
  status?: string
}

export interface ProductIdentityCollision {
  identitySignature: string
  productIds: number[]
  skus: string[]
}

export interface ProductIdentityPreflight {
  productSeriesId: number
  currentVersion: number
  proposedVersion: number
  collisionCount: number
  incompleteProductCount: number
  collisions: ProductIdentityCollision[]
  warnings: string[]
}

export interface ProductCategory {
  id: number
  parentId: number
  name: string
  sortNo: number
  status: string
}

export interface ProductStandard {
  id: number
  code: string
  name: string
  englishName: string
  standardStatus: string
  ccs: string
  ics: string
  publishDate: string | null
  implementationDate: string | null
  abolitionDate: string | null
  competentDepartment: string
  responsibleDepartment: string
  publisher: string
  memo: string
  sourceUrl: string
  status: string
}

export interface ProductTemper {
  id: number
  name: string
  code: string
  englishName: string
  status: string
}

export interface CustomField {
  id: number
  name: string
  fieldType: string
  valueType: string
  unitCategoryId: number
  optionJson: string
  status: string
  seriesCount: number
  productCount: number
  presetCount: number
}

export interface CustomFieldUsage {
  seriesFieldId: number
  productSeriesId: number
  productSeriesName: string
  categoryId: number
  categoryName: string
  displayName: string
  unitId: number
  unitName: string
  required: boolean
  skuDimension: boolean
  includeInSpec: boolean
  searchable: boolean
  orderVisible: boolean
  status: string
  productCount: number
  presetCount: number
}

export interface SeriesField {
  id: number
  productSeriesId: number
  fieldId: number
  fieldName: string
  displayName: string
  fieldType: string
  valueType: string
  optionJson: string
  unitCategoryId: number
  unitId: number
  unitName: string
  required: boolean
  skuDimension: boolean
  includeInSpec: boolean
  searchable: boolean
  orderVisible: boolean
  minValue?: number
  maxValue?: number
  decimalScale: number
  sortNo: number
  status: string
  productCount: number
  presetCount: number
}

export interface SpecPreset {
  id: number
  productSeriesId: number
  productSeriesName: string
  name: string
  model: string
  attributeJson: string
  fieldValues: SpecPresetFieldValue[]
  specText: string
  skuSuffix: string
  sortNo: number
  status: string
}

export interface SpecPresetFieldValue {
  seriesFieldId: number
  fieldId?: number
  fieldName?: string
  value: string | number | null
}

export interface UnitCategory {
  id: number
  name: string
  description: string
  status: string
}

export interface Unit {
  id: number
  unitCategoryId: number
  unitCategoryName: string
  name: string
  abbreviation: string
  description: string
  unitKind: string
  aliases: string[]
  status: string
}

export type PackagingConversionMode = 'FIXED' | 'ACTUAL_WEIGHT' | 'THEORETICAL'
export type PackagingRoundingPolicy = 'REJECT' | 'ROUND_UP' | 'ROUND_DOWN'
export type PackagingPurpose = 'PURCHASE' | 'SALES' | 'OUTBOUND' | 'PURCHASE_ORDER' | 'INBOUND' | 'SALES_ORDER'

export interface PackagingRule {
  id?: number
  productId?: number
  versionNo?: number
  parentUnit: string
  childUnit: string
  conversionMode: PackagingConversionMode
  fixedChildQuantity: number | null
  referenceBaseQuantity: number | null
  breakable: boolean
  repackable: boolean
  purchaseAllowed: boolean
  salesAllowed: boolean
  outboundAllowed: boolean
  integerOnly: boolean
  sortNo: number
  status: string
  ruleKey?: string
  sourceLevel?: string
  overridden?: boolean
}

export type ShelfLifeMode = 'NONE' | 'PERIOD'
export type ShelfLifeUnit = 'DAY' | 'MONTH' | 'YEAR'

export interface PackageDefinition {
  productId: number
  packageUnit: string
  barcode: string
  shelfLifeMode: ShelfLifeMode
  shelfLifeValue?: number | null
  shelfLifeUnit?: ShelfLifeUnit | ''
  revisionId?: number
  updatedAt?: string
}

export interface PackageDefinitionSave {
  packageUnit: string
  barcode: string
  shelfLifeMode: ShelfLifeMode
  shelfLifeValue?: number | null
  shelfLifeUnit?: ShelfLifeUnit | ''
}

export interface PackageBarcodeMatch extends PackageDefinition {
  sku: string
  productName: string
}

export interface PackagingProfile {
  productId: number
  productName: string
  baseUnit: string
  minimumSalesUnit: string
  minimumOutboundUnit: string
  defaultPurchaseUnit: string
  defaultSalesUnit: string
  roundingPolicy: PackagingRoundingPolicy
  versionNo: number
  sourceLevel?: string
  inherited?: boolean
  templateId?: number | null
  templateName?: string
  templateRevisionId?: number | null
  templateRevisionNo?: number | null
  productSeriesId?: number | null
  productSeriesName?: string
  overrideRevisionId?: number | null
  effectiveRevisionId?: number | null
  contentHash?: string
  overrideFields?: string[]
  warnings?: string[]
  rules: PackagingRule[]
}

export type PackagingProfileSave = Omit<PackagingProfile, 'productId' | 'productName' | 'versionNo'> & {
  expectedVersionNo: number
  changeReason?: string
  packageDefinitions?: PackageDefinitionSave[]
}

export interface PackagingTemplate {
  id: number
  code: string
  name: string
  description: string
  status: string
  lockVersion: number
  currentRevisionId?: number
  currentRevisionNo: number
  baseUnit?: string
  minimumSalesUnit?: string
  minimumOutboundUnit?: string
  defaultPurchaseUnit?: string
  defaultSalesUnit?: string
  roundingPolicy?: PackagingRoundingPolicy
  contentHash?: string
  seriesCount: number
  productCount: number
  overrideProductCount: number
  ruleCount?: number
  updatedAt?: string
  rules?: PackagingRule[]
}

export interface PackagingTemplateRevision {
  revisionId: number
  revisionNo: number
  status: string
  baseUnit: string
  contentHash?: string
  changeReason?: string
  publishedAt?: string
}

export interface PackagingTemplateSave {
  expectedRevisionNo?: number
  code: string
  name: string
  description?: string
  status?: string
  changeReason: string
  baseUnit: string
  minimumSalesUnit: string
  minimumOutboundUnit: string
  defaultPurchaseUnit: string
  defaultSalesUnit: string
  roundingPolicy: PackagingRoundingPolicy
  rules: PackagingRule[]
}

export interface SeriesPackagingBinding {
  productSeriesId: number
  productSeriesName: string
  categoryName?: string
  productCount: number
  templateId?: number | null
  templateName?: string
  templateRevisionId?: number | null
  templateRevisionNo?: number | null
  bindingVersionNo: number
  lockVersion?: number
  overrideProductCount: number
  status?: string
  warnings?: string[]
}

export interface PackagingHistory {
  effectiveRevisionId: number
  versionNo: number
  sourceLevel: string
  sourceName?: string
  templateName?: string
  templateRevisionNo?: number | null
  overrideRevisionId?: number | null
  contentHash?: string
  changeReason?: string
  createdAt?: string
  publishedAt?: string
  baseUnit: string
  packagingSummary?: string
}

export interface ProductPackagingSummary {
  productId: number
  versionNo: number
  sourceLevel: string
  templateName?: string
  templateRevisionNo?: number | null
  overrideCount: number
  contentHash?: string
  packagingSummary?: string
  warnings?: string[]
}

export interface PackagingComponent {
  unit: string
  quantity: number
  baseQuantity: number
  conversionMode: string
}

export interface PackagingPlan {
  requestedQuantity: number
  requestedUnit: string
  baseQuantity: number
  baseUnit: string
  pricingQuantity: number
  priceUnit: string
  actualWeightRequired: boolean
  rounded: boolean
  roundingPolicy: PackagingRoundingPolicy
  suggestion: string
  components: PackagingComponent[]
  snapshotJson: string
  estimated?: boolean
  effectiveRevisionId?: number | null
  snapshotHash?: string
}

export interface PackagingPlanRequest {
  quantity: number
  quantityUnit?: string
  actualBaseQuantity?: number
  priceUnit?: string
  purpose: PackagingPurpose
  expectedEffectiveRevisionId?: number
  expectedSnapshotHash?: string
  supplierOfferRevisionId?: number
  packagingVariantRevisionId?: number
}

export interface ProductPurchaseUnitOption {
  name: string
  unitId: number | null
  unitName: string
  exchangeRate: number | null
  primaryUnit: boolean
}

export interface ProductPriceSuggestion {
  unitPrice: number
  priceUnit: string
  source: string
  sourceName: string
  explanation?: string
  ruleVersionId?: number
  priceIncludesTax?: boolean | null
  taxRate?: number | null
  components?: PriceComponent[]
  warnings?: string[]
  snapshotJson?: string
}

export interface ProductSupplierOfferContext {
  supplierOfferId: number
  supplierOfferRevisionId: number
  supplierOfferRevisionNo: number
  supplierId: number
  purchaseUnit: string
  priceUnit: string
  quotedUnitPrice: number | null
  currencyCode?: string
  priceIncludesTax: boolean
  taxRate: number
  minimumOrderQuantity: number | null
  orderMultiple: number | null
  leadTimeDays: number | null
  effectiveFrom: string
  effectiveTo: string | null
  packagingVariantId: number | null
  packagingVariantRevisionId: number | null
  packagingVariantRevisionNo: number | null
  packagingVariantName: string
  packageUnit: string
  conversionMode: 'FIXED' | 'ACTUAL_WEIGHT' | 'THEORETICAL' | string
  fixedBaseQuantity: number | null
  referenceBaseQuantity: number | null
  snapshotJson: string
  snapshotHash: string
}

export interface ProductPurchaseOptions {
  productId: number
  defaultPriceUnit: string
  priceUnits: ProductPurchaseUnitOption[]
  packageUnits: ProductPurchaseUnitOption[]
  priceSuggestion: ProductPriceSuggestion
  supplierOffer: ProductSupplierOfferContext | null
}

export type ProductUnitOptionsQuery = {
  partnerId?: number
  partnerType?: 'SUPPLIER' | 'CUSTOMER'
  priceUnit?: string
  quantity?: number
  businessDate?: string
}

export interface PriceComponent {
  code: string
  name: string
  sourceAmount: number
  unitPriceContribution: number
}

export interface PriceBenchmark {
  id: number
  benchmarkCode: string
  benchmarkName: string
  versionNo: number
  priceUnit: string
  unitPrice: number
  priceIncludesTax: boolean
  taxRate: number
  effectiveDate: string
  sourceType: 'MANUAL' | 'MARKET_REFERENCE' | 'SUPPLIER_NOTICE'
  sourceNote: string
  changeReason: string
  operatorId: number
  operatorName: string
  createdAt: string
}

export interface PriceRule {
  id: number
  ruleCode: string
  ruleName: string
  versionNo: number
  businessType: 'SALES' | 'PURCHASE'
  scopeType: 'STANDARD' | 'CUSTOMER_LEVEL' | 'CUSTOMER' | 'SUPPLIER'
  partnerId: number
  partnerName: string
  customerLevel: string
  agreementType: 'STANDARD' | 'AGREEMENT' | 'SPECIAL'
  productId: number
  productSku: string
  productName: string
  productSpec: string
  priceUnit: string
  minimumQuantity: number
  maximumQuantity: number | null
  pricingMode: 'FORMULA' | 'FIXED'
  benchmarkCode: string
  fixedUnitPrice: number
  processingFee: number
  specificationFee: number
  packagingFee: number
  freightFee: number
  priceIncludesTax: boolean
  taxRate: number
  effectiveFrom: string
  effectiveTo: string | null
  changeReason: string
  status: 'PUBLISHED' | 'WITHDRAWN'
  operatorId: number
  operatorName: string
  createdAt: string
}

export interface PricePreview {
  unitPrice: number
  priceUnit: string
  sourceCode: string
  sourceName: string
  explanation: string
  ruleVersionId: number
  ruleCode: string
  ruleVersionNo: number
  benchmarkVersionId: number
  benchmarkCode: string
  benchmarkVersionNo: number
  priceIncludesTax: boolean
  taxRate: number
  agreementType: string
  components: PriceComponent[]
  warnings: string[]
  snapshotJson: string
}

export interface Warehouse {
  id: number
  name: string
  location: string
  receivingAddresses: string
  status: string
}

export interface InventoryBatch {
  id: number
  productId: number
  sku: string
  productName: string
  grade: string
  spec: string
  warehouseId: number
  warehouseName: string
  batchNo: string
  sourceInboundId?: number | null
  sourceInboundNo?: string | null
  quantityIn: number
  quantityAvailable: number
  quantityReserved: number
  quantityFree: number
  quantityInTransit: number
  baseUnit?: string
  packagingEffectiveRevisionId?: number
  packagingProfileSnapshotJson?: string
  packagingSnapshotHash?: string
  packagingTrackingMode?: 'QUANTITY_ONLY' | 'STRUCTURED' | 'ACTUAL_INSTANCE' | string
  packageBarcode?: string
  unitCost: number
  amountCost: number
  status: string
  receivedAt: string
  productionDate?: string | null
  shelfLifeMode?: ShelfLifeMode
  shelfLifeValue?: number | null
  shelfLifeUnit?: ShelfLifeUnit | ''
  expirationDate?: string | null
}

export interface InventoryPackageInstance {
  id: number
  batchId: number
  productId: number
  packageCode: string
  unitName: string
  actualBaseQuantity: number
  remainingBaseQuantity: number
  baseUnit: string
  status: string
}

export interface InventoryCostPolicy {
  policyCode: string
  costingMethod: 'BATCH_ACTUAL' | string
  quantityScale: number
  unitCostScale: number
  amountScale: number
  roundingMode: string
  retroactiveRecalculation: string
  effectiveFrom: string
}

export interface InventoryOpeningCostAlignment {
  id: number
  batchId: number
  batchNo: string
  productId: number
  sku: string
  productName: string
  warehouseId: number
  warehouseName: string
  quantitySnapshot: number
  unitCostSnapshot: number
  batchAmountBefore: number
  batchAmountAfter: number
  differenceAmount: number
  stockBalanceAmountSnapshot: number
  alignmentReason: string
  alignedAt: string
}

export interface InventoryEvent {
  id: number
  documentType: string
  documentId: number
  action: string
  reason: string
  operatorId: number
  operatorName: string
  createdAt: string
}

export interface InventoryMovement {
  id: number
  movementNo: string
  movementType: string
  productId: number
  productName: string
  warehouseId: number
  warehouseName: string
  batchId: number
  batchNo: string
  quantity: number
  unitCost: number | null
  amount: number | null
  refType: string
  refId: number
  sourceDocumentType: string
  sourceDocumentId: number
  sourceDocumentNo: string
  memo: string
  occurredAt: string
}

export interface InventoryReconciliationRow {
  productId: number
  sku: string
  productName: string
  warehouseId: number
  warehouseName: string
  batchQuantity: number
  balanceQuantity: number
  quantityDifference: number
  reservedQuantity: number
  freeQuantity: number
  inTransitQuantity: number
  trackedBatchQuantity: number
  trackedPackageQuantity: number
  packageDifference: number
  batchAmountCost: number | null
  balanceAmountCost: number | null
  costDifference: number | null
  negativeSourceCount: number
  unavailableBatchCount: number
  status: 'MATCH' | 'RISK' | 'MISMATCH'
}

export interface InventoryTransferRow {
  id: number
  orderNo: string
  fromWarehouseId: number
  fromWarehouseName: string
  toWarehouseId: number
  toWarehouseName: string
  businessDate: string
  expectedArrivalDate: string | null
  status: string
  totalQuantity: number
  totalCost: number | null
  memo: string
  dispatchedAt: string | null
  receivedAt: string | null
  createdAt: string
}

export interface InventoryTransferItem {
  id: number
  productId: number
  sku: string
  productName: string
  spec: string
  sourceBatchId: number
  sourceBatchNo: string
  targetBatchId: number
  targetBatchNo: string
  quantity: number
  baseUnit: string
  unitCost: number | null
  amountCost: number | null
  packageInstanceIds: number[]
}

export interface InventoryTransferDetail {
  header: InventoryTransferRow
  cancelReason: string
  reversalReason: string
  items: InventoryTransferItem[]
  events: InventoryEvent[]
}

export interface InventoryStocktakeRow {
  id: number
  orderNo: string
  warehouseId: number
  warehouseName: string
  businessDate: string
  status: string
  totalGainQuantity: number
  totalLossQuantity: number
  totalCostDifference: number | null
  memo: string
  confirmedAt: string | null
  createdAt: string
}

export interface InventoryStocktakeItem {
  id: number
  productId: number
  sku: string
  productName: string
  spec: string
  batchId: number
  batchNo: string
  systemQuantity: number
  countedQuantity: number
  differenceQuantity: number
  baseUnit: string
  unitCost: number | null
  costDifference: number | null
  differenceReason: string
}

export interface InventoryStocktakeDetail {
  header: InventoryStocktakeRow
  cancelReason: string
  reversalReason: string
  items: InventoryStocktakeItem[]
  events: InventoryEvent[]
}

export interface PurchaseReturnRow {
  id: number
  orderNo: string
  inboundId: number
  inboundNo: string
  purchaseOrderId: number
  purchaseOrderNo: string
  supplierId: number
  supplierName: string
  warehouseId: number
  warehouseName: string
  businessDate: string
  status: string
  totalQuantity: number
  totalAmount: number
  priceIncludesTax: boolean
  taxRateSummary: string
  taxRuleSnapshot: string
  amountExcludingTax: number
  taxAmount: number
  taxRoundingDifference: number
  payableReductionAmount: number
  supplierCreditAmount: number
  reason: string
  memo: string
  confirmedAt: string | null
  createdAt: string
}

export interface PurchaseReturnItem {
  id: number
  inboundItemId: number
  productId: number
  sku: string
  productName: string
  spec: string
  batchId: number
  batchNo: string
  quantity: number
  baseUnit: string
  inventoryUnitCost: number | null
  inventoryAmountCost: number | null
  payableUnitCost: number
  amount: number
  priceIncludesTax: boolean
  taxRate: number
  amountExcludingTax: number
  taxAmount: number
  taxRoundingDifference: number
  packageInstanceIds: number[]
}

export interface PurchaseReturnDetail {
  header: PurchaseReturnRow
  cancelReason: string
  reversalReason: string
  items: PurchaseReturnItem[]
  events: InventoryEvent[]
}

export interface InventoryCostAdjustmentRow {
  id: number
  adjustmentNo: string
  businessDate: string
  status: string
  totalDifferenceAmount: number
  reason: string
  memo: string
  confirmedAt: string | null
  createdAt: string
}

export interface InventoryCostAdjustmentItem {
  id: number
  productId: number
  sku: string
  productName: string
  spec: string
  warehouseId: number
  warehouseName: string
  batchId: number
  batchNo: string
  quantitySnapshot: number
  oldUnitCost: number
  newUnitCost: number
  oldAmountCost: number
  newAmountCost: number
  differenceAmount: number
}

export interface InventoryCostAdjustmentDetail {
  header: InventoryCostAdjustmentRow
  cancelReason: string
  reversalReason: string
  items: InventoryCostAdjustmentItem[]
  events: InventoryEvent[]
}

export type LandedCostAllocationMethod = 'QUANTITY' | 'WEIGHT' | 'AMOUNT' | 'MANUAL'
export type LandedCostAllocationStatus = 'DRAFT' | 'CONFIRMED' | 'CANCELLED' | 'REVERSED'

export interface LandedCostSourceOption {
  id: number
  documentNo: string
  partnerId: number
  partnerName: string
  businessDate: string
  summary: string
  amountExcludingTax: number
  taxAmount: number
  totalAmount: number
  capitalizableAmount: number
  inputTaxTreatment: 'DEDUCTIBLE' | 'INCLUDED_IN_COST'
  status: string
  allocated: boolean
}

export interface LandedCostInboundOption {
  inboundId: number
  inboundNo: string
  inboundDate: string
  supplierId: number
  supplierName: string
  inboundItemId: number
  batchId: number
  batchNo: string
  productId: number
  sku: string
  productName: string
  spec: string
  warehouseId: number
  warehouseName: string
  originalQuantity: number
  currentQuantity: number
  baseUnit: string
  amountExcludingTax: number
  suggestedWeight: number
}

export interface LandedCostOptions { sources: LandedCostSourceOption[]; inboundItems: LandedCostInboundOption[] }

export interface LandedCostAllocationRow {
  id: number
  allocationNo: string
  treasuryDocumentId: number
  treasuryDocumentNo: string
  feePartnerName: string
  businessDate: string
  allocationMethod: LandedCostAllocationMethod
  status: LandedCostAllocationStatus
  feeAmount: number
  allocatedAmount: number
  capitalizedAmount: number
  expensedAmount: number
  memo: string
  confirmedAt: string | null
  createdAt: string
}

export interface LandedCostAllocationItem {
  id: number
  inboundId: number
  inboundNo: string
  inboundItemId: number
  originalBatchId: number
  batchNo: string
  productId: number
  sku: string
  productName: string
  spec: string
  warehouseId: number
  warehouseName: string
  originalQuantity: number
  remainingQuantitySnapshot: number
  baseUnit: string
  basisValue: number
  allocatedAmount: number
  landedUnitAmount: number
  capitalizedAmount: number
  expensedAmount: number
}

export interface LandedCostBatchAllocation {
  id: number
  allocationItemId: number
  batchId: number
  batchNo: string
  warehouseName: string
  quantitySnapshot: number
  oldUnitCost: number
  allocatedAmount: number
  newUnitCost: number
  oldAmountCost: number
  newAmountCost: number
}

export interface LandedCostEvent { id: number; action: string; fromStatus: string; toStatus: string; reason: string; operatorName: string; createdAt: string }
export interface LandedCostAllocationDetail {
  header: LandedCostAllocationRow
  cancelReason: string
  reversalReason: string
  postingBatchId: number
  accountingVoucherId: number
  accountingVoucherNo: string
  items: LandedCostAllocationItem[]
  batches: LandedCostBatchAllocation[]
  events: LandedCostEvent[]
}

export interface LandedCostAllocationCreate {
  treasuryDocumentId: number
  businessDate: string
  allocationMethod: LandedCostAllocationMethod
  memo: string
  items: { inboundItemId: number; basisValue: number; allocatedAmount: number }[]
}

export interface InboundRow {
  id: number
  orderNo: string
  purchaseOrderId: number
  purchaseOrderNo: string | null
  supplierName: string
  warehouseName: string
  sourceType: string
  businessDate: string
  sendNumber: string
  totalQuantity: number
  quantitySummary: string
  totalAmount: number
  paidAmount: number
  paymentStatus: string
  status: string
}

export interface InboundDetail extends InboundRow {
  supplierId: number
  warehouseId: number
  sourceNote: string
  sendDate: string | null
  originalAmount: number
  discountRate: number
  adjustmentAmount: number
  payableAmount: number
  rejectionReason?: string
  submittedAt?: string | null
  withdrawnAt?: string | null
  rejectedAt?: string | null
  auditedAt?: string | null
  memo: string
  items: InboundItemDetail[]
  nonTradeRecords: InboundNonTradeRecord[]
}

export interface InboundItemDetail {
  id: number
  productId: number
  purchaseOrderItemId?: number
  sku: string
  productName: string
  grade: string
  temper: string
  standard: string
  spec: string
  unit: string
  inventoryBatchId: number
  batchNo: string
  serialNumber: string
  quantity: number
  currentBookQuantity?: number | null
  reservedQuantity?: number | null
  currentFreeQuantity?: number | null
  nonTradeIssuedQuantity?: number
  requestedQuantity?: number
  requestedUnit?: string
  baseUnit?: string
  pricingQuantity?: number
  packagingPlanJson?: string
  packageInstances?: Array<{ packageCode: string; actualBaseQuantity: number }>
  packagingRevisionId?: number
  packagingSnapshotHash?: string
  packageQuantity: number
  priceUnit: string
  packageUnit: string
  packageBarcode?: string
  unitCost: number
  amount: number
  productionDate: string | null
  shelfLifeMode?: ShelfLifeMode
  shelfLifeValue?: number | null
  shelfLifeUnit?: ShelfLifeUnit | ''
  expirationDate?: string | null
}

export interface InboundNonTradeRecord {
  id: number
  documentNo: string
  businessType: 'SAMPLE_OUT' | 'INTERNAL_USE' | 'NORMAL_LOSS' | 'OTHER_OUT'
  businessDate: string
  productId: number
  batchId: number
  batchNo: string
  quantity: number
  baseUnit: string
  recipient: string
  handlerName: string
  status: 'ISSUED_PENDING_SIGNATURE' | 'CONFIRMED' | 'REVERSED'
  issuedAt: string | null
}

export interface InboundAttachment {
  id: number
  inboundId: number
  fileName: string
  contentType: string
  fileSize: number
  sha256: string
  uploadedAt: string
}

export type EvidenceDocumentType =
  | 'INBOUND' | 'OUTBOUND' | 'PURCHASE_ORDER' | 'SALES_ORDER' | 'SALES_RETURN'
  | 'PAYMENT' | 'SUPPLIER_PAYMENT' | 'INVENTORY_TRANSFER' | 'INVENTORY_STOCKTAKE'
  | 'PURCHASE_RETURN' | 'INVENTORY_COST_ADJUSTMENT' | 'TAX_INVOICE' | 'ACCOUNTING_VOUCHER'
  | 'BANK_RECONCILIATION' | 'PERIOD_CLOSE' | 'PARTNER_FUND_ADJUSTMENT' | 'TREASURY_DOCUMENT'
  | 'LANDED_COST_ALLOCATION' | 'INVENTORY_NON_TRADE'

export interface EvidenceAttachment {
  id: number
  documentType: EvidenceDocumentType
  documentId: number
  evidenceType: 'ORIGINAL_DOCUMENT' | 'SIGNED_RECEIPT' | 'SIGNED_STAMPED_COPY' | 'DELIVERY_PROOF' | 'PAYMENT_PROOF' | 'VERIFICATION_PROOF' | 'VISUAL_COPY' | 'OTHER'
  sourceChannel: 'UPLOAD' | 'TAX_DIGITAL_ACCOUNT' | 'IMPORT' | 'SCAN' | 'SYSTEM'
  originalFileName: string
  archiveFileName: string
  contentType: string
  fileSize: number
  sha256: string
  status: 'ACTIVE' | 'VOIDED'
  note: string
  uploadedByName: string
  uploadedAt: string
  voidedByName: string
  voidReason: string
  voidedAt: string | null
  canDelete: boolean
}

export interface EvidenceOutputRecord {
  id: number
  documentType: EvidenceDocumentType
  documentId: number
  documentNoSnapshot: string
  statusSnapshot: string
  outputType: 'PRINT_OR_PDF'
  templateVersion: string
  outputFileName: string
  snapshotHash: string
  operatorName: string
  createdAt: string
}

export interface InboundAuditLog {
  id: number
  inboundId: number
  action: 'SUBMIT' | 'WITHDRAW' | 'APPROVE' | 'REJECT'
  reason: string
  operatorId: number
  operatorName: string
  snapshotId: number
  reviewRound: number
  createdAt: string
}

export interface InboundAuditLogDetail {
  auditLog: InboundAuditLog
  snapshot: InboundReviewSnapshot | null
  previousSnapshot: InboundReviewSnapshot | null
}

export interface InboundReviewSnapshot {
  id: number
  inboundId: number
  roundNo: number
  orderNo: string
  purchaseOrderId: number
  purchaseOrderNo: string
  supplierId: number
  supplierName: string
  warehouseId: number
  warehouseName: string
  sourceType: string
  sourceNote: string
  businessDate: string
  sendDate: string | null
  sendNumber: string
  originalAmount: number
  discountRate: number
  adjustmentAmount: number
  totalQuantity: number
  quantitySummary: string
  totalAmount: number
  memo: string
  snapshotHash: string
  captureSource: 'SUBMIT' | 'LEGACY_ACTION'
  submittedByName: string
  createdAt: string
  items: InboundReviewSnapshotItem[]
  attachments: InboundReviewSnapshotAttachment[]
}

export interface InboundReviewSnapshotItem {
  id: number
  lineNo: number
  productId: number
  sku: string
  productName: string
  spec: string
  batchNo: string
  serialNumber: string
  requestedQuantity: number
  requestedUnit: string
  quantity: number
  baseUnit: string
  packageQuantity: number
  packageUnit: string
  priceUnit: string
  pricingQuantity: number
  packageBarcode: string
  unitCost: number
  amount: number
  productionDate: string | null
  shelfLifeMode: ShelfLifeMode
  shelfLifeValue: number | null
  shelfLifeUnit: ShelfLifeUnit | ''
  expirationDate: string | null
  packagingPlanJson: string
}

export interface InboundReviewSnapshotAttachment {
  id: number
  originalAttachmentId: number
  fileName: string
  contentType: string
  fileSize: number
  sha256: string
  originalUploadedAt: string | null
}

export interface OutboundRow {
  id: number
  orderNo: string
  salesOrderId: number
  salesOrderNo: string | null
  customerName: string
  warehouseName: string
  businessDate: string
  actualShippedAt: string | null
  sourceType: 'DIRECT' | 'SALES_ORDER'
  sourceChannel: 'PHONE' | 'WECHAT' | 'ON_SITE' | 'SALES_ORDER' | 'OTHER'
  entryMode: 'NORMAL' | 'QUICK' | 'BACKFILL'
  completenessStatus: 'COMPLETE' | 'TO_COMPLETE'
  settlementStatus: 'READY' | 'PENDING_PRICING' | 'SETTLED'
  totalQuantity: number
  packageQuantity: number
  totalAmount: number
  paidAmount: number
  paymentStatus: string
  status: string
}

export interface OutboundDetail extends OutboundRow {
  salesOrderId: number
  customerId: number
  addressId: number
  addressSnapshot: string
  warehouseId: number
  lateEntryReason: string
  allowOverShipment: boolean
  overShipmentReason: string
  originalAmount: number
  discountRate: number
  adjustmentAmount: number
  receivableAmount: number
  shipmentPaymentPolicy: 'NONE' | 'SHIPMENT_PAID' | 'ORDER_PAID'
  shipmentControlAmount: number
  shipmentRequiredAmount: number
  shipmentFundedAmount: number
  shipmentPaymentShortfall: number
  customerAdvanceAvailable: number
  memo: string
  cancelReason: string
  cancelledAt: string | null
  confirmedAt: string | null
  settledAt: string | null
  reversalNo: string
  reversalReason: string
  reversedAt: string | null
  items: OutboundItemDetail[]
}

export interface OutboundItemDetail {
  id: number
  salesOrderItemId: number
  productId: number
  sku: string
  productName: string
  grade: string
  temper: string
  standard: string
  spec: string
  unit: string
  batchId: number
  batchNo: string
  quantity: number
  requestedQuantity?: number
  requestedUnit?: string
  baseUnit?: string
  pricingQuantity?: number
  packageUnit?: string
  packagingPlanJson?: string
  packagingRevisionId?: number
  packagingSnapshotHash?: string
  selectedPackageInstanceIdsJson?: string
  packageQuantity: number
  priceUnit: string
  unitPrice: number
  amount: number
  returnedQuantity: number
}

export interface SalesReturnRow {
  id: number
  returnNo: string
  outboundId: number
  outboundNo: string
  salesOrderId: number
  salesOrderNo: string | null
  customerId: number
  customerName: string
  businessDate: string
  status: 'CONFIRMED' | 'REVERSED'
  totalQuantity: number
  totalAmount: number
  priceIncludesTax: boolean
  taxRateSummary: string
  taxRuleSnapshot: string
  amountExcludingTax: number
  taxAmount: number
  taxRoundingDifference: number
  receivableReduction: number
  advanceCreditAmount: number
  reason: string
  memo: string
  confirmedAt: string | null
  reversalNo: string
  reversalReason: string
  reversedAt: string | null
}

export interface PaymentRow {
  id: number
  paymentNo: string
  customerId: number
  customerName: string
  salesOrderId: number
  salesOrderNo: string | null
  outboundId: number
  outboundNo: string | null
  amount: number
  allocatedAmount: number
  unallocatedAmount: number
  allocationCount: number
  fundNature: 'SETTLEMENT' | 'ADVANCE'
  allocationMode: 'ADVANCE' | 'LEGACY_SINGLE' | 'MULTI'
  paymentMethod: string
  accountType: string
  businessBankAccountId: number | null
  businessBankAccountName: string | null
  receivedDate: string
  verificationStatus: string
  reversalNo: string
  reversalReason: string
  reversedAt: string | null
}

export interface BusinessPostingRule {
  id: number
  ruleCode: string
  ruleName: string
  sourceType: string
  postingAction: string
  versionNo: number
  effectiveFrom: string
  effectiveTo: string | null
  ruleJson: string
  status: string
  publishedAt: string | null
}

export interface BusinessPostingBatch {
  id: number
  postingNo: string
  sourceType: string
  sourceId: number
  sourceNo: string
  postingAction: string
  sourceRevisionNo: number
  ruleCode: string
  ruleVersionNo: number
  businessDate: string
  partnerId: number
  partnerName: string
  originalBatchId: number
  reversalBatchId: number
  status: string
  entryHash: string
  reason: string
  operatorName: string
  legacyImported: boolean
  postedAt: string | null
  reversedAt: string | null
  createdAt: string
}

export interface BusinessPostingEntry {
  id: number
  postingBatchId: number
  lineNo: number
  factType: string
  direction: string
  partnerId: number
  productId: number
  productName: string
  warehouseId: number
  warehouseName: string
  inventoryBatchId: number
  inventoryBatchNo: string
  sourceLineType: string
  sourceLineId: number
  quantity: number
  quantityUnit: string
  amount: number
  currency: string
  memo: string
}

export interface BusinessPostingEvent {
  id: number
  postingBatchId: number
  action: string
  fromStatus: string
  toStatus: string
  reason: string
  operatorId: number
  operatorName: string
  createdAt: string
}

export interface BusinessPostingDetail {
  batch: BusinessPostingBatch
  entries: BusinessPostingEntry[]
  events: BusinessPostingEvent[]
}

export interface BusinessPostingReconciliation {
  sourceType: string
  sourceId: number
  sourceNo: string
  documentStatus: string
  expectedAction: string
  issueCode: string
  issueMessage: string
}

export interface AccountingEntity {
  id: number
  entityCode: string
  entityName: string
  baseCurrency: string
  fiscalYearStartMonth: number
  status: string
}

export interface AccountingAccount {
  id: number
  entityId: number
  accountCode: string
  accountName: string
  category: string
  normalDirection: 'DEBIT' | 'CREDIT'
  parentId: number
  accountLevel: number
  leaf: boolean
  allowManual: boolean
  partnerAuxiliary: string
  productAuxiliary: string
  warehouseAuxiliary: string
  effectiveFrom: string
  effectiveTo: string | null
  status: string
  used: boolean
}

export type AccountingAccountSave = Omit<AccountingAccount, 'id' | 'entityId' | 'accountLevel' | 'leaf' | 'used'>

export interface AccountingPeriod {
  id: number
  entityId: number
  fiscalYear: number
  periodNo: number
  periodName: string
  startDate: string
  endDate: string
  status: 'OPEN' | 'CLOSED'
  closeReason: string
  closedByName: string
  closedAt: string | null
  reopenedAt: string | null
}

export interface AccountingRule {
  id: number
  entityId: number
  ruleCode: string
  ruleName: string
  sourceType: string
  postingAction: string
  factType: string
  factDirection: 'INCREASE' | 'DECREASE'
  debitAccountId: number
  debitAccountCode: string
  debitAccountName: string
  creditAccountId: number
  creditAccountCode: string
  creditAccountName: string
  versionNo: number
  effectiveFrom: string
  effectiveTo: string | null
  status: string
  changeReason: string
  publishedByName: string
  publishedAt: string | null
}

export type AccountingRuleSave = Pick<AccountingRule,
  'ruleCode' | 'ruleName' | 'sourceType' | 'postingAction' | 'factType' | 'factDirection' |
  'debitAccountId' | 'creditAccountId' | 'effectiveFrom' | 'changeReason'>

export interface AccountingRuleCoverageSummary {
  asOf: string
  totalCount: number
  coveredCount: number
  warningCount: number
  blockingCount: number
  accountingRuleCount: number
  noVoucherCount: number
}

export interface AccountingRuleCoverageRow {
  scenarioCode: string
  category: string
  scenarioName: string
  sourceType: string
  postingAction: string
  factType: string
  factDirection: string
  settlementMethod: string
  generationMode: 'ACCOUNTING_RULE' | 'SOURCE_REVERSAL' | 'DIRECT_VOUCHER' | 'NO_VOUCHER' | 'UNKNOWN'
  voucherRequired: boolean
  baselineDebitCode: string
  baselineCreditCode: string
  actualRuleCode: string
  actualDebitCode: string
  actualCreditCode: string
  activeRuleCount: number
  coverageStatus: 'COVERED' | 'NOT_REQUIRED' | 'CUSTOMIZED' | 'MISSING_RULE' |
    'AMBIGUOUS_RULE' | 'UNREGISTERED_RULE' | 'UNREGISTERED_FACT'
  resultDescription: string
  evidence: string
}

export interface AccountingRuleCoverageMatrix {
  summary: AccountingRuleCoverageSummary
  rows: AccountingRuleCoverageRow[]
}

export interface AccountingVoucher {
  id: number
  entityId: number
  periodId: number
  voucherNo: string
  voucherType: 'AUTO' | 'MANUAL' | 'REVERSAL'
  voucherDate: string
  summary: string
  sourceType: string
  sourceId: number
  sourceNo: string
  businessPostingBatchId: number | null
  originalVoucherId: number
  reversalVoucherId: number
  status: 'DRAFT' | 'REVIEWED' | 'POSTED' | 'REVERSED' | 'CANCELLED'
  debitTotal: number
  creditTotal: number
  makerName: string
  reviewerName: string
  posterName: string
  reviewedAt: string | null
  postedAt: string | null
  reversedAt: string | null
  createdAt: string
}

export interface AccountingVoucherLine {
  id: number
  voucherId: number
  lineNo: number
  accountId: number
  accountCode: string
  accountName: string
  summary: string
  debitAmount: number
  creditAmount: number
  partnerId: number
  partnerName: string
  productId: number
  productName: string
  warehouseId: number
  warehouseName: string
  sourcePostingEntryId: number
  sourceLineType: string
  sourceLineId: number
}

export interface AccountingVoucherEvent {
  id: number
  voucherId: number
  action: string
  fromStatus: string
  toStatus: string
  reason: string
  operatorId: number
  operatorName: string
  createdAt: string
}

export interface AccountingLinkedTaxInvoice {
  id: number
  internalNo: string
  direction: 'INPUT' | 'OUTPUT'
  invoiceColor: 'BLUE' | 'RED'
  invoiceNumber: string
  taxAmount: number
  status: string
  matchingBasis: string
}

export interface AccountingVoucherDetail {
  voucher: AccountingVoucher
  lines: AccountingVoucherLine[]
  events: AccountingVoucherEvent[]
  linkedTaxInvoices: AccountingLinkedTaxInvoice[]
}

export interface AccountingArchive {
  id: number
  entityId: number
  periodId: number
  periodName: string
  archiveNo: string
  archiveMode: 'PREVIEW' | 'FINAL'
  status: 'GENERATING' | 'COMPLETED' | 'FAILED' | 'SUPERSEDED'
  formatVersion: string
  fileName: string
  archiveBytes: number
  archiveSha256: string
  voucherCount: number
  sourceAttachmentCount: number
  generatedByName: string
  generatedAt: string
  completedAt: string | null
  errorMessage: string
  downloadable: boolean
}

export interface AccountingVoucherLineSave {
  accountId: number
  summary: string
  debitAmount: number
  creditAmount: number
  partnerId?: number
  productId?: number
  warehouseId?: number
}

export interface AccountingVoucherSave {
  voucherDate: string
  summary: string
  lines: AccountingVoucherLineSave[]
}

export interface AccountingGenerationIssue {
  id: number
  businessPostingBatchId: number
  postingNo: string
  sourceType: string
  sourceId: number
  sourceNo: string
  issueCode: string
  issueMessage: string
  status: string
  retryCount: number
  resolvedVoucherId: number
  lastAttemptAt: string
  resolvedAt: string | null
  createdAt: string
}

export interface AccountingDashboard {
  entity: AccountingEntity
  currentPeriod: AccountingPeriod | null
  draftVoucherCount: number
  reviewedVoucherCount: number
  postedVoucherCount: number
  openIssueCount: number
  ungeneratedPostingCount: number
  trialDifference: number
}

export interface AccountingLedgerRow {
  accountId: number
  accountCode: string
  accountName: string
  category: string
  normalDirection: string
  openingDebit: number
  openingCredit: number
  periodDebit: number
  periodCredit: number
  closingDebit: number
  closingCredit: number
}

export interface AccountingTrialBalance {
  rows: AccountingLedgerRow[]
  openingDebitTotal: number
  openingCreditTotal: number
  periodDebitTotal: number
  periodCreditTotal: number
  closingDebitTotal: number
  closingCreditTotal: number
  difference: number
}

export interface AccountingLedgerEntry {
  id: number
  voucherId: number
  voucherNo: string
  voucherDate: string
  accountId: number
  accountCode: string
  accountName: string
  summary: string
  debitAmount: number
  creditAmount: number
  runningBalance: number
  partnerId: number
  partnerName: string
  productId: number
  productName: string
  warehouseId: number
  warehouseName: string
}

export interface FinancialReportLine {
  lineCode: string
  lineName: string
  lineOrder: number
  currentAmount: number
  comparativeAmount: number
  calculationType: 'ACCOUNT_SUM' | 'FORMULA'
  accountCodes: string[]
}

export interface FinancialReport {
  reportType: 'BALANCE_SHEET' | 'INCOME_STATEMENT'
  reportTitle: string
  fromDate: string | null
  toDate: string
  baseCurrency: string
  rows: FinancialReportLine[]
  primaryTotal: number
  secondaryTotal: number
  difference: number
  unmappedAccounts: string[]
  generatedAt: string
}

export interface CashFlowLine {
  category: string
  categoryName: string
  activityType: 'OPERATING' | 'INVESTING' | 'FINANCING'
  direction: 'DEBIT' | 'CREDIT'
  amount: number
}

export interface CashFlowReport {
  fromDate: string
  toDate: string
  baseCurrency: string
  rows: CashFlowLine[]
  openingCash: number
  operatingNet: number
  investingNet: number
  financingNet: number
  netIncrease: number
  closingCash: number
  ledgerClosingCash: number
  closingDifference: number
  confirmedSessionCount: number
  unclassifiedMatchCount: number
  generatedAt: string
}

export interface BusinessBankAccount {
  id: number
  accountCode: string
  accountName: string
  bankName: string
  bankAccountNo: string
  maskedAccountNo: string
  currency: string
  ledgerAccountId: number
  ledgerAccountCode: string
  ledgerAccountName: string
  openingDate: string
  openingBalance: number
  status: 'ENABLED' | 'DISABLED'
  disabledReason: string
}

export type BusinessBankAccountSave = Omit<BusinessBankAccount,
  'id' | 'maskedAccountNo' | 'ledgerAccountCode' | 'ledgerAccountName'>

export interface BankReconciliationSession {
  id: number
  bankAccountId: number
  bankAccountName: string
  maskedAccountNo: string
  periodId: number
  periodName: string
  reconciliationNo: string
  statementFrom: string
  statementTo: string
  openingBalance: number
  statementClosingBalance: number
  ledgerClosingBalance: number
  adjustedStatementBalance: number
  adjustedLedgerBalance: number
  difference: number
  status: 'DRAFT' | 'CONFIRMED' | 'REOPENED'
  confirmedByName: string
  confirmedAt: string | null
}

export interface BankStatementLine {
  id: number
  lineNo: number
  transactionDate: string
  valueDate: string
  bankSerialNo: string
  direction: 'DEBIT' | 'CREDIT'
  amount: number
  balance: number
  counterpartyName: string
  counterpartyAccount: string
  purpose: string
  matchedAmount: number
  explainedAmount: number
  remainingAmount: number
  status: 'UNMATCHED' | 'PARTIAL' | 'MATCHED' | 'EXPLAINED' | 'VOID'
  voidReason: string
  voidedByName: string
  voidedAt: string | null
  importBatchId: number
  importBatchNo: string
  unmatchedReasonCode: string
  unmatchedReason: string
  suggestedSourceType: string
  suggestedSourceId: number
  suggestedSourceNo: string
  suggestedScore: number
  suggestedReason: string
}

export interface BankFundCandidate {
  sourceType: string
  sourceId: number
  sourceNo: string
  sourceDate: string
  direction: 'DEBIT' | 'CREDIT'
  amount: number
  matchedAmount: number
  remainingAmount: number
  partnerId: number
  partnerName: string
  paymentMethod: string
  memo: string
}

export interface BankReconciliationMatch {
  id: number
  statementLineId: number
  sourceType: string
  sourceId: number
  sourceNo: string
  sourceDate: string
  sourceDirection: 'DEBIT' | 'CREDIT'
  matchedAmount: number
  cashFlowCategory: string
  matchingMethod: string
  matchScore: number
  matchRuleVersion: string
  importBatchId: number
  status: 'ACTIVE' | 'REVERSED'
  reason: string
  matchedByName: string
  matchedAt: string
}

export interface BankStatementImportBatch {
  id: number
  sessionId: number
  batchNo: string
  templateVersion: string
  sourceFileName: string
  sourceFileFormat: 'CSV' | 'XLSX'
  sourceFileSha256: string
  sourceFileSize: number
  status: 'PREVIEWED' | 'COMPLETED' | 'CANCELLED'
  totalRows: number
  validRows: number
  duplicateRows: number
  errorRows: number
  autoMatchRows: number
  reviewRows: number
  unmatchedRows: number
  importedRows: number
  appliedAutoMatchRows: number
  importedByName: string
  createdAt: string
  completedAt: string | null
}

export interface BankStatementImportRow {
  id: number
  batchId: number
  sourceRowNo: number
  transactionDate: string | null
  valueDate: string | null
  bankSerialNo: string
  direction: 'DEBIT' | 'CREDIT' | ''
  amount: number | null
  balance: number | null
  counterpartyName: string
  counterpartyAccount: string
  purpose: string
  validationStatus: 'VALID' | 'DUPLICATE' | 'ERROR'
  validationMessage: string
  duplicateType: 'NONE' | 'FILE_EXACT' | 'FILE_SERIAL' | 'EXISTING_EXACT' | 'EXISTING_SERIAL'
  duplicateStatementLineId: number
  matchAction: 'AUTO_MATCH' | 'REVIEW' | 'UNMATCHED' | 'DUPLICATE' | 'INVALID'
  suggestedSourceType: string
  suggestedSourceId: number
  suggestedSourceNo: string
  suggestedScore: number
  suggestedReason: string
  unmatchedReasonCode: string
  unmatchedReason: string
  importedStatementLineId: number
}

export interface BankStatementImportPreview {
  batch: BankStatementImportBatch
  rows: BankStatementImportRow[]
}

export interface BankStatementImportResult {
  batchId: number
  batchNo: string
  status: string
  importedRows: number
  duplicateRows: number
  errorRows: number
  appliedAutoMatchRows: number
  reviewRows: number
  unmatchedRows: number
}

export interface BankReconciliationDifference {
  id: number
  statementLineId: number
  differenceType: string
  direction: 'DEBIT' | 'CREDIT'
  amount: number
  cashFlowCategory: string
  reason: string
  accountingVoucherId: number
  accountingVoucherNo: string
  accountingVoucherStatus: string
  status: 'OPEN' | 'RESOLVED' | 'REVERSED'
  createdByName: string
  createdAt: string
}

export interface BankPendingSystemItem {
  id: number
  sourceType: string
  sourceId: number
  sourceNo: string
  sourceDate: string
  direction: 'DEBIT' | 'CREDIT'
  amount: number
  cashFlowCategory: string
  reason: string
  status: 'OPEN' | 'MATCHED' | 'REVERSED'
  createdByName: string
  createdAt: string
}

export interface BankReconciliationEvent {
  id: number
  action: string
  fromStatus: string
  toStatus: string
  reason: string
  operatorName: string
  createdAt: string
}

export interface BankReconciliationDetail {
  session: BankReconciliationSession
  statementLines: BankStatementLine[]
  fundCandidates: BankFundCandidate[]
  matches: BankReconciliationMatch[]
  differences: BankReconciliationDifference[]
  pendingSystemItems: BankPendingSystemItem[]
  events: BankReconciliationEvent[]
  unmatchedStatementCredit: number
  unmatchedStatementDebit: number
  unmatchedSystemCredit: number
  unmatchedSystemDebit: number
}

export interface PeriodCloseCheck {
  checkCode: string
  checkName: string
  status: 'PASS' | 'BLOCKED' | 'WARNING'
  issueCount: number
  amountDifference: number
  detail: string
}

export interface PeriodClosePreview {
  periodId: number
  periodName: string
  startDate: string
  endDate: string
  periodStatus: 'OPEN' | 'CLOSED'
  checks: PeriodCloseCheck[]
  closable: boolean
  profitVoucherId: number
  profitVoucherNo: string
  profitVoucherStatus: string
  balanceSheetAssets: number
  balanceSheetLiabilitiesEquity: number
  incomeNetProfit: number
  cashNetIncrease: number
  trialDifference: number
  lastCloseRunId: number
  lastCloseRunStatus: string
  lastSnapshotHash: string
  events: Array<{
    id: number
    action: string
    fromStatus: string
    toStatus: string
    reason: string
    operatorName: string
    createdAt: string
  }>
}

export interface PeriodCloseResult {
  periodId: number
  closeRunId: number
  status: 'OPEN' | 'CLOSED' | 'REOPENED'
  snapshotHash: string
}

export type TaxInvoiceDirection = 'INPUT' | 'OUTPUT'
export type TaxInvoiceColor = 'BLUE' | 'RED'

export interface TaxRateRule {
  id: number
  ruleCode: string
  ruleName: string
  taxRate: number
  calculationMethod: string
  invoiceScope: string
  versionNo: number
  effectiveFrom: string
  effectiveTo: string | null
  legalBasis: string
  status: string
}

export interface TaxInvoiceDashboard {
  inputConfirmedCount: number
  inputTotalAmount: number
  inputTaxAmount: number
  outputConfirmedCount: number
  outputTotalAmount: number
  outputTaxAmount: number
  reconciliationExceptionCount: number
  verificationPendingCount: number
  deductionPendingCount: number
  redConfirmationPendingCount: number
}

export interface TaxInvoiceRow {
  id: number
  internalNo: string
  direction: TaxInvoiceDirection
  invoiceColor: TaxInvoiceColor
  invoiceType: string
  invoiceCode: string
  invoiceNumber: string
  blueInvoiceId: number
  partnerId: number
  partnerName: string
  invoiceDate: string
  receivedOrIssuedDate: string
  amountExcludingTax: number
  taxAmount: number
  totalAmount: number
  status: 'DRAFT' | 'CONFIRMED' | 'VOIDED'
  verificationStatus: string
  bookingStatus: string
  usageStatus: string
  usagePeriod: string
  redConfirmationStatus: string
  accountingVoucherId: number
  usageVoucherId: number
  transferOutVoucherId: number
  redTotalAmount: number
  remainingRedAmount: number
  goodsMatchedAmount: number
  settlementMatchedAmount: number
  fundsMatchedAmount: number
  accountingMatchedTax: number
  reconciliationStatus: 'COMPLETE' | 'PARTIAL' | 'UNMATCHED'
  versionNo: number
  confirmedAt: string | null
  createdAt: string
}

export interface TaxInvoiceLine {
  id: number
  invoiceId: number
  lineNo: number
  productId: number
  productSku: string
  productName: string
  productSpec: string
  unit: string
  quantity: number
  unitPriceExcludingTax: number
  amountExcludingTax: number
  taxRateRuleId: number
  taxRuleCode: string
  taxRuleName: string
  taxRate: number
  calculationMethod: string
  taxClassificationCode: string
  taxAmount: number
  totalAmount: number
  legalBasis: string
}

export interface TaxInvoiceRelation {
  id: number
  invoiceId: number
  relationLayer: string
  sourceType: string
  sourceId: number
  sourceNo: string
  sourceBusinessDate: string | null
  partnerId: number
  amountExcludingTax: number
  taxAmount: number
  totalAmount: number
  status: string
  matchingBasis: string
  reversedReason: string
  createdByName: string
  reversedByName: string
  reversedAt: string | null
  createdAt: string
}

export interface TaxInvoiceEvent {
  id: number
  invoiceId: number
  action: string
  fromStatus: string
  toStatus: string
  reason: string
  operatorId: number
  operatorName: string
  createdAt: string
}

export interface TaxInvoiceUseEvent {
  id: number
  invoiceId: number
  action: string
  fromUsageStatus: string
  toUsageStatus: string
  taxPeriod: string
  taxAmount: number
  accountingVoucherId: number
  evidenceReference: string
  reason: string
  operatorName: string
  createdAt: string
}

export interface TaxInvoiceVerificationRecord {
  id: number
  invoiceId: number
  verificationSource: 'TAX_DIGITAL_ACCOUNT' | 'NATIONAL_PLATFORM' | 'LOCAL_TAX_AUTHORITY' | 'MANUAL_REVIEW'
  verificationResult: 'VERIFIED' | 'FAILED'
  verificationReference: string
  proofAttachmentId: number
  proofSha256: string
  checkedAt: string
  querySnapshot: string
  note: string
  operatorName: string
  createdAt: string
}

export interface TaxInvoiceDetail {
  invoice: TaxInvoiceRow
  partnerBusinessNo: string
  partnerTaxNo: string
  issuerName: string
  issuerTaxNo: string
  recipientName: string
  recipientTaxNo: string
  sourceChannel: string
  currency: string
  roundingDifference: number
  redConfirmationNo: string
  redConfirmationInitiator: string
  redConfirmationInitiatedAt: string | null
  redConfirmationExpiresAt: string | null
  redConfirmationConfirmedAt: string | null
  sourceFileName: string
  sourceFileSha256: string
  memo: string
  voidReason: string
  voidVoucherId: number
  lines: TaxInvoiceLine[]
  relations: TaxInvoiceRelation[]
  events: TaxInvoiceEvent[]
  useEvents: TaxInvoiceUseEvent[]
  verificationRecords: TaxInvoiceVerificationRecord[]
}

export interface TaxInvoiceImportResult {
  batchId: number
  batchNo: string
  importedInvoiceCount: number
  importedLineCount: number
  invoiceIds: number[]
}

export interface TaxInvoiceLineSave {
  productId?: number
  productName: string
  productSku?: string
  productSpec?: string
  unit?: string
  quantity: number
  unitPriceExcludingTax: number
  amountExcludingTax: number
  taxRateRuleId: number
  taxClassificationCode?: string
  taxAmount: number
  totalAmount: number
}

export interface TaxInvoiceSave {
  direction: TaxInvoiceDirection
  invoiceColor: TaxInvoiceColor
  invoiceType: string
  invoiceCode?: string
  invoiceNumber: string
  blueInvoiceId?: number
  partnerId: number
  issuerName: string
  issuerTaxNo?: string
  recipientName: string
  recipientTaxNo?: string
  invoiceDate: string
  receivedOrIssuedDate: string
  sourceChannel?: string
  currency?: string
  sourceFileName?: string
  sourceFileSha256?: string
  memo?: string
  expectedVersionNo?: number
  lines: TaxInvoiceLineSave[]
}

export interface TaxInvoiceSourceCandidate {
  relationLayer: string
  sourceType: string
  sourceId: number
  sourceNo: string
  businessDate: string | null
  partnerId: number
  partnerName: string
  sourceAmount: number
  alreadyMatchedAmount: number
  availableAmount: number
  sourceAmountExcludingTax: number
  sourceTaxAmount: number
  availableAmountExcludingTax: number
  availableTaxAmount: number
  taxRoundingDifference: number
  taxSnapshotAvailable: boolean
  status: string
}

export type ElectronicCredentialType = 'DIGITAL_INVOICE' | 'BANK_RECEIPT' | 'BANK_STATEMENT'

export interface TaxComplianceDashboard {
  credentialCount: number
  pendingVerificationCount: number
  parseExceptionCount: number
  unlinkedCount: number
  failedVerificationCount: number
  latestWorkpaper: TaxWorkpaperRow | null
}

export interface ElectronicCredentialRow {
  id: number
  credentialNo: string
  credentialType: ElectronicCredentialType
  fileFormat: 'XML' | 'PDF' | 'OFD'
  standardCode: string
  standardVersion: string
  originalFileName: string
  fileSize: number
  sourceFileSha256: string
  parseStatus: 'PARSED' | 'PENDING_EXTERNAL' | 'FAILED'
  parseMessage: string
  verificationStatus: 'UNVERIFIED' | 'VERIFIED' | 'FAILED'
  signatureStatus: 'UNVERIFIED' | 'VERIFIED' | 'FAILED' | 'NOT_APPLICABLE'
  externalNo: string
  directionHint: '' | TaxInvoiceDirection
  issueDate: string | null
  periodFrom: string | null
  periodTo: string | null
  accountingEntityCode: string
  accountingEntityName: string
  counterpartyCode: string
  counterpartyName: string
  bankAccountNo: string
  currency: string
  amountExcludingTax: number
  taxAmount: number
  totalAmount: number
  detailCount: number
  status: 'ACTIVE' | 'VOIDED'
  activeRelationCount: number
  createdByName: string
  createdAt: string
}

export interface ElectronicCredentialVerification {
  id: number
  verificationKind: 'AUTHENTICITY' | 'SIGNATURE'
  verificationSource: 'MOF_TOOLKIT' | 'TAX_DIGITAL_ACCOUNT' | 'NATIONAL_PLATFORM' | 'BANK_VALIDATION_TOOL' | 'MANUAL_REVIEW'
  verificationResult: 'VERIFIED' | 'FAILED'
  verificationReference: string
  checkedAt: string
  resultSnapshot: string
  proofFileName: string
  proofContentType: string
  proofFileSize: number
  proofSha256: string
  operatorName: string
  createdAt: string
}

export interface ElectronicCredentialRelation {
  id: number
  relationType: 'SOURCE' | 'BOOKING' | 'RECONCILIATION'
  documentType: string
  documentId: number
  documentNo: string
  evidenceAttachmentId: number
  status: 'ACTIVE' | 'REVERSED'
  note: string
  createdByName: string
  createdAt: string
  reversedByName: string
  reversedReason: string
  reversedAt: string | null
}

export interface ElectronicCredentialEvent {
  id: number
  action: string
  fromStatus: string
  toStatus: string
  summary: string
  operatorName: string
  createdAt: string
}

export interface ElectronicCredentialDetail {
  credential: ElectronicCredentialRow
  verifications: ElectronicCredentialVerification[]
  relations: ElectronicCredentialRelation[]
  events: ElectronicCredentialEvent[]
}

export interface ElectronicCredentialLinkCandidate {
  relationType: 'SOURCE' | 'BOOKING' | 'RECONCILIATION'
  documentType: string
  documentId: number
  documentNo: string
  businessDate: string
  amount: number
  status: string
  matchLevel: 'EXACT' | 'POSSIBLE' | 'REVIEW'
  matchReason: string
}

export interface TaxWorkpaperRow {
  id: number
  periodId: number
  periodName: string
  workpaperNo: string
  taxType: string
  ruleCode: string
  ruleVersion: number
  versionNo: number
  dataCutoffAt: string
  status: 'DRAFT' | 'REVIEWED' | 'SUPERSEDED'
  outputNetAmount: number
  outputTaxAmount: number
  inputNetAmount: number
  inputTaxAmount: number
  deductibleInputTax: number
  transferredOutInputTax: number
  estimatedVatPayable: number
  ledgerOutputTax: number
  ledgerInputTax: number
  ledgerTransferredOutTax: number
  outputLedgerDifference: number
  inputLedgerDifference: number
  blockerCount: number
  warningCount: number
  contentSha256: string
  reviewNote: string
  generatedByName: string
  generatedAt: string
  reviewedByName: string
  reviewedAt: string | null
}

export interface TaxWorkpaperLine {
  id: number
  lineNo: number
  sectionCode: string
  itemCode: string
  itemName: string
  taxRate: number | null
  amountExcludingTax: number
  taxAmount: number
  totalAmount: number
  sourceCount: number
  severity: 'INFO' | 'WARNING' | 'BLOCKER'
  formula: string
  sourceQuerySnapshot: string
}

export interface TaxWorkpaperEvent {
  id: number
  action: string
  fromStatus: string
  toStatus: string
  reason: string
  operatorName: string
  createdAt: string
}

export interface TaxWorkpaperDetail {
  workpaper: TaxWorkpaperRow
  lines: TaxWorkpaperLine[]
  events: TaxWorkpaperEvent[]
}

export interface BusinessOrderRevision {
  id: number
  orderType: 'PURCHASE_ORDER' | 'SALES_ORDER'
  orderId: number
  orderNo: string
  revisionNo: number
  changeNo: string
  changeType: string
  changeReason: string
  snapshotBeforeJson: string
  snapshotAfterJson: string
  reversedPostingBatchId: number
  postedBatchId: number
  operatorName: string
  createdAt: string
}

export interface PurchaseOrderRow {
  id: number
  orderNo: string
  supplierName: string
  businessDate: string
  expectedDate: string | null
  expectedWarehouseId: number
  expectedWarehouseName: string | null
  sourceType: string
  totalQuantity: number
  receivedQuantity: number
  originalAmount: number
  discountAmount: number
  adjustmentAmount: number
  totalAmount: number
  paidAmount: number
  paymentStatus: string
  status: string
}

export interface PurchaseOrderDetail extends PurchaseOrderRow {
  supplierId: number
  sourceNote: string
  discountRate: number
  priceIncludesTax: boolean
  taxRateSummary: string
  taxRuleSnapshot: string
  amountExcludingTax: number
  taxAmount: number
  taxRoundingDifference: number
  memo: string
  cancelReason?: string
  cancelledAt?: string | null
  items: PurchaseOrderItemDetail[]
}

export interface PurchaseOrderItemDetail {
  id: number
  productId: number
  sku: string
  productName: string
  grade: string
  temper: string
  standard: string
  spec: string
  unit: string
  quantity: number
  requestedQuantity?: number
  requestedUnit?: string
  baseUnit?: string
  pricingQuantity?: number
  packagingPlanJson?: string
  packagingRevisionId?: number
  packagingSnapshotHash?: string
  receivedQuantity: number
  priceUnit: string
  packageQuantity: number
  packageUnit: string
  priceSourceCode: string
  priceSourceName: string
  priceRuleVersionId: number
  priceSnapshotJson: string
  noCharge: boolean
  unitPrice: number
  amount: number
  taxRate: number
  unitPriceExcludingTax: number
  amountExcludingTax: number
  taxAmount: number
  settlementTotalAmount: number
  taxRoundingDifference: number
  status: string
}

export interface SupplierPaymentRow {
  id: number
  paymentNo: string
  supplierId: number
  supplierName: string
  purchaseOrderId: number
  purchaseOrderNo: string | null
  inboundId: number
  inboundNo: string | null
  amount: number
  allocatedAmount: number
  unallocatedAmount: number
  allocationCount: number
  fundNature: 'SETTLEMENT' | 'ADVANCE'
  allocationMode: 'ADVANCE' | 'LEGACY_SINGLE' | 'MULTI'
  paymentMethod: string
  accountType: string
  businessBankAccountId: number | null
  businessBankAccountName: string | null
  paidDate: string
  verificationStatus: string
  reversalNo: string
  reversalReason: string
  reversedAt: string | null
}

export interface PaymentAllocationRow {
  id: number
  allocationNo: string
  targetType: 'SALES_ORDER' | 'OUTBOUND' | 'PURCHASE_ORDER' | 'INBOUND'
  targetId: number
  targetNo: string
  allocatedAmount: number
  status: 'ACTIVE' | 'RELEASED' | 'REVERSED'
  captureSource: 'CREATION' | 'LEGACY_BACKFILL' | 'REALLOCATION'
  releaseReason: string
  releasedAt: string | null
  reversalReason: string
  reversedAt: string | null
  createdAt: string
}

export interface PaymentAllocationEvent {
  id: number
  allocationId: number
  action: 'APPLIED' | 'RELEASED' | 'HEAD_REVERSED'
  fromStatus: string
  toStatus: string
  amount: number
  businessDate: string
  reason: string
  operatorId: number
  operatorName: string
  createdAt: string
}

export interface PaymentAllocationDetail {
  paymentId: number
  paymentNo: string
  partnerType: 'CUSTOMER' | 'SUPPLIER'
  partnerId: number
  partnerName: string
  amount: number
  allocatedAmount: number
  unallocatedAmount: number
  paymentMethod: string
  businessBankAccountId: number | null
  businessBankAccountName: string | null
  businessDate: string
  verificationStatus: string
  fundNature: 'SETTLEMENT' | 'ADVANCE'
  allocationMode: 'ADVANCE' | 'LEGACY_SINGLE' | 'MULTI'
  reversalNo: string
  reversalReason: string
  reversedAt: string | null
  allocations: PaymentAllocationRow[]
  events: PaymentAllocationEvent[]
}

export interface PaymentAllocationChange {
  reason: string
  businessDate: string
  releaseAllocationIds: number[]
  allocations: Array<{ targetType: PaymentAllocationRow['targetType']; targetId: number; amount: number }>
}

export interface PayableRow {
  sourceType: 'PURCHASE_ORDER' | 'INBOUND'
  sourceId: number
  sourceNo: string
  supplierId: number
  supplierName: string
  businessDate: string
  totalAmount: number
  paidAmount: number
  unpaidAmount: number
  settlementDays: number
  dueDate: string
  overdueDays: number
  paymentStatus: string
  status: string
}

export interface ReceivableRow {
  sourceType: 'SALES_ORDER' | 'OUTBOUND'
  sourceId: number
  sourceNo: string
  customerId: number
  customerName: string
  businessDate: string
  totalAmount: number
  paidAmount: number
  unpaidAmount: number
  settlementDays: number
  dueDate: string
  overdueDays: number
  paymentStatus: string
  status: string
}

export type SettlementDirection = 'RECEIVABLE' | 'PAYABLE'
export type SettlementScope = 'all' | 'open' | 'due-today' | 'overdue' | 'settled'
export type SettlementAgingCode =
  | 'NOT_DUE'
  | 'DUE_TODAY'
  | 'OVERDUE_1_30'
  | 'OVERDUE_31_60'
  | 'OVERDUE_61_90'
  | 'OVERDUE_91_PLUS'

export interface SettlementReportRow {
  sourceType: 'SALES_ORDER' | 'OUTBOUND' | 'PURCHASE_ORDER' | 'INBOUND' | 'OPENING_RECEIVABLE' | 'OPENING_PAYABLE'
  sourceId: number
  sourceNo: string
  partnerId: number
  partnerName: string
  businessDate: string
  grossAmount: number
  reductionAmount: number
  totalAmount: number
  paidAmount: number
  unpaidAmount: number
  settlementDays: number
  dueDate: string
  overdueDays: number
  agingBucket: SettlementAgingCode | 'SETTLED'
  paymentStatus: string
  status: string
}

export interface SettlementReportSummary {
  documentCount: number
  partnerCount: number
  grossAmount: number
  reductionAmount: number
  totalAmount: number
  paidAmount: number
  unpaidAmount: number
  dueTodayCount: number
  dueTodayAmount: number
  overdueCount: number
  overdueAmount: number
}

export interface SettlementAgingBucket {
  code: SettlementAgingCode
  label: string
  documentCount: number
  outstandingAmount: number
}

export interface SettlementReportPage {
  direction: SettlementDirection
  asOfDate: string
  snapshotAt: string
  completeScope: boolean
  pageNo: number
  pageSize: number
  totalRows: number
  totalPages: number
  summary: SettlementReportSummary
  aging: SettlementAgingBucket[]
  items: SettlementReportRow[]
}

export interface SettlementReportParams {
  partnerId?: number
  keyword?: string
  sourceType?: string
  scope?: SettlementScope
  dueDateFrom?: string
  dueDateTo?: string
  pageNo?: number
  pageSize?: number
}

export interface PartnerAccountRow {
  id: number
  partnerId: number
  partnerName: string
  partnerType: string
  accountType: string
  balance: number
  frozenAmount: number
  creditLimit: number
}

export interface PartnerAccountLogRow {
  id: number
  partnerId: number
  partnerName: string
  accountType: string
  sourceType: string
  sourceId: number
  businessType: string
  businessId: number
  businessNo: string
  businessSummary: string
  amount: number
  amountBefore: number
  amountAfter: number
  createdAt: string
}

export type PartnerFundAccountType = 'CUSTOMER_ADVANCE' | 'CUSTOMER_REWARD' | 'SUPPLIER_PREPAY' | 'SUPPLIER_REWARD'
export type PartnerFundAdjustmentType = 'OPENING_BALANCE' | 'CUSTOMER_REWARD_GRANT' | 'SUPPLIER_REBATE_GRANT' | 'BALANCE_CORRECTION'
export type PartnerFundTaxTreatment = 'NOT_APPLICABLE' | 'PENDING_PROFESSIONAL_REVIEW' | 'SEPARATE_INVOICE_HANDLING'
export type PartnerFundAdjustmentStatus = 'DRAFT' | 'CONFIRMED' | 'CANCELLED' | 'REVERSED'

export interface PartnerFundAdjustmentRow {
  id: number
  adjustmentNo: string
  partnerId: number
  partnerName: string
  accountType: PartnerFundAccountType
  adjustmentType: PartnerFundAdjustmentType
  direction: 'INCREASE' | 'DECREASE'
  amount: number
  businessDate: string
  basisNo: string
  taxTreatment: PartnerFundTaxTreatment
  memo: string
  status: PartnerFundAdjustmentStatus
  createdByName: string
  confirmedByName: string
  confirmedAt: string | null
  cancelReason: string
  cancelledByName: string
  cancelledAt: string | null
  reversalNo: string
  reversalReason: string
  reversedByName: string
  reversedAt: string | null
  postingBatchId: number
  postingNo: string
  postingStatus: string
  accountingVoucherId: number
  accountingVoucherNo: string
  accountingVoucherStatus: string
  createdAt: string
}

export interface PartnerFundAdjustmentEvent {
  id: number
  action: 'CREATE' | 'CONFIRM' | 'CANCEL' | 'REVERSE'
  fromStatus: string
  toStatus: string
  reason: string
  operatorName: string
  createdAt: string
}

export interface PartnerFundAdjustmentDetail {
  header: PartnerFundAdjustmentRow
  events: PartnerFundAdjustmentEvent[]
}

export interface PartnerFundAdjustmentCreate {
  partnerId: number
  accountType: PartnerFundAccountType
  adjustmentType: PartnerFundAdjustmentType
  direction: 'INCREASE' | 'DECREASE'
  amount: number
  businessDate: string
  basisNo: string
  taxTreatment: PartnerFundTaxTreatment
  memo: string
}

export type TreasuryDocumentType = 'CORPORATE_EXPENSE' | 'OTHER_EXPENSE' | 'OTHER_INCOME' | 'OTHER_RECEIVABLE' | 'OTHER_PAYABLE' | 'PURCHASE_LANDED_COST' | 'OPENING_RECEIVABLE' | 'OPENING_PAYABLE'
export type TreasuryDocumentStatus = 'DRAFT' | 'CONFIRMED' | 'PARTIALLY_SETTLED' | 'SETTLED' | 'CANCELLED' | 'REVERSED'
export type TreasuryPaymentRequestStatus = 'DRAFT' | 'APPROVED' | 'PARTIALLY_PAID' | 'PAID' | 'REJECTED' | 'CANCELLED'

export interface TreasuryDocumentRow {
  id: number
  documentNo: string
  documentType: TreasuryDocumentType
  partnerId: number
  partnerName: string
  businessDate: string
  dueDate: string
  basisNo: string
  summary: string
  offsetAccountId: number
  offsetAccountCode: string
  offsetAccountName: string
  priceIncludesTax: boolean
  taxRate: number
  amountExcludingTax: number
  taxAmount: number
  totalAmount: number
  taxRoundingDifference: number
  taxRuleSnapshot: string
  inputTaxTreatment: 'DEDUCTIBLE' | 'INCLUDED_IN_COST'
  settledAmount: number
  outstandingAmount: number
  settlementDirection: 'CREDIT' | 'DEBIT'
  status: TreasuryDocumentStatus
  memo: string
  versionNo: number
  createdByName: string
  confirmedByName: string
  confirmedAt: string | null
  cancelReason: string
  cancelledByName: string
  cancelledAt: string | null
  reversalReason: string
  reversedByName: string
  reversedAt: string | null
  accountingVoucherId: number
  accountingVoucherNo: string
  accountingVoucherStatus: string
  createdAt: string
}

export interface TreasuryEvent {
  id: number
  action: string
  fromStatus: string
  toStatus: string
  reason: string
  operatorName: string
  createdAt: string
}

export interface TreasuryFundRow {
  id: number
  fundNo: string
  fundType: 'DOCUMENT_SETTLEMENT' | 'INTERNAL_TRANSFER'
  documentId: number
  paymentRequestId: number
  direction: 'CREDIT' | 'DEBIT' | 'TRANSFER'
  settlementMethod: 'CASH' | 'BANK'
  businessBankAccountId: number
  bankAccountName: string
  counterBankAccountId: number
  counterBankAccountName: string
  amount: number
  transactionDate: string
  cashFlowCategory: string
  counterpartyName: string
  purpose: string
  status: 'ACTIVE' | 'REVERSED'
  accountingVoucherId: number
  accountingVoucherNo: string
  accountingVoucherStatus: string
  reversalVoucherId: number
  reversalVoucherNo: string
  createdByName: string
  reversalReason: string
  reversedByName: string
  reversedAt: string | null
  createdAt: string
}

export interface TreasuryPaymentRequestRow {
  id: number
  requestNo: string
  documentId: number
  documentNo: string
  documentType: TreasuryDocumentType
  partnerId: number
  partnerName: string
  documentOutstandingAmount: number
  requestedAmount: number
  paidAmount: number
  unpaidAmount: number
  requestDate: string
  expectedPaymentDate: string
  purpose: string
  status: TreasuryPaymentRequestStatus
  versionNo: number
  createdByName: string
  approvedByName: string
  approvedAt: string | null
  rejectedByName: string
  rejectedReason: string
  rejectedAt: string | null
  cancelledByName: string
  cancelReason: string
  cancelledAt: string | null
  createdAt: string
}

export interface TreasuryDocumentDetail {
  header: TreasuryDocumentRow
  events: TreasuryEvent[]
  funds: TreasuryFundRow[]
  paymentRequests: TreasuryPaymentRequestRow[]
}

export interface TreasuryDashboard {
  draftDocumentCount: number
  draftDocumentAmount: number
  outstandingDocumentCount: number
  outstandingDocumentAmount: number
  pendingRequestCount: number
  pendingRequestAmount: number
  unreconciledBankCount: number
  unreconciledBankAmount: number
  restricted: boolean
}

export interface TreasuryJournalRow {
  rowKey: string
  sourceType: string
  sourceId: number
  sourceNo: string
  fundTransactionId: number
  transactionDate: string
  settlementMethod: 'CASH' | 'BANK'
  businessBankAccountId: number
  bankAccountName: string
  direction: 'CREDIT' | 'DEBIT'
  amount: number
  cashFlowCategory: string
  partnerId: number
  partnerName: string
  purpose: string
  status: 'ACTIVE' | 'REVERSED'
  matchedAmount: number
  unmatchedAmount: number
  createdAt: string
}

export interface Dashboard {
  stockQuantity: number
  stockCostAmount: number
  receivableAmount: number
  paidAmount: number
  todayInboundQuantity: number
  todayOutboundQuantity: number
  ownerAttention: OwnerAttention
  fulfillmentReminders: FulfillmentReminder[]
  inventoryAlerts: InventoryBatch[]
  recentInbounds: InboundRow[]
  recentReceivables: ReceivableRow[]
  recentPayments: PaymentRow[]
}

export interface FulfillmentReminder {
  sourceType: 'PURCHASE_ORDER' | 'SALES_ORDER'
  sourceId: number
  orderNo: string
  partnerName: string
  plannedDate: string
  timingBucket: 'TODAY' | 'TOMORROW' | 'UPCOMING'
  remainingLineCount: number
  warehouseName: string | null
}

export interface ReplenishmentRow {
  productId: number
  sku: string
  productName: string
  warehouseId: number
  warehouseName: string
  baseUnit: string
  onHandQuantity: number
  reservedQuantity: number
  freeQuantity: number
  incomingQuantity: number
  sales30Days: number
  averageDailySales: number
  leadTimeDays: number
  targetCoverageDays: number
  coverageDays: number | null
  suggestedQuantity: number
  oldestBatchDate: string | null
  oldestBatchAgeDays: number
  stockAmount: number
  attentionLevel: 'NORMAL' | 'REPLENISH' | 'AGED'
  explanation: string
}

export interface ContributionRow {
  sourceType: 'SALES_ORDER' | 'OUTBOUND'
  sourceId: number
  sourceNo: string
  partnerId: number
  partnerName: string
  businessDate: string
  status: string
  revenue: number
  inventoryCost: number
  allocatedExpense: number
  grossMargin: number
  contributionMargin: number
  outstandingAmount: number
}

export interface OwnerOperationsSummary {
  businessDate: string
  todaySales: number
  monthSales: number
  monthContributionMargin: number
  availableCashAndBank: number
  overdueReceivable: number
  duePayable7Days: number
  agedStockAmount: number
  pendingDeliveryCount: number
  pendingQualityCount: number
  replenishmentAttentionCount: number
}

export interface PurchaseQuoteItem {
  id: number
  productId: number
  productSku: string
  productName: string
  requestedQuantity: number
  requestedUnit: string
  quotedUnitPrice: number
  amount: number
}

export interface PurchaseQuoteOption {
  id: number
  purchaseOrderId: number
  supplierId: number
  supplierName: string
  quoteDate: string
  priceIncludesTax: boolean
  goodsAmount: number
  freightAmount: number
  totalAmount: number
  leadTimeDays: number
  settlementTerms: string
  sourceNote: string
  status: 'ACTIVE' | 'SELECTED' | 'VOID'
  selectedByName: string
  selectedAt: string | null
  voidReason: string
  items: PurchaseQuoteItem[]
}

export interface SalesCostAllocation {
  id: number
  treasuryDocumentId: number
  treasuryDocumentNo: string
  treasuryDocumentType: string
  summary: string
  documentAmount: number
  documentAllocatedAmount: number
  sourceType: 'SALES_ORDER' | 'OUTBOUND'
  sourceId: number
  amount: number
  reason: string
  status: 'ACTIVE' | 'REVERSED'
  createdByName: string
  reversalReason: string
  reversedByName: string
  reversedAt: string | null
  createdAt: string
}

export interface ExpenseSource {
  id: number
  documentNo: string
  documentType: string
  partnerId: number
  partnerName: string
  businessDate: string
  summary: string
  totalAmount: number
  allocatedAmount: number
  availableAmount: number
  status: string
}

export interface OutboundDeliveryEvent {
  id: number
  action: string
  fromStatus: string
  toStatus: string
  reason: string
  operatorName: string
  createdAt: string
}

export interface OutboundDelivery {
  id: number
  outboundId: number
  outboundNo: string
  deliveryMode: 'SELF_PICKUP' | 'OWN_DELIVERY' | 'LOGISTICS'
  carrierName: string
  trackingNo: string
  handlerName: string
  status: 'PENDING' | 'DISPATCHED' | 'SIGNED' | 'EXCEPTION'
  dispatchedAt: string | null
  signedAt: string | null
  recipientName: string
  exceptionNote: string
  memo: string
  versionNo: number
  createdByName: string
  updatedByName: string
  createdAt: string
  updatedAt: string
  events: OutboundDeliveryEvent[]
}

export interface ExternalShare {
  id: number
  shareNo: string
  sourceType: 'SALES_ORDER' | 'PURCHASE_ORDER'
  sourceId: number
  sourceNo: string
  partnerId: number
  partnerName: string
  expiresAt: string
  status: 'ACTIVE' | 'REVOKED' | 'EXPIRED'
  createdByName: string
  viewCount: number
  lastViewedAt: string | null
  revokeReason: string
  createdAt: string
}

export interface PublicOrderLine {
  productSku: string
  productName: string
  productSpec: string
  quantity: number
  unit: string
  unitPrice: number
  amount: number
}

export interface PublicOrder {
  sourceType: 'SALES_ORDER' | 'PURCHASE_ORDER'
  sourceNo: string
  partnerName: string
  businessDate: string
  status: string
  priceIncludesTax: boolean
  totalAmount: number
  memo: string
  items: PublicOrderLine[]
}

export interface PurchaseFollowupItem {
  purchaseOrderItemId: number
  productSku: string
  productName: string
  unit: string
  orderedQuantity: number
  dispatchedQuantity: number
  receivedQuantity: number
  inTransitQuantity: number
  remainingToDispatch: number
}

export interface PurchaseFollowupEvent {
  id: number
  eventType: 'SUPPLIER_DISPATCHED' | 'ETA_CHANGED' | 'DELAYED' | 'EXCEPTION' | 'RESOLVED'
  occurredOn: string
  expectedArrivalDate: string | null
  carrierName: string
  trackingNo: string
  exceptionType: string
  reason: string
  operatorName: string
  createdAt: string
  items: Array<{ id: number; purchaseOrderItemId: number; productSku: string; productName: string; dispatchedQuantity: number; unit: string }>
}

export interface PurchaseFollowupSummary {
  purchaseOrderId: number
  purchaseOrderNo: string
  supplierName: string
  orderStatus: string
  fulfillmentStatus: string
  latestExpectedArrivalDate: string | null
  delayDays: number
  openException: boolean
  latestException: string
  orderedQuantity: number
  dispatchedQuantity: number
  receivedQuantity: number
  inTransitQuantity: number
  remainingToDispatch: number
  items: PurchaseFollowupItem[]
  events: PurchaseFollowupEvent[]
}

export interface CashWindowSource {
  sourceType: string
  sourceId: number
  sourceNo: string
  partnerName: string
  dueDate: string
  direction: 'INFLOW' | 'OUTFLOW'
  factLevel: string
  amount: number
  explanation: string
}

export interface CashWindow {
  snapshotAt: string
  startDate: string
  endDate: string
  openingCash: number
  receivable30Days: number
  payable30Days: number
  conservativeGap7Days: number
  scheduledGap7Days: number
  conservativeGap30Days: number
  scheduledGap30Days: number
  firstConservativeGapDate: string | null
  firstScheduledGapDate: string | null
  days: Array<{ date: string; inflow: number; outflow: number; conservativeBalance: number; scheduledBalance: number; conservativeGap: number; scheduledGap: number }>
  sources: CashWindowSource[]
  formula: string
}

export type ConfirmationPacketType = 'CUSTOMER_QUOTE' | 'CUSTOMER_STATEMENT' | 'DELIVERY_SUMMARY'

export interface ConfirmationPacket {
  id: number
  packetNo: string
  packetType: ConfirmationPacketType
  sourceType: string
  sourceId: number
  sourceNo: string
  partnerId: number
  partnerName: string
  asOfDate: string
  snapshotHash: string
  expiresAt: string
  status: 'ACTIVE' | 'CONFIRMED' | 'DISPUTED' | 'REVOKED' | 'EXPIRED'
  createdByName: string
  viewCount: number
  lastViewedAt: string | null
  revokeReason: string
  createdAt: string
  decision: string | null
  confirmerName: string | null
  comment: string | null
  confirmedAt: string | null
}

export interface PublicConfirmationPacket {
  packetNo: string
  packetType: ConfirmationPacketType
  sourceNo: string
  partnerName: string
  asOfDate: string
  snapshotHash: string
  expiresAt: string
  status: string
  snapshot: Record<string, any>
  confirmation: null | { decision: string; confirmerName: string; contactTail: string; comment: string; snapshotHash: string; confirmedAt: string }
}

export interface DocumentIntakeCandidate {
  id: number
  fieldCode: string
  fieldLabel: string
  candidateValue: string
  confirmedValue: string
  confidence: number
  sourceExcerpt: string
  sortNo: number
}

export interface DocumentIntakeBatch {
  id: number
  intakeNo: string
  documentType: 'SUPPLIER_QUOTE' | 'WEIGH_TICKET' | 'SIGNED_RECEIPT' | 'PACKAGE_LABEL'
  originalFileName: string
  contentType: string
  fileSize: number
  sha256: string
  extractionMode: 'PDF_TEXT' | 'TEXT' | 'PROVIDED_TEXT' | 'OCR' | 'FILE_ONLY'
  ocrEngine: string
  ocrModel: string
  ocrResponseSha256: string
  status: 'REVIEW_REQUIRED' | 'CONFIRMED' | 'REJECTED' | 'FAILED'
  errorMessage: string
  uploadedByName: string
  reviewedByName: string
  reviewedAt: string | null
  reviewNote: string
  createdAt: string
  candidates: DocumentIntakeCandidate[]
}

export interface IntegrationAccessKey {
  id: number
  keyNo: string
  keyName: string
  partnerId: number
  partnerName: string
  scopes: string
  expiresAt: string
  status: 'ACTIVE' | 'REVOKED' | 'EXPIRED'
  createdByName: string
  useCount: number
  lastUsedAt: string | null
  revokeReason: string
  createdAt: string
}

export interface InventoryNonTradeRow {
  id: number
  documentNo: string
  businessType: 'SAMPLE_OUT' | 'INTERNAL_USE' | 'NORMAL_LOSS' | 'OTHER_OUT' | 'OTHER_IN'
  direction: 'IN' | 'OUT'
  businessDate: string
  productId: number
  sku: string
  productName: string
  spec: string
  warehouseId: number
  warehouseName: string
  batchId: number
  batchNo: string
  quantity: number
  baseUnit: string
  unitCost: number
  amountCost: number
  quantityBefore: number
  amountBefore: number
  recipient: string
  reason: string
  memo: string
  status: 'DRAFT' | 'ISSUED_PENDING_SIGNATURE' | 'CONFIRMED' | 'CANCELLED' | 'REVERSED'
  cancelReason: string
  signedAttachmentId: number | null
  signedAttachmentSha256: string
  handlerName: string
  itemCount: number
  totalAmountCost: number | null
  issuedByName: string
  issuedAt: string | null
  createdByName: string
  confirmedByName: string
  confirmedAt: string | null
  reversalReason: string
  reversedByName: string
  reversedAt: string | null
  createdAt: string
}

export interface InventoryNonTradePackageAllocation {
  packageInstanceId: number
  quantity: number
  packageCode?: string | null
  unitName?: string | null
  quantityBefore?: number | null
  quantityAfter?: number | null
}

export interface InventoryNonTradeItem {
  id: number
  lineNo: number
  productId: number
  sku: string
  productName: string
  spec: string
  warehouseId: number
  warehouseName: string
  batchId: number
  batchNo: string
  quantity: number
  baseUnit: string
  unitCost: number | null
  amountCost: number | null
  quantityBefore: number
  quantityAfter: number
  amountBefore: number | null
  packageInstanceIds: number[]
  packageAllocations: InventoryNonTradePackageAllocation[]
}

export interface InventoryNonTradeDetail {
  header: InventoryNonTradeRow
  items: InventoryNonTradeItem[]
  events: InventoryEvent[]
}

export interface SettlementAttention {
  dueTodayCount: number
  dueTodayAmount: number
  overdueCount: number
  overdueAmount: number
}

export interface OwnerAttention {
  completeScope: boolean
  receivables: SettlementAttention
  payables: SettlementAttention
  inventory: {
    riskCount: number
    reconciliationMismatchCount: number
    negativeBalanceCount: number
    unavailableBatchCount: number
  }
  bank: {
    unmatchedStatementCount: number
    unmatchedStatementAmount: number
    openSessionCount: number
  }
  invoices: {
    unreconciledCount: number
    unreconciledAmount: number
    verificationPendingCount: number
  }
  close: {
    periodId: number
    periodName: string
    periodEndDate: string | null
    periodStatus: string
    blockingCheckCount: number
    blockingIssueCount: number
    blockingSummary: string
  }
}

export interface SalesOrderRow {
  id: number
  orderNo: string
  title: string
  customerName: string
  businessDate: string
  deliveryPlanDate: string | null
  fulfillmentWarehouseId: number
  fulfillmentWarehouseName: string | null
  originalAmount: number
  discountAmount: number
  adjustmentAmount: number
  totalAmount: number
  receivableAmount: number
  receivedAmount: number
  adjustedAmount: number
  cashReceivedAmount: number
  outstandingAmount: number
  paymentStatus: string
  paymentTerm: string
  shipmentPaymentPolicy: 'NONE' | 'SHIPMENT_PAID' | 'ORDER_PAID'
  requiredDepositAmount: number
  reservedPrepaymentAmount: number
  prepaymentShortfall: number
  shipmentFundedAmount: number
  shipmentRequiredAmount: number
  shipmentPaymentShortfall: number
  status: string
}

export interface SalesOrderDetail extends SalesOrderRow {
  customerId: number
  addressId: number
  addressSnapshot: string
  discountRate: number
  priceIncludesTax: boolean
  taxRateSummary: string
  taxRuleSnapshot: string
  amountExcludingTax: number
  taxAmount: number
  taxRoundingDifference: number
  roundedAmount: number
  depositMode: string
  depositRate: number
  customerAdvanceBalance: number
  customerAdvanceAvailable: number
  memo: string
  cancelReason?: string
  cancelledAt?: string | null
  items: SalesOrderItemDetail[]
}

export type AtpStatus = 'AVAILABLE' | 'SHORTAGE' | 'UNALLOCATED_DEMAND' | 'UNSCHEDULED_SUPPLY'

export interface AtpSummary {
  productId: number
  sku: string
  productName: string
  baseUnit: string
  warehouseId: number
  warehouseName: string
  asOfDate: string
  onHandQuantity: number
  outboundDraftQuantity: number
  salesCommitmentQuantity: number
  linkedDraftCoveredQuantity: number
  purchaseIncomingQuantity: number
  transferIncomingQuantity: number
  atpQuantity: number
  unallocatedSalesCommitmentQuantity: number
  unallocatedPurchaseIncomingQuantity: number
  status: AtpStatus
  calculatedAt: string
}

export interface AtpTimelineRow {
  eventDate: string
  eventType: 'SALES_COMMITMENT' | 'PURCHASE_INCOMING' | 'TRANSFER_INCOMING' | 'OUTBOUND_DRAFT'
  sourceType: string
  sourceId: number
  sourceNo: string
  productId: number
  warehouseId: number
  direction: 'DEMAND' | 'SUPPLY'
  quantity: number
  coveredQuantity: number
  netQuantity: number
  status: string
}

export interface AtpIssue {
  code: string
  message: string
  shortageDate: string | null
  shortageQuantity: number
}

export interface AtpOrderItemEvaluation {
  salesOrderItemId: number
  productId: number
  sku: string
  productName: string
  baseUnit: string
  commitmentQuantity: number
  onHandQuantity: number
  outboundDraftQuantity: number
  salesCommitmentQuantity: number
  linkedDraftCoveredQuantity: number
  purchaseIncomingQuantity: number
  transferIncomingQuantity: number
  atpBeforeQuantity: number
  atpAfterQuantity: number
  earliestShortageDate: string | null
  shortageQuantity: number
}

export interface AtpOrderEvaluation {
  salesOrderId: number
  salesOrderNo: string
  warehouseId: number
  warehouseName: string | null
  promiseDate: string | null
  calculationVersion: string
  canPromise: boolean
  issues: AtpIssue[]
  items: AtpOrderItemEvaluation[]
  calculatedAt: string
}

export interface AtpSnapshot {
  id: number
  snapshotNo: string
  action: 'CONFIRM' | 'AMEND' | 'CANCEL'
  decision: 'PROMISED' | 'RELEASED'
  salesOrderId: number
  salesOrderItemId: number
  salesOrderNo: string
  revisionNo: number
  productId: number
  sku: string
  productName: string
  warehouseId: number
  warehouseName: string
  promiseDate: string
  commitmentQuantity: number
  onHandQuantity: number
  outboundDraftQuantity: number
  salesCommitmentQuantity: number
  linkedDraftCoveredQuantity: number
  purchaseIncomingQuantity: number
  transferIncomingQuantity: number
  atpBeforeQuantity: number
  atpAfterQuantity: number
  earliestShortageDate: string | null
  operatorId: number
  operatorName: string
  calculationVersion: string
  createdAt: string
}

export interface SalesOrderAdvanceAllocation {
  id: number
  salesOrderId: number
  sourcePaymentId: number
  sourcePaymentNo: string
  amount: number
  status: string
  appliedPaymentId: number
  memo: string
  createdAt: string
}

export interface SalesOrderPrepaymentSummary {
  salesOrderId: number
  orderNo: string
  customerId: number
  customerName: string
  orderStatus: string
  paymentTerm: string
  orderAmount: number
  requiredAmount: number
  reservedAmount: number
  shortfallAmount: number
  customerAdvanceBalance: number
  customerAdvanceReserved: number
  customerAdvanceAvailable: number
  allocations: SalesOrderAdvanceAllocation[]
}

export interface ReceivableAdjustmentRow {
  id: number
  adjustmentNo: string
  customerId: number
  customerName: string
  salesOrderId: number
  salesOrderNo: string
  adjustmentType: 'COMMERCIAL_DISCOUNT' | 'BAD_DEBT' | 'SMALL_WAIVER'
  amount: number
  occurredDate: string
  status: 'DRAFT' | 'CONFIRMED' | 'CANCELLED' | 'REVERSED'
  reason: string
  evidenceRef: string
  confirmedAt: string | null
  reversalNo: string
  reversalReason: string
  reversedAt: string | null
  createdAt: string
}

export interface AcceptanceBillRow {
  id: number
  billNo: string
  instrumentNo: string
  billType: 'BANK_ACCEPTANCE' | 'COMMERCIAL_ACCEPTANCE'
  customerId: number
  customerName: string
  salesOrderId: number
  salesOrderNo: string
  faceAmount: number
  issueDate: string
  dueDate: string
  receivedDate: string
  drawer: string
  acceptor: string
  settlementPaymentId: number
  status: 'HELD' | 'DISCOUNTED' | 'MATURED' | 'ENDORSED' | 'BOUNCED'
  discountDate: string | null
  discountBank: string
  discountFee: number
  netProceeds: number
  realizedCashAmount: number
  memo: string
  createdAt: string
  updatedAt: string
}

export interface AcceptanceEventRow {
  id: number
  eventNo: string
  billId: number
  eventType: 'RECEIVE' | 'DISCOUNT' | 'MATURITY' | 'ENDORSE' | 'BOUNCE'
  occurredDate: string
  cashAmount: number
  feeAmount: number
  businessBankAccountId: number | null
  businessBankAccountName: string | null
  counterparty: string
  memo: string
  createdAt: string
}

export interface CustomerAdvanceRow {
  customerId: number
  customerName: string
  balance: number
  reservedAmount: number
  availableAmount: number
  updatedAt: string
}

export interface CustomerAdvanceRefundRow {
  id: number
  refundNo: string
  customerId: number
  customerName: string
  amount: number
  refundMethod: string
  businessBankAccountId: number | null
  businessBankAccountName: string | null
  refundedDate: string
  status: 'ACTIVE' | 'REVERSED'
  memo: string
  reversalNo: string
  reversalReason: string
  reversedAt: string | null
  createdAt: string
}

export interface SalesOrderItemDetail {
  id: number
  productId: number
  sku: string
  productName: string
  grade: string
  temper: string
  standard: string
  spec: string
  unit: string
  quantity: number
  requestedQuantity?: number
  requestedUnit?: string
  baseUnit?: string
  pricingQuantity?: number
  packagingPlanJson?: string
  packagingRevisionId?: number
  packagingSnapshotHash?: string
  priceUnit: string
  packageQuantity: number
  packageUnit: string
  priceSourceCode: string
  priceSourceName: string
  priceRuleVersionId: number
  priceSnapshotJson: string
  noCharge: boolean
  unitPrice: number
  amount: number
  taxRate: number
  unitPriceExcludingTax: number
  amountExcludingTax: number
  taxAmount: number
  settlementTotalAmount: number
  taxRoundingDifference: number
  status: string
  shippedQuantity: number
  shippedRequestedQuantity: number
}

export type RecordStatus = 'ENABLED' | 'DISABLED'
export type EmploymentStatus = 'ACTIVE' | 'LEFT'
export type DataScopeMode = 'ALL' | 'DEPARTMENT_AND_CHILDREN' | 'DEPARTMENT' | 'SELF' | 'CUSTOM'
export type DataScopeDimension = 'DOCUMENT' | 'WAREHOUSE' | 'PARTNER' | 'PRODUCT' | 'ACCOUNT'

export interface Department {
  id: number
  parentId: number
  code: string
  name: string
  managerEmployeeId: number | null
  managerName: string
  level: number
  fullPath: string
  sortNo: number
  status: RecordStatus
  disabledReason: string
  employeeCount: number
}

export interface Employee {
  id: number
  employeeNo: string
  name: string
  departmentId: number
  departmentName: string
  jobTitle: string
  mobile: string
  email: string
  hireDate: string | null
  leaveDate: string | null
  departmentManager: boolean
  employmentStatus: EmploymentStatus
  leaveReason: string
  userId: number | null
  loginEnabled: boolean
  username: string
  roleNames: string[]
}

export interface PermissionItem {
  id: number
  code: string
  moduleCode: string
  moduleName: string
  permissionType: 'MENU' | 'ACTION' | 'FIELD'
  name: string
  description: string
  sortNo: number
}

export interface DataScopeRule {
  dimension: DataScopeDimension
  mode: DataScopeMode
  selectedIds: number[]
}

export interface DataScopeOption {
  id: number
  name: string
  secondaryText: string
}

export interface SecurityRole {
  id: number
  code: string
  name: string
  description: string
  systemPreset: boolean
  status: RecordStatus
  userCount: number
  permissionCodes: string[]
  dataScopes: DataScopeRule[]
}

export interface RoleAcceptanceCheck {
  groupCode: 'ROLE' | 'PERMISSION' | 'DATA_SCOPE'
  checkCode: string
  checkName: string
  expectedValue: string
  actualValue: string
  passed: boolean
}

export interface RoleAcceptanceRow {
  roleCode: string
  roleName: string
  userCount: number
  passed: boolean
  checks: RoleAcceptanceCheck[]
}

export interface DefaultRoleAcceptance {
  checkedAt: string
  passed: boolean
  failedCheckCount: number
  roles: RoleAcceptanceRow[]
}

export interface SystemUser {
  id: number
  employeeId: number
  employeeNo: string
  employeeName: string
  departmentId: number
  departmentName: string
  username: string
  mobile: string
  status: RecordStatus
  loginEnabled: boolean
  passwordChangeRequired: boolean
  lastLoginAt: string | null
  roleIds: number[]
  roleNames: string[]
}

export interface AuthSession {
  sessionId: number
  userId: number
  employeeId: number
  employeeName: string
  employeeNo: string
  departmentId: number
  departmentName: string
  username: string
  passwordChangeRequired: boolean
  roleNames: string[]
  permissionCodes: string[]
}

export interface DeviceSession {
  id: number
  current: boolean
  clientName: string
  ipAddress: string
  createdAt: string
  lastSeenAt: string
  expiresAt: string
}

export interface SecurityAuditRow {
  eventId: string
  operatorUserId: number
  operatorName: string
  actionCode: string
  resultCode: 'SUCCESS' | 'DENIED' | 'FAILED'
  failureCode: string
  targetType: string
  targetId: number
  targetBusinessKey: string
  summary: string
  ipAddress: string
  traceId: string
  requestMethod: string
  requestPath: string
  occurredAt: string
}

export interface SecurityAuditQuery {
  action?: string
  result?: 'SUCCESS' | 'DENIED' | 'FAILED' | ''
  operatorUserId?: number
  from?: string
  to?: string
  limit?: number
}

export type BusinessExportStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'

export interface BusinessExportTask {
  id: string
  formatVersion: string
  scopeType: 'FULL'
  status: BusinessExportStatus
  progressPercent: number
  currentPhase: string
  requestedByUserId: number
  requestedByName: string
  retryOf: string | null
  fileName: string
  archiveBytes: number
  archiveSha256: string
  exportedTableCount: number
  exportedRecordCount: number
  exportedAttachmentCount: number
  errorCode: string
  errorMessage: string
  requestedAt: string
  startedAt: string
  completedAt: string
  downloadedAt: string
  downloadable: boolean
}

export interface BusinessExportTaskList {
  items: BusinessExportTask[]
  hasActiveTask: boolean
}

export interface BusinessExportDownload {
  blob: Blob
  fileName: string
  sha256: string
}

export type StorageRiskLevel = 'NORMAL' | 'WARNING' | 'CRITICAL' | 'DANGER' | 'UNKNOWN'

export interface StorageUsage {
  databaseBytes: number
  attachmentBytes: number
  exportBytes: number
  backupBytes: number
  logBytes: number
}

export interface StorageFilesystem {
  capacityBytes: number
  availableBytes: number
  riskLevel: StorageRiskLevel
}

export interface StorageTargetStatus {
  target: 'local' | 'nas' | 's3' | string
  type: 'FILESYSTEM' | 'S3_COMPATIBLE' | string
  location: string
  backupCount: number
  backupBytes: number
  capacityBytes: number | null
  availableBytes: number | null
  quotaKnown: boolean
  quotaMessage: string
  riskLevel: StorageRiskLevel
  available: boolean
  errorCode: string
}

export interface StorageCapacityStatus {
  formatVersion: 'copper-capacity/v1'
  observedAt: string | null
  snapshotStale: boolean
  runtimeRiskLevel: StorageRiskLevel
  usage: StorageUsage
  dataFilesystem: StorageFilesystem
  targets: StorageTargetStatus[]
  lastSuccessfulBackup: {
    backupId: string
    completedAt: string
    archive: string
  } | null
  policy: {
    quotaSource: 'CUSTOMER_STORAGE'
    artificialCopperQuota: false
    warning: string
    critical: string
    danger: string
  }
  statusMessage: string
}

export interface CrmOption {
  id: number
  optionType: string
  optionCode: string
  optionName: string
  sortNo: number
  builtIn: boolean
  status: string
}

export interface CrmStageSummary { stage: string; count: number; amount: number }
export interface CrmDashboard {
  openPipelineAmount: number
  openOpportunityCount: number
  openLeadCount: number
  overduePlanCount: number
  dueTodayPlanCount: number
  activeQuoteCount: number
  acceptedQuoteAmount: number
  expiringQuoteCount: number
  repurchaseReminderCount: number
  receivableReminderCount: number
  receivableReminderAmount: number
  stages: CrmStageSummary[]
  urgentPlans: CrmPlan[]
  expiringQuotes: CrmQuote[]
  repurchaseReminders: CrmCustomerAttention[]
  receivableReminders: CrmCustomerAttention[]
  customerRanking: CrmCustomerAnalysisRow[]
}

export interface CrmCustomerAttention {
  customerProfileId: number
  partnerId: number
  partnerName: string
  contactName: string
  contactPhone: string
  receivableBalance: number
  overdueAmount: number
  lastBusinessDate: string | null
  suggestedDate: string | null
  overdueDays: number
  repurchaseCycleDays: number
  recentProducts: string
}

export interface CrmDuplicateCandidate {
  matchType: string
  sourceId: number
  sourceNo: string
  name: string
  phone: string
  email: string
  status: string
}

export interface CrmLead {
  id: number
  leadNo: string
  subject: string
  companyName: string
  contactName: string
  phone: string
  email: string
  wechat: string
  sourceCode: string
  demand: string
  estimatedAmount: number
  ownerUserId: number
  ownerName: string
  status: 'OPEN' | 'QUALIFIED' | 'DISQUALIFIED'
  partnerId: number | null
  partnerName: string | null
  contactId: number | null
  qualifiedOpportunityId: number | null
  disqualifiedReasonCode: string
  disqualifiedReason: string
  qualifiedAt: string | null
  disqualifiedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface CrmLeadDetail { lead: CrmLead; activities: CrmActivity[]; plans: CrmPlan[]; events: CrmEvent[] }

export interface CrmCustomer {
  id: number
  partnerId: number
  businessNo: string
  partnerName: string
  contactName: string
  contactPhone: string
  lifecycleStage: 'PROSPECT' | 'ACTIVE' | 'DORMANT' | 'LOST'
  tierCode: string
  sourceCode: string
  ownerUserId: number | null
  ownerName: string
  tags: string
  annualPotential: number
  preferredCategory: string
  competitor: string
  nextAction: string
  nextActionDate: string | null
  repurchaseReminderEnabled: boolean
  repurchaseCycleDays: number
  wonReasonCode: string
  lostReasonCode: string
  lostReason: string
  status: string
  updatedAt: string
  openPipelineAmount: number
  lastActivityAt: string | null
  nextPlanAt: string | null
}

export interface CrmContact {
  id: number
  name: string
  jobTitle: string
  department: string
  phone: string
  wechat: string
  email: string
  preferredMethod: string
  firstChoice: boolean
  status: string
}

export interface CrmCustomerAnalysis {
  partnerId: number
  shippedRevenue: number
  returnedRevenue: number
  netRevenue: number
  inventoryCost: number
  returnedCost: number
  grossProfit: number
  grossMargin: number
  receivableBalance: number
  overdueAmount: number
  receivedAmount: number
  lastBusinessDate: string | null
  recentProducts: string
  openPipelineAmount: number
}

export interface CrmCustomerAnalysisRow {
  customerProfileId: number
  partnerId: number
  partnerName: string
  contactName: string
  contactPhone: string
  lifecycleStage: string
  tierCode: string
  repurchaseReminderEnabled: boolean
  repurchaseCycleDays: number
  netRevenue: number
  grossProfit: number
  receivableBalance: number
  overdueAmount: number
  lastBusinessDate: string | null
  latestOrderDate: string | null
  recentProducts: string
  openPipelineAmount: number
}

export interface CrmCustomerDetail {
  customer: CrmCustomer
  analysis: CrmCustomerAnalysis
  contacts: CrmContact[]
  opportunities: CrmOpportunity[]
  quotes: CrmQuote[]
  activities: CrmActivity[]
  plans: CrmPlan[]
  events: CrmEvent[]
}

export interface CrmOpportunity {
  id: number
  opportunityNo: string
  partnerId: number
  partnerName: string
  contactId: number | null
  contactName: string | null
  sourceLeadId: number | null
  title: string
  stage: 'QUALIFY' | 'NEEDS' | 'PROPOSAL' | 'NEGOTIATION' | 'WON' | 'LOST'
  probability: number
  estimatedAmount: number
  expectedCloseDate: string | null
  ownerUserId: number
  ownerName: string
  demand: string
  competitor: string
  winReasonCode: string
  winReason: string
  lossReasonCode: string
  lossReason: string
  wonAt: string | null
  lostAt: string | null
  updatedAt: string
}

export interface CrmOpportunityDetail { opportunity: CrmOpportunity; quotes: CrmQuote[]; activities: CrmActivity[]; plans: CrmPlan[]; events: CrmEvent[] }

export interface CrmQuote {
  id: number
  quoteNo: string
  revisionNo: number
  previousQuoteId: number | null
  opportunityId: number | null
  opportunityNo: string | null
  partnerId: number
  partnerName: string
  contactId: number | null
  contactName: string | null
  addressId: number | null
  title: string
  quoteDate: string
  validUntil: string
  priceIncludesTax: boolean
  taxRate: number
  taxRuleSnapshot: string
  originalAmount: number
  discountRate: number
  discountAmount: number
  adjustmentAmount: number
  amountExcludingTax: number
  taxAmount: number
  totalAmount: number
  paymentTerm: string
  shipmentPaymentPolicy: string
  depositMode: string
  depositRate: number
  requiredDepositAmount: number
  deliveryPlanDate: string | null
  memo: string
  status: 'DRAFT' | 'ACTIVE' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED' | 'REVISED'
  decisionReasonCode: string
  decisionReason: string
  salesOrderId: number | null
  salesOrderNo: string | null
  updatedAt: string
}

export interface CrmQuoteItem {
  id: number
  productId: number
  productSkuSnapshot: string
  productNameSnapshot: string
  productSpecSnapshot: string
  requestedQuantity: number
  requestedUnit: string
  actualBaseQuantity: number | null
  baseQuantity: number
  baseUnit: string
  pricingQuantity: number
  priceUnit: string
  packageUnit: string
  packagingPlanJson: string
  packagingEffectiveRevisionId: number
  packagingSnapshotHash: string
  priceSourceCode: string
  priceSourceName: string
  priceRuleVersionId: number
  priceSnapshotJson: string
  noCharge: boolean
  unitPrice: number
  taxRate: number
  amountExcludingTax: number
  taxAmount: number
  totalAmount: number
  sortNo: number
}

export interface CrmQuoteDetail { quote: CrmQuote; items: CrmQuoteItem[]; activities: CrmActivity[]; plans: CrmPlan[]; events: CrmEvent[] }

export interface CrmActivity {
  id: number
  targetType: string
  targetId: number
  partnerId: number | null
  partnerName: string | null
  contactId: number | null
  contactName: string | null
  activityType: string
  subject: string
  occurredAt: string
  durationMinutes: number
  outcome: string
  nextAction: string
  ownerUserId: number
  ownerName: string
  sourceType: string
  sourceId: number | null
  createdAt: string
}

export interface CrmPlan {
  id: number
  targetType: string
  targetId: number
  partnerId: number | null
  partnerName: string | null
  contactId: number | null
  contactName: string | null
  subject: string
  planType: string
  dueAt: string
  priority: string
  reminderMinutes: number
  ownerUserId: number
  ownerName: string
  status: 'OPEN' | 'COMPLETED' | 'CANCELLED'
  completionActivityId: number | null
  cancelReason: string
  completedAt: string | null
  cancelledAt: string | null
  createdAt: string
}

export interface CrmEvent {
  id: number
  aggregateType: string
  aggregateId: number
  eventType: string
  eventVersion: number
  partnerId: number | null
  actorUserId: number
  actorName: string
  payloadJson: string
  occurredAt: string
}

export type DataMigrationStatus = 'PREFLIGHT_FAILED' | 'PREFLIGHT_PASSED' | 'APPLIED' | 'CUTOVER' | 'ROLLED_BACK'

export interface DataMigrationBatchRow {
  id: number
  batchNo: string
  openingDate: string
  sourceFileName: string
  sourceFileSha256: string
  sourceFileSize: number
  status: DataMigrationStatus
  totalRows: number
  validRows: number
  errorRows: number
  appliedRows: number
  openingVoucherId: number
  openingVoucherNo: string
  openingVoucherStatus: string
  failureMessage: string
  importedByName: string
  appliedByName: string
  cutoverByName: string
  rolledBackByName: string
  preflightAt: string | null
  appliedAt: string | null
  cutoverAt: string | null
  rolledBackAt: string | null
  lastCheckedAt: string | null
  createdAt: string
}

export interface DataMigrationDomainSummary {
  domainCode: string
  domainName: string
  totalRows: number
  validRows: number
  errorRows: number
  appliedRows: number
  quantityTotal: number
  amountTotal: number
}

export interface DataMigrationRowResult {
  id: number
  domainCode: string
  sourceSheet: string
  sourceRowNo: number
  sourceKey: string
  validationStatus: string
  errorCode: string
  errorMessage: string
  targetType: string
  targetId: number
  targetCreated: boolean
  appliedAt: string | null
  rolledBackAt: string | null
}

export interface DataMigrationEvent {
  id: number
  action: string
  fromStatus: string
  toStatus: string
  reason: string
  operatorName: string
  createdAt: string
}

export interface DataMigrationCheckIssue {
  id: number
  checkCode: string
  severity: string
  domainCode: string
  expectedValue: string
  actualValue: string
  message: string
}

export interface DataMigrationCheckRun {
  id: number
  triggerType: string
  status: 'PASSED' | 'FAILED'
  checkedCount: number
  issueCount: number
  summary: Record<string, unknown>
  checkedByName: string
  createdAt: string
  issues: DataMigrationCheckIssue[]
}

export interface DataMigrationDetail {
  header: DataMigrationBatchRow
  domains: DataMigrationDomainSummary[]
  rowErrors: DataMigrationRowResult[]
  events: DataMigrationEvent[]
  latestCheck: DataMigrationCheckRun | null
  safeguards: string[]
}

const http = axios.create({
  baseURL: '/api',
  timeout: 10000,
  withCredentials: true,
})

async function unwrap<T>(request: Promise<AxiosResponse<ApiResponse<T>>>): Promise<T> {
  try {
    const response = await request
    dispatchConnectivityEvent('copper:request-succeeded')
    const body = response.data
    if (body.code !== 0) throw new Error(body.message || '请求失败')
    return body.data
  } catch (error) {
    if (axios.isAxiosError<ApiResponse<unknown>>(error)) {
      dispatchAxiosConnectivity(error.response?.status)
      if (error.response?.status === 401 && typeof window !== 'undefined') window.dispatchEvent(new CustomEvent('copper:unauthorized'))
      const fallback = error.code === 'ECONNABORTED'
        ? '请求超时，请稍后重试'
        : error.response?.status
          ? `请求失败（HTTP ${error.response.status}）`
          : `网络请求失败${error.code ? `（${error.code}）` : ''}`
      throw new Error(error.response?.data?.message || fallback)
    }
    throw error
  }
}

async function downloadBusinessExport(id: string): Promise<BusinessExportDownload> {
  try {
    const response = await http.get<Blob>(`/business-exports/${encodeURIComponent(id)}/download`, {
      responseType: 'blob',
      timeout: 0,
    })
    dispatchConnectivityEvent('copper:request-succeeded')
    const disposition = String(response.headers['content-disposition'] ?? '')
    const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
    const quoted = disposition.match(/filename="([^"]+)"/i)?.[1]
    let fileName = `copper-export-${id}.zip`
    try { fileName = encoded ? decodeURIComponent(encoded) : (quoted || fileName) } catch { fileName = quoted || fileName }
    fileName = fileName.replace(/\\/g, '/').split('/').pop() || `copper-export-${id}.zip`
    return {
      blob: response.data,
      fileName,
      sha256: String(response.headers['x-content-sha256'] ?? ''),
    }
  } catch (error) {
    if (axios.isAxiosError<Blob>(error)) {
      dispatchAxiosConnectivity(error.response?.status)
      if (error.response?.status === 401 && typeof window !== 'undefined') window.dispatchEvent(new CustomEvent('copper:unauthorized'))
      let message = error.code === 'ECONNABORTED' ? '下载超时，请稍后重试' : '导出文件下载失败'
      try {
        if (error.response?.data instanceof Blob) {
          const body = JSON.parse(await error.response.data.text()) as ApiResponse<unknown>
          if (body.message) message = body.message
        }
      } catch { /* 保留通用下载错误 */ }
      throw new Error(message)
    }
    throw error
  }
}

function dispatchConnectivityEvent(name: 'copper:request-succeeded' | 'copper:request-failed') {
  if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent(name))
}

function dispatchAxiosConnectivity(status?: number) {
  const gatewayUnavailable = status !== undefined && [502, 503, 504].includes(status)
  dispatchConnectivityEvent(status === undefined || gatewayUnavailable ? 'copper:request-failed' : 'copper:request-succeeded')
}

function packagingTemplatePayload(data: PackagingTemplateSave) {
  const {
    expectedRevisionNo = 0,
    code,
    name,
    description = '',
    status = 'ENABLED',
    changeReason,
    ...profile
  } = data
  return {
    code,
    name,
    description,
    status,
    expectedRevisionNo,
    changeReason,
    profile: {
      ...profile,
      expectedVersionNo: expectedRevisionNo,
      changeReason,
    },
  }
}

function packagingTemplatePublishPayload(data: PackagingTemplateSave) {
  const {
    expectedRevisionNo = 0,
    code: _code,
    name: _name,
    description: _description,
    status: _status,
    changeReason,
    ...profile
  } = data
  return {
    expectedRevisionNo,
    changeReason,
    profile: {
      ...profile,
      expectedVersionNo: expectedRevisionNo,
      changeReason,
    },
  }
}

function normalizeSeriesPackagingBinding(item: SeriesPackagingBinding): SeriesPackagingBinding {
  return { ...item, bindingVersionNo: item.bindingVersionNo ?? item.lockVersion ?? 0 }
}

function normalizeProductImportRow(item: ProductImportRow): ProductImportRow {
  return {
    ...item,
    decidedAction: item.decidedAction ?? item.proposedAction,
  }
}

function normalizeProductImportPreview(item: ProductImportPreview): ProductImportPreview {
  return { ...item, rows: (item.rows ?? []).map(normalizeProductImportRow) }
}

export interface DirectTradeRow {
  id: number
  tradeNo: string
  customerId: number
  customerName: string
  supplierId: number
  supplierName: string
  businessDate: string
  expectedDeliveryDate: string | null
  status: string
  totalQuantity: number
  baseUnitSummary: string
  saleOrderAmount: number
  purchaseOrderAmount: number
  saleReceivableAmount: number
  purchasePayableAmount: number
  receivedAmount: number
  paidAmount: number
  grossProfitAmount: number
  paymentStatus: string
  supplierPaymentStatus: string
  updatedAt: string
}

export interface DirectTradeItem {
  id: number
  lineNo: number
  productId: number
  productSku: string
  productName: string
  productSpec: string
  salesOrderItemId: number
  purchaseOrderItemId: number
  requestedQuantity: number
  requestedUnit: string
  quantity: number
  baseUnit: string
  priceUnit: string
  pricingQuantity: number
  packageUnit: string
  packagingPlanJson: string
  packagingEffectiveRevisionId: number
  packagingSnapshotHash: string
  saleUnitPrice: number
  purchaseUnitPrice: number
  saleAmount: number
  purchaseAmount: number
  deliveredQuantity: number
  returnedQuantity: number
  remainingQuantity: number
  recognizedSaleAmount: number
  recognizedPurchaseAmount: number
}

export interface DirectTradeDeliveryItem {
  id: number
  directTradeItemId: number
  productId: number
  productSku: string
  productName: string
  productSpec: string
  dispatchedQuantity: number
  acceptedQuantity: number
  baseUnit: string
  saleAmount: number
  purchaseAmount: number
}

export interface DirectTradeDelivery {
  id: number
  deliveryNo: string
  sequenceNo: number
  businessDate: string
  carrierName: string
  trackingNo: string
  handlerName: string
  status: string
  dispatchedQuantity: number
  acceptedQuantity: number
  saleAmount: number
  purchaseAmount: number
  recipientName: string
  differenceReason: string
  dispatchedByName: string
  acceptedByName: string
  dispatchedAt: string
  acceptedAt: string | null
  items: DirectTradeDeliveryItem[]
}

export interface DirectTradeReturn {
  id: number
  returnNo: string
  businessDate: string
  totalQuantity: number
  saleReductionAmount: number
  purchaseReductionAmount: number
  reason: string
  status: string
  createdByName: string
  confirmedAt: string
}

export interface DirectTradeEvent {
  id: number
  eventType: string
  refType: string
  refId: number
  fromStatus: string
  toStatus: string
  reason: string
  operatorName: string
  createdAt: string
}

export interface DirectTradeDetail {
  header: DirectTradeRow
  addressId: number
  addressSnapshot: string
  salesOrderId: number
  salesOrderNo: string
  purchaseOrderId: number
  purchaseOrderNo: string
  salePriceIncludesTax: boolean
  saleTaxRateSummary: string
  purchasePriceIncludesTax: boolean
  purchaseTaxRateSummary: string
  memo: string
  cancelReason: string
  createdByName: string
  confirmedByName: string
  confirmedAt: string | null
  items: DirectTradeItem[]
  deliveries: DirectTradeDelivery[]
  returns: DirectTradeReturn[]
  events: DirectTradeEvent[]
}

export const api = {
  login: (data: { username: string; password: string }) =>
    unwrap(http.post<ApiResponse<AuthSession>>('/auth/login', data)),
  logout: () => unwrap(http.post<ApiResponse<void>>('/auth/logout')),
  currentSession: () => unwrap(http.get<ApiResponse<AuthSession>>('/auth/me')),
  currentUserSessions: () => unwrap(http.get<ApiResponse<DeviceSession[]>>('/auth/sessions')),
  revokeCurrentUserSession: (id: number) =>
    unwrap(http.post<ApiResponse<{ current: boolean }>>(`/auth/sessions/${id}/revoke`)),
  changePassword: (data: { currentPassword: string; newPassword: string }) =>
    unwrap(http.post<ApiResponse<void>>('/auth/change-password', data)),
  crmDashboard: () => unwrap(http.get<ApiResponse<CrmDashboard>>('/crm/dashboard')),
  crmOptions: (params?: { optionType?: string; includeDisabled?: boolean }) =>
    unwrap(http.get<ApiResponse<CrmOption[]>>('/crm/options', { params })),
  crmDuplicates: (params: { companyName?: string; phone?: string; email?: string }) =>
    unwrap(http.get<ApiResponse<CrmDuplicateCandidate[]>>('/crm/duplicates', { params })),
  crmLeads: (params?: { status?: string; keyword?: string }) =>
    unwrap(http.get<ApiResponse<CrmLead[]>>('/crm/leads', { params })),
  crmLead: (id: number) => unwrap(http.get<ApiResponse<CrmLeadDetail>>(`/crm/leads/${id}`)),
  createCrmLead: (data: unknown) => unwrap(http.post<ApiResponse<number>>('/crm/leads', data)),
  updateCrmLead: (id: number, data: unknown) => unwrap(http.put<ApiResponse<number>>(`/crm/leads/${id}`, data)),
  qualifyCrmLead: (id: number, data: unknown) => unwrap(http.post<ApiResponse<number>>(`/crm/leads/${id}/qualify`, data)),
  disqualifyCrmLead: (id: number, data: { reasonCode: string; reason: string }) => unwrap(http.post<ApiResponse<number>>(`/crm/leads/${id}/disqualify`, data)),
  reactivateCrmLead: (id: number, data: { reasonCode: string; reason: string }) => unwrap(http.post<ApiResponse<number>>(`/crm/leads/${id}/reactivate`, data)),
  crmCustomers: (params?: { lifecycleStage?: string; tierCode?: string; keyword?: string }) =>
    unwrap(http.get<ApiResponse<CrmCustomer[]>>('/crm/customers', { params })),
  crmCustomer: (id: number) => unwrap(http.get<ApiResponse<CrmCustomerDetail>>(`/crm/customers/${id}`)),
  crmCustomerAnalysis: (partnerId: number) =>
    unwrap(http.get<ApiResponse<CrmCustomerAnalysis>>(`/crm/customers/by-partner/${partnerId}/analysis`)),
  createCrmCustomer: (data: unknown) => unwrap(http.post<ApiResponse<number>>('/crm/customers', data)),
  updateCrmCustomer: (id: number, data: unknown) => unwrap(http.put<ApiResponse<number>>(`/crm/customers/${id}`, data)),
  crmOpportunities: (params?: { stage?: string; partnerId?: number; keyword?: string }) =>
    unwrap(http.get<ApiResponse<CrmOpportunity[]>>('/crm/opportunities', { params })),
  crmOpportunity: (id: number) => unwrap(http.get<ApiResponse<CrmOpportunityDetail>>(`/crm/opportunities/${id}`)),
  createCrmOpportunity: (data: unknown) => unwrap(http.post<ApiResponse<number>>('/crm/opportunities', data)),
  updateCrmOpportunity: (id: number, data: unknown) => unwrap(http.put<ApiResponse<number>>(`/crm/opportunities/${id}`, data)),
  changeCrmOpportunityStage: (id: number, data: { stage: string; reasonCode?: string; reason?: string }) => unwrap(http.post<ApiResponse<number>>(`/crm/opportunities/${id}/stage`, data)),
  crmQuotes: (params?: { status?: string; partnerId?: number; opportunityId?: number }) =>
    unwrap(http.get<ApiResponse<CrmQuote[]>>('/crm/quotes', { params })),
  crmQuote: (id: number) => unwrap(http.get<ApiResponse<CrmQuoteDetail>>(`/crm/quotes/${id}`)),
  createCrmQuote: (data: unknown) => unwrap(http.post<ApiResponse<number>>('/crm/quotes', data)),
  updateCrmQuote: (id: number, data: unknown) => unwrap(http.put<ApiResponse<number>>(`/crm/quotes/${id}`, data)),
  activateCrmQuote: (id: number) => unwrap(http.post<ApiResponse<number>>(`/crm/quotes/${id}/activate`)),
  reviseCrmQuote: (id: number, data: { reasonCode: string; reason: string }) => unwrap(http.post<ApiResponse<number>>(`/crm/quotes/${id}/revise`, data)),
  acceptCrmQuote: (id: number, data: { reasonCode: string; reason: string }) => unwrap(http.post<ApiResponse<number>>(`/crm/quotes/${id}/accept`, data)),
  rejectCrmQuote: (id: number, data: { reasonCode: string; reason: string }) => unwrap(http.post<ApiResponse<number>>(`/crm/quotes/${id}/reject`, data)),
  cancelCrmQuote: (id: number, data: { reasonCode: string; reason: string }) => unwrap(http.post<ApiResponse<number>>(`/crm/quotes/${id}/cancel`, data)),
  convertCrmQuote: (id: number) => unwrap(http.post<ApiResponse<{ quoteId: number; salesOrderId: number; salesOrderNo: string }>>(`/crm/quotes/${id}/convert-to-sales-order`)),
  crmActivities: (params?: { targetType?: string; targetId?: number }) => unwrap(http.get<ApiResponse<CrmActivity[]>>('/crm/activities', { params })),
  createCrmActivity: (data: unknown) => unwrap(http.post<ApiResponse<number>>('/crm/activities', data)),
  crmPlans: (params?: { status?: string; dueScope?: string; overdueFirst?: boolean }) => unwrap(http.get<ApiResponse<CrmPlan[]>>('/crm/plans', { params })),
  createCrmPlan: (data: unknown) => unwrap(http.post<ApiResponse<number>>('/crm/plans', data)),
  updateCrmPlan: (id: number, data: unknown) => unwrap(http.put<ApiResponse<number>>(`/crm/plans/${id}`, data)),
  completeCrmPlan: (id: number, data: unknown) => unwrap(http.post<ApiResponse<number>>(`/crm/plans/${id}/complete`, data)),
  cancelCrmPlan: (id: number, data: { reasonCode: string; reason: string }) => unwrap(http.post<ApiResponse<number>>(`/crm/plans/${id}/cancel`, data)),
  departments: () => unwrap(http.get<ApiResponse<Department[]>>('/organization/departments')),
  createDepartment: (data: Partial<Department>) =>
    unwrap(http.post<ApiResponse<{ id: number }>>('/organization/departments', data)),
  updateDepartment: (id: number, data: Partial<Department>) =>
    unwrap(http.put<ApiResponse<{ id: number }>>(`/organization/departments/${id}`, data)),
  disableDepartment: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/organization/departments/${id}/disable`, { reason })),
  employees: (params?: { departmentId?: number; keyword?: string; employmentStatus?: EmploymentStatus }) =>
    unwrap(http.get<ApiResponse<Employee[]>>('/organization/employees', { params })),
  createEmployee: (data: Partial<Employee>) =>
    unwrap(http.post<ApiResponse<{ id: number }>>('/organization/employees', data)),
  updateEmployee: (id: number, data: Partial<Employee>) =>
    unwrap(http.put<ApiResponse<{ id: number }>>(`/organization/employees/${id}`, data)),
  leaveEmployee: (id: number, data: { leaveDate: string; reason: string }) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/organization/employees/${id}/leave`, data)),
  reinstateEmployee: (id: number) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/organization/employees/${id}/reinstate`)),
  systemUsers: () => unwrap(http.get<ApiResponse<SystemUser[]>>('/system/users')),
  createSystemUser: (data: { employeeId: number; username: string; mobile: string; initialPassword: string; roleIds: number[] }) =>
    unwrap(http.post<ApiResponse<{ id: number }>>('/system/users', data)),
  updateSystemUser: (id: number, data: { username: string; mobile: string; roleIds: number[] }) =>
    unwrap(http.put<ApiResponse<{ id: number }>>(`/system/users/${id}`, data)),
  resetUserPassword: (id: number, initialPassword: string) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/system/users/${id}/reset-password`, { initialPassword })),
  setUserLoginEnabled: (id: number, enabled: boolean, reason = '') =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/system/users/${id}/${enabled ? 'enable' : 'disable'}`, { reason })),
  revokeUserSessions: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/system/users/${id}/sessions/revoke`, { reason })),
  securityAudit: (params?: SecurityAuditQuery) =>
    unwrap(http.get<ApiResponse<SecurityAuditRow[]>>('/system/security-audit', { params })),
  permissions: () => unwrap(http.get<ApiResponse<PermissionItem[]>>('/system/permissions')),
  dataScopeOptions: (dimension: DataScopeDimension) =>
    unwrap(http.get<ApiResponse<DataScopeOption[]>>('/system/data-scope-options', { params: { dimension } })),
  roles: () => unwrap(http.get<ApiResponse<SecurityRole[]>>('/system/roles')),
  defaultRoleAcceptance: () => unwrap(http.get<ApiResponse<DefaultRoleAcceptance>>('/system/roles/default-acceptance')),
  createRole: (data: { name: string; description: string; permissionCodes: string[]; dataScopes: DataScopeRule[] }) =>
    unwrap(http.post<ApiResponse<{ id: number }>>('/system/roles', data)),
  updateRole: (id: number, data: { name: string; description: string; permissionCodes: string[]; dataScopes: DataScopeRule[] }) =>
    unwrap(http.put<ApiResponse<{ id: number }>>(`/system/roles/${id}`, data)),
  copyRole: (id: number, name: string) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/system/roles/${id}/copy`, { name })),
  disableRole: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/system/roles/${id}/disable`, { reason })),
  dataMigrations: () => unwrap(http.get<ApiResponse<DataMigrationBatchRow[]>>('/data-migrations')),
  dataMigration: (id: number) => unwrap(http.get<ApiResponse<DataMigrationDetail>>(`/data-migrations/${id}`)),
  downloadDataMigrationTemplate: async () => {
    const response = await http.get<Blob>('/data-migrations/template', { responseType: 'blob' })
    const disposition = String(response.headers['content-disposition'] ?? '')
    const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
    const quoted = disposition.match(/filename="([^"]+)"/i)?.[1]
    let fileName = 'copper-opening-data-template-v1.xlsx'
    try { fileName = encoded ? decodeURIComponent(encoded) : (quoted || fileName) } catch { fileName = quoted || fileName }
    return { blob: response.data, fileName: fileName.replace(/\\/g, '/').split('/').pop() || 'copper-opening-data-template-v1.xlsx' }
  },
  uploadDataMigration: (openingDate: string, file: File) => {
    const data = new FormData()
    data.append('openingDate', openingDate)
    data.append('file', file)
    return unwrap(http.post<ApiResponse<DataMigrationDetail>>('/data-migrations/upload', data, {
      headers: { 'Content-Type': 'multipart/form-data' }, timeout: 120000,
    }))
  },
  applyDataMigration: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<DataMigrationDetail>>(`/data-migrations/${id}/apply`, { reason }, { timeout: 120000 })),
  rollbackDataMigration: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<DataMigrationDetail>>(`/data-migrations/${id}/rollback`, { reason }, { timeout: 120000 })),
  cutoverDataMigration: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<DataMigrationDetail>>(`/data-migrations/${id}/cutover`, { reason }, { timeout: 120000 })),
  checkDataMigration: (id: number) =>
    unwrap(http.post<ApiResponse<DataMigrationCheckRun>>(`/data-migrations/${id}/consistency-check`, {}, { timeout: 120000 })),
  businessExports: () => unwrap(http.get<ApiResponse<BusinessExportTaskList>>('/business-exports')),
  storageCapacity: () => unwrap(http.get<ApiResponse<StorageCapacityStatus>>('/system/storage-capacity')),
  createBusinessExport: () => unwrap(http.post<ApiResponse<BusinessExportTask>>('/business-exports', { confirmFullExport: true })),
  retryBusinessExport: (id: string) =>
    unwrap(http.post<ApiResponse<BusinessExportTask>>(`/business-exports/${encodeURIComponent(id)}/retry`, { confirmRetry: true })),
  downloadBusinessExport,
  dashboard: () => unwrap(http.get<ApiResponse<Dashboard>>('/dashboard')),
  ownerOperationsSummary: () => unwrap(http.get<ApiResponse<OwnerOperationsSummary>>('/operations/owner-summary')),
  replenishmentSuggestions: () => unwrap(http.get<ApiResponse<ReplenishmentRow[]>>('/operations/replenishment')),
  contributionRows: (params?: { from?: string; to?: string; sourceType?: string; sourceId?: number }) =>
    unwrap(http.get<ApiResponse<ContributionRow[]>>('/operations/contributions', { params })),
  purchaseQuotes: (purchaseOrderId: number) =>
    unwrap(http.get<ApiResponse<PurchaseQuoteOption[]>>(`/purchase-orders/${purchaseOrderId}/quotes`)),
  createPurchaseQuote: (purchaseOrderId: number, data: unknown) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/purchase-orders/${purchaseOrderId}/quotes`, data)),
  selectPurchaseQuote: (purchaseOrderId: number, quoteId: number) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/purchase-orders/${purchaseOrderId}/quotes/${quoteId}/select`)),
  voidPurchaseQuote: (purchaseOrderId: number, quoteId: number, reason: string) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/purchase-orders/${purchaseOrderId}/quotes/${quoteId}/void`, { reason })),
  expenseSources: () => unwrap(http.get<ApiResponse<ExpenseSource[]>>('/operations/expense-sources')),
  salesCostAllocations: (sourceType: string, sourceId: number) =>
    unwrap(http.get<ApiResponse<SalesCostAllocation[]>>(`/operations/sales-costs/${sourceType}/${sourceId}`)),
  allocateSalesCost: (sourceType: string, sourceId: number, data: { treasuryDocumentId: number; amount: number; reason: string }) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/operations/sales-costs/${sourceType}/${sourceId}`, data)),
  reverseSalesCost: (sourceType: string, sourceId: number, allocationId: number, reason: string) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/operations/sales-costs/${sourceType}/${sourceId}/${allocationId}/reverse`, { reason })),
  outboundDelivery: (outboundId: number) =>
    unwrap(http.get<ApiResponse<OutboundDelivery | null>>(`/outbounds/${outboundId}/delivery`)),
  saveOutboundDelivery: (outboundId: number, data: { deliveryMode: string; carrierName?: string; trackingNo?: string; handlerName?: string; memo?: string }) =>
    unwrap(http.post<ApiResponse<OutboundDelivery>>(`/outbounds/${outboundId}/delivery`, data)),
  actOutboundDelivery: (outboundId: number, data: { action: string; recipientName?: string; reason?: string }) =>
    unwrap(http.post<ApiResponse<OutboundDelivery>>(`/outbounds/${outboundId}/delivery/action`, data)),
  externalShares: (params: { sourceType: string; sourceId: number }) =>
    unwrap(http.get<ApiResponse<ExternalShare[]>>('/external-shares', { params })),
  createExternalShare: (data: { sourceType: string; sourceId: number; expiresAt: string }) =>
    unwrap(http.post<ApiResponse<{ share: ExternalShare; token: string }>>('/external-shares', data)),
  revokeExternalShare: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/external-shares/${id}/revoke`, { reason })),
  publicOrderShare: (token: string) =>
    unwrap(http.get<ApiResponse<PublicOrder>>(`/public/order-shares/${encodeURIComponent(token)}`)),
  purchaseFollowup: (purchaseOrderId: number) =>
    unwrap(http.get<ApiResponse<PurchaseFollowupSummary>>(`/purchase-orders/${purchaseOrderId}/followup`)),
  addPurchaseFollowup: (purchaseOrderId: number, data: {
    eventType: string; occurredOn: string; expectedArrivalDate?: string; carrierName?: string;
    trackingNo?: string; exceptionType?: string; reason?: string;
    items?: Array<{ purchaseOrderItemId: number; dispatchedQuantity: number }>
  }) => unwrap(http.post<ApiResponse<PurchaseFollowupSummary>>(`/purchase-orders/${purchaseOrderId}/followup/events`, data)),
  cashWindow: () => unwrap(http.get<ApiResponse<CashWindow>>('/operations/cash-window')),
  confirmationPackets: (packetType: ConfirmationPacketType, sourceId: number) =>
    unwrap(http.get<ApiResponse<ConfirmationPacket[]>>('/external-confirmation-packets', { params: { packetType, sourceId } })),
  createConfirmationPacket: (data: { packetType: ConfirmationPacketType; sourceId: number; asOfDate: string; expiresAt: string }) =>
    unwrap(http.post<ApiResponse<{ packet: ConfirmationPacket; token: string }>>('/external-confirmation-packets', data)),
  revokeConfirmationPacket: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/external-confirmation-packets/${id}/revoke`, { reason })),
  publicConfirmation: (token: string) =>
    unwrap(http.get<ApiResponse<PublicConfirmationPacket>>(`/public/confirmations/${encodeURIComponent(token)}`)),
  confirmPublicPacket: (token: string, data: { decision: 'CONFIRMED' | 'DISPUTED'; confirmerName: string; contactTail?: string; comment?: string }) =>
    unwrap(http.post<ApiResponse<PublicConfirmationPacket>>(`/public/confirmations/${encodeURIComponent(token)}`, data)),
  documentIntakes: (status?: string) =>
    unwrap(http.get<ApiResponse<DocumentIntakeBatch[]>>('/document-intakes', { params: { status } })),
  documentIntake: (id: number) =>
    unwrap(http.get<ApiResponse<DocumentIntakeBatch>>(`/document-intakes/${id}`)),
  documentIntakeContent: async (id: number) =>
    (await http.get<Blob>(`/document-intakes/${id}/content`, { responseType: 'blob' })).data,
  createDocumentIntake: (documentType: string, file: File, providedText?: string, expectedProductId?: number) => {
    const data = new FormData()
    data.append('documentType', documentType)
    if (providedText) data.append('providedText', providedText)
    if (expectedProductId) data.append('expectedProductId', String(expectedProductId))
    data.append('file', file)
    return unwrap(http.post<ApiResponse<DocumentIntakeBatch>>('/document-intakes', data, {
      timeout: 120000,
    }))
  },
  reviewDocumentIntake: (id: number, data: { decision: 'CONFIRMED' | 'REJECTED'; note?: string; candidates: Array<{ fieldCode: string; confirmedValue: string }> }) =>
    unwrap(http.post<ApiResponse<DocumentIntakeBatch>>(`/document-intakes/${id}/review`, data)),
  integrationKeys: () => unwrap(http.get<ApiResponse<IntegrationAccessKey[]>>('/integration-keys')),
  createIntegrationKey: (data: { keyName: string; partnerId: number; expiresAt: string }) =>
    unwrap(http.post<ApiResponse<{ key: IntegrationAccessKey; token: string }>>('/integration-keys', data)),
  revokeIntegrationKey: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/integration-keys/${id}/revoke`, { reason })),
  inventoryNonTrade: (params?: { status?: string; batchId?: number }) =>
    unwrap(http.get<ApiResponse<InventoryNonTradeRow[]>>('/inventory/non-trade', { params })),
  inventoryNonTradeDetail: (id: number) =>
    unwrap(http.get<ApiResponse<InventoryNonTradeDetail>>(`/inventory/non-trade/${id}`)),
  createInventoryNonTradeDraft: (data: unknown) =>
    unwrap(http.post<ApiResponse<{ id: number }>>('/inventory/non-trade', data)),
  issueInventoryNonTrade: (id: number) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/inventory/non-trade/${id}/issue`)),
  archiveInventoryNonTradeSignature: (id: number, signedAttachmentId: number) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/inventory/non-trade/${id}/confirm`, { signedAttachmentId })),
  confirmInventoryNonTrade: (data: unknown) =>
    unwrap(http.post<ApiResponse<{ id: number }>>('/inventory/non-trade/quick-confirm', data)),
  reverseInventoryNonTrade: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/inventory/non-trade/${id}/reverse`, { reason })),
  inventoryBatchQualityCertificates: (batchId: number) =>
    unwrap(http.get<ApiResponse<ProductQualityCertificate[]>>(`/inventory/batches/${batchId}/quality-certificates`)),
  createInventoryBatchQualityCertificate: (batchId: number, data: ProductQualityCertificateSave) =>
    unwrap(http.post<ApiResponse<ProductQualityCertificate>>(`/inventory/batches/${batchId}/quality-certificates`, data)),
  updateInventoryBatchQualityCertificate: (batchId: number, certificateId: number, data: ProductQualityCertificateSave) =>
    unwrap(http.put<ApiResponse<ProductQualityCertificate>>(`/inventory/batches/${batchId}/quality-certificates/${certificateId}`, data)),
  reviewInventoryBatchQualityCertificate: (batchId: number, certificateId: number, data: ProductQualityCertificateReviewSave) =>
    unwrap(http.post<ApiResponse<ProductQualityCertificate>>(`/inventory/batches/${batchId}/quality-certificates/${certificateId}/review`, data)),
  inventoryBatchQualityReviews: (batchId: number, certificateId: number) =>
    unwrap(http.get<ApiResponse<ProductQualityCertificateReviewHistory[]>>(`/inventory/batches/${batchId}/quality-certificates/${certificateId}/reviews`)),
  riskExceptionPolicy: () => unwrap(http.get<ApiResponse<RiskExceptionPolicy>>('/risk-exceptions/policy')),
  updateRiskExceptionPolicy: (data: Omit<RiskExceptionPolicy, 'entityId' | 'versionNo' | 'updatedByName' | 'updatedAt'>) =>
    unwrap(http.put<ApiResponse<RiskExceptionPolicy>>('/risk-exceptions/policy', data)),
  riskExceptionDashboard: () => unwrap(http.get<ApiResponse<RiskExceptionDashboard>>('/risk-exceptions/dashboard')),
  riskExceptionRequests: (params?: { status?: RiskExceptionStatus; exceptionType?: RiskExceptionType; mineOnly?: boolean }) =>
    unwrap(http.get<ApiResponse<RiskExceptionRequest[]>>('/risk-exceptions', { params })),
  riskExceptionRequest: (id: number) => unwrap(http.get<ApiResponse<RiskExceptionDetail>>(`/risk-exceptions/${id}`)),
  riskExceptionApprovers: () => unwrap(http.get<ApiResponse<RiskExceptionApprover[]>>('/risk-exceptions/approvers')),
  riskExceptionSourceStatus: (params: { sourceType: string; sourceId: number; actionCode: string }) =>
    unwrap(http.get<ApiResponse<RiskExceptionSourceStatus>>('/risk-exceptions/source', { params })),
  submitRiskException: (data: { sourceType: string; sourceId: number; actionCode: string; reason: string }) =>
    unwrap(http.post<ApiResponse<RiskExceptionRequest>>('/risk-exceptions/requests', data)),
  submitSupplierPaymentException: (payment: unknown, reason: string) =>
    unwrap(http.post<ApiResponse<RiskExceptionRequest>>('/risk-exceptions/supplier-payment-intents', { payment, reason })),
  approveRiskException: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<RiskExceptionRequest>>(`/risk-exceptions/${id}/approve`, { reason })),
  rejectRiskException: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<RiskExceptionRequest>>(`/risk-exceptions/${id}/reject`, { reason })),
  withdrawRiskException: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<RiskExceptionRequest>>(`/risk-exceptions/${id}/withdraw`, { reason })),
  transferRiskException: (id: number, data: { targetEmployeeId: number; reason: string }) =>
    unwrap(http.post<ApiResponse<RiskExceptionRequest>>(`/risk-exceptions/${id}/transfer`, data)),
  executeRiskException: (id: number) => unwrap(http.post<ApiResponse<{ id: number }>>(`/risk-exceptions/${id}/execute`)),
  partners: (typeOrQuery?: PartnerType | PartnerQuery) => {
    const params = typeof typeOrQuery === 'string' ? { type: typeOrQuery } : typeOrQuery
    return unwrap(http.get<ApiResponse<Partner[]>>('/partners', { params }))
  },
  partner: (id: number) => unwrap(http.get<ApiResponse<Partner>>(`/partners/${id}`)),
  salesRiskPolicy: (partnerId: number) => unwrap(http.get<ApiResponse<SalesRiskPolicy>>(`/sales-risk/partners/${partnerId}/policy`)),
  updateSalesRiskPolicy: (partnerId: number, data: SalesRiskPolicySave) => unwrap(http.put<ApiResponse<SalesRiskPolicy>>(`/sales-risk/partners/${partnerId}/policy`, data)),
  partnerSummaries: (params?: { type?: PartnerType; keyword?: string; status?: string }) =>
    unwrap(http.get<ApiResponse<PartnerSummary[]>>('/partners/summaries', { params })),
  createPartner: (data: Partial<Partner>) => unwrap(http.post<ApiResponse<{ id: number }>>('/partners', data)),
  updatePartner: (id: number, data: Partial<Partner>) => unwrap(http.put<ApiResponse<{ id: number }>>(`/partners/${id}`, data)),
  contacts: (partnerId: number) => unwrap(http.get<ApiResponse<Contact[]>>(`/partners/${partnerId}/contacts`)),
  createContact: (data: Partial<Contact>) => unwrap(http.post<ApiResponse<{ id: number }>>('/contacts', data)),
  updateContact: (id: number, data: Partial<Contact>) => unwrap(http.put<ApiResponse<{ id: number }>>(`/contacts/${id}`, data)),
  addresses: (partnerId: number) => unwrap(http.get<ApiResponse<Address[]>>(`/partners/${partnerId}/addresses`)),
  createAddress: (data: Partial<Address>) => unwrap(http.post<ApiResponse<{ id: number }>>('/addresses', data)),
  updateAddress: (id: number, data: Partial<Address>) => unwrap(http.put<ApiResponse<{ id: number }>>(`/addresses/${id}`, data)),
  bankAccounts: (partnerId: number) => unwrap(http.get<ApiResponse<BankAccount[]>>(`/partners/${partnerId}/bank-accounts`)),
  createBankAccount: (data: Partial<BankAccount>) => unwrap(http.post<ApiResponse<{ id: number }>>('/bank-accounts', data)),
  updateBankAccount: (id: number, data: Partial<BankAccount>) => unwrap(http.put<ApiResponse<{ id: number }>>(`/bank-accounts/${id}`, data)),
  followups: (partnerId: number) => unwrap(http.get<ApiResponse<Followup[]>>(`/partners/${partnerId}/followups`)),
  createFollowup: (data: Partial<Followup>) => unwrap(http.post<ApiResponse<{ id: number }>>('/followups', data)),
  updateFollowup: (id: number, data: Partial<Followup>) => unwrap(http.put<ApiResponse<{ id: number }>>(`/followups/${id}`, data)),
  specialDates: (partnerId: number) => unwrap(http.get<ApiResponse<SpecialDate[]>>(`/partners/${partnerId}/special-dates`)),
  createSpecialDate: (data: Partial<SpecialDate>) => unwrap(http.post<ApiResponse<{ id: number }>>('/special-dates', data)),
  updateSpecialDate: (id: number, data: Partial<SpecialDate>) => unwrap(http.put<ApiResponse<{ id: number }>>(`/special-dates/${id}`, data)),
  products: () => unwrap(http.get<ApiResponse<Product[]>>('/products')),
  createProduct: (data: Partial<Product>) => unwrap(http.post<ApiResponse<{ id: number }>>('/products', data)),
  updateProduct: (id: number, data: Partial<Product>) => unwrap(http.put<ApiResponse<{ id: number }>>(`/products/${id}`, data)),
  checkProductDuplicate: ({ excludeProductId, ...data }: ProductDuplicateCheckRequest) =>
    unwrap(http.post<ApiResponse<ProductDuplicateCheck>>('/products/duplicate-check', data, { params: { excludeProductId } })),
  productPackagingVariants: (id: number) =>
    unwrap(http.get<ApiResponse<ProductPackagingVariant[]>>(`/products/${id}/packaging-variants`)),
  createProductPackagingVariant: (id: number, data: ProductPackagingVariantSave) =>
    unwrap(http.post<ApiResponse<ProductPackagingVariant>>(`/products/${id}/packaging-variants`, data)),
  updateProductPackagingVariant: (id: number, variantId: number, data: ProductPackagingVariantSave) =>
    unwrap(http.put<ApiResponse<ProductPackagingVariant>>(`/products/${id}/packaging-variants/${variantId}`, data)),
  productPackagingVariantRevisions: (id: number, variantId: number) =>
    unwrap(http.get<ApiResponse<ProductPackagingVariantRevision[]>>(`/products/${id}/packaging-variants/${variantId}/revisions`)),
  supplierProductOffers: (id: number) =>
    unwrap(http.get<ApiResponse<SupplierProductOffer[]>>(`/products/${id}/supplier-offers`)),
  createSupplierProductOffer: (id: number, data: SupplierProductOfferSave) =>
    unwrap(http.post<ApiResponse<SupplierProductOffer>>(`/products/${id}/supplier-offers`, data)),
  updateSupplierProductOffer: (id: number, offerId: number, data: SupplierProductOfferSave) =>
    unwrap(http.put<ApiResponse<SupplierProductOffer>>(`/products/${id}/supplier-offers/${offerId}`, data)),
  supplierProductOfferRevisions: (id: number, offerId: number) =>
    unwrap(http.get<ApiResponse<SupplierProductOfferRevision[]>>(`/products/${id}/supplier-offers/${offerId}/revisions`)),
  productImportTemplateInfo: () =>
    unwrap(http.get<ApiResponse<ProductImportTemplateInfo>>('/product-imports/template-info')),
  productImportTemplate: (format: 'xlsx' | 'csv', seriesId: number) =>
    http.get<Blob>('/product-imports/template', {
      params: { format, seriesId },
      responseType: 'blob',
    }).then((response) => response.data),
  previewProductImport: (file: File, mapping?: Record<string, string>) => {
    const data = new FormData()
    data.append('file', file)
    if (mapping && Object.keys(mapping).length) data.append('mapping', JSON.stringify(mapping))
    return unwrap(http.post<ApiResponse<ProductImportPreview>>('/product-imports/preview', data, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })).then(normalizeProductImportPreview)
  },
  productImports: () => unwrap(http.get<ApiResponse<ProductImportJob[]>>('/product-imports')),
  productImport: (id: number) => unwrap(http.get<ApiResponse<ProductImportPreview>>(`/product-imports/${id}`)).then(normalizeProductImportPreview),
  productImportSourceFile: (id: number) =>
    http.get<Blob>(`/product-imports/${id}/source-file`, { responseType: 'blob' }).then((response) => response.data),
  decideProductImportRow: (id: number, rowId: number, data: ProductImportDecision) =>
    unwrap(http.put<ApiResponse<ProductImportPreview>>(`/product-imports/${id}/rows/${rowId}/decision`, data)).then(normalizeProductImportPreview),
  commitProductImport: (id: number, data: { expectedLockVersion: number; allowSafeUpdates: boolean }) =>
    unwrap(http.post<ApiResponse<ProductImportCommitResult>>(`/product-imports/${id}/commit`, data)),
  packagingProfile: (id: number) => unwrap(http.get<ApiResponse<PackagingProfile>>(`/products/${id}/packaging-profile`)),
  packageDefinitions: (id: number) => unwrap(http.get<ApiResponse<PackageDefinition[]>>(`/products/${id}/package-definitions`)),
  savePackageDefinitions: (id: number, data: { definitions: PackageDefinitionSave[]; changeReason: string }) =>
    unwrap(http.put<ApiResponse<PackageDefinition[]>>(`/products/${id}/package-definitions`, data)),
  packageBarcode: (barcode: string) =>
    unwrap(http.get<ApiResponse<PackageBarcodeMatch>>(`/products/package-barcode/${encodeURIComponent(barcode)}`)),
  inheritedPackagingProfile: (id: number) => unwrap(http.get<ApiResponse<PackagingProfile>>(`/products/${id}/packaging-profile/inherited`)),
  savePackagingProfile: (id: number, data: PackagingProfileSave) =>
    unwrap(http.put<ApiResponse<PackagingProfile>>(`/products/${id}/packaging-profile`, data)),
  restorePackagingProfile: (id: number, data?: { expectedVersionNo?: number; changeReason?: string }) =>
    unwrap(http.delete<ApiResponse<PackagingProfile>>(`/products/${id}/packaging-override`, {
      data,
      params: { expectedVersionNo: data?.expectedVersionNo, reason: data?.changeReason },
    })),
  packagingHistory: (id: number) => unwrap(http.get<ApiResponse<PackagingHistory[]>>(`/products/${id}/packaging-history`)),
  productPackagingSummaries: () => unwrap(http.get<ApiResponse<ProductPackagingSummary[]>>('/products/packaging-summaries')),
  packagingTemplates: () => unwrap(http.get<ApiResponse<PackagingTemplate[]>>('/packaging-templates')),
  packagingTemplate: (id: number) => unwrap(http.get<ApiResponse<{ template: PackagingTemplate; profile: PackagingProfile }>>(`/packaging-templates/${id}`))
    .then(({ template, profile }) => ({
      ...template,
      baseUnit: profile.baseUnit,
      minimumSalesUnit: profile.minimumSalesUnit,
      minimumOutboundUnit: profile.minimumOutboundUnit,
      defaultPurchaseUnit: profile.defaultPurchaseUnit,
      defaultSalesUnit: profile.defaultSalesUnit,
      roundingPolicy: profile.roundingPolicy,
      contentHash: profile.contentHash,
      rules: profile.rules,
    })),
  packagingTemplateHistory: (id: number) => unwrap(http.get<ApiResponse<PackagingTemplateRevision[]>>(`/packaging-templates/${id}/history`)),
  packagingTemplateRevision: (id: number, revisionId: number) =>
    unwrap(http.get<ApiResponse<PackagingProfile>>(`/packaging-templates/${id}/revisions/${revisionId}`)),
  createPackagingTemplate: (data: PackagingTemplateSave) =>
    unwrap(http.post<ApiResponse<PackagingTemplate>>('/packaging-templates', packagingTemplatePayload(data))),
  publishPackagingTemplate: (id: number, data: PackagingTemplateSave) =>
    unwrap(http.post<ApiResponse<PackagingTemplate>>(`/packaging-templates/${id}/publish`, packagingTemplatePublishPayload(data))),
  updatePackagingTemplateMetadata: (id: number, data: { name: string; description: string; expectedLockVersion: number }) =>
    unwrap(http.put<ApiResponse<PackagingTemplate>>(`/packaging-templates/${id}/metadata`, data)),
  updatePackagingTemplateStatus: (id: number, data: { status: string; expectedLockVersion: number; changeReason: string }) =>
    unwrap(http.put<ApiResponse<PackagingTemplate>>(`/packaging-templates/${id}/status`, data)),
  seriesPackagingBindings: () => unwrap(http.get<ApiResponse<SeriesPackagingBinding[]>>('/product-series/packaging-bindings'))
    .then((rows) => rows.map(normalizeSeriesPackagingBinding)),
  seriesPackagingBinding: (seriesId: number) =>
    unwrap(http.get<ApiResponse<SeriesPackagingBinding>>(`/product-series/${seriesId}/packaging-binding`)).then(normalizeSeriesPackagingBinding),
  saveSeriesPackagingBinding: (seriesId: number, data: { templateId: number; expectedBindingVersionNo: number; changeReason: string }) =>
    unwrap(http.put<ApiResponse<SeriesPackagingBinding>>(`/product-series/${seriesId}/packaging-binding`, {
      templateId: data.templateId,
      expectedLockVersion: data.expectedBindingVersionNo,
      changeReason: data.changeReason,
    })).then(normalizeSeriesPackagingBinding),
  packagingPlan: (id: number, data: PackagingPlanRequest) =>
    unwrap(http.post<ApiResponse<PackagingPlan>>(`/products/${id}/packaging-plan`, data)),
  batchPackagingProfile: (batchId: number) =>
    unwrap(http.get<ApiResponse<PackagingProfile>>(`/inventory/batches/${batchId}/packaging-profile`)),
  batchPackagingPlan: (batchId: number, data: PackagingPlanRequest) =>
    unwrap(http.post<ApiResponse<PackagingPlan>>(`/inventory/batches/${batchId}/packaging-plan`, data)),
  productPurchaseOptions: (id: number, params?: { supplierId?: number; priceUnit?: string; quantity?: number; businessDate?: string }) =>
    unwrap(http.get<ApiResponse<ProductPurchaseOptions>>(`/products/${id}/purchase-options`, { params })),
  productUnitOptions: (id: number, params?: ProductUnitOptionsQuery) =>
    unwrap(http.get<ApiResponse<ProductPurchaseOptions>>(`/products/${id}/unit-options`, { params })),
  priceBenchmarks: (benchmarkCode?: string) =>
    unwrap(http.get<ApiResponse<PriceBenchmark[]>>('/price-center/benchmarks', { params: { benchmarkCode } })),
  createPriceBenchmark: (data: unknown) =>
    unwrap(http.post<ApiResponse<PriceBenchmark>>('/price-center/benchmarks', data)),
  priceRules: (params?: { businessType?: 'SALES' | 'PURCHASE'; productId?: number; partnerId?: number }) =>
    unwrap(http.get<ApiResponse<PriceRule[]>>('/price-center/rules', { params })),
  createPriceRule: (data: unknown) =>
    unwrap(http.post<ApiResponse<PriceRule>>('/price-center/rules', data)),
  previewPrice: (data: { businessType: 'SALES' | 'PURCHASE'; partnerId?: number; productId: number;
    quantity: number; priceUnit: string; businessDate: string }) =>
    unwrap(http.post<ApiResponse<PricePreview>>('/price-center/preview', data)),
  productCategories: () => unwrap(http.get<ApiResponse<ProductCategory[]>>('/product-categories')),
  createProductCategory: (data: Partial<ProductCategory>) => unwrap(http.post<ApiResponse<{ id: number }>>('/product-categories', data)),
  updateProductCategory: (id: number, data: Partial<ProductCategory>) => unwrap(http.put<ApiResponse<{ id: number }>>(`/product-categories/${id}`, data)),
  productStandards: () => unwrap(http.get<ApiResponse<ProductStandard[]>>('/product-standards')),
  createProductStandard: (data: Partial<ProductStandard>) => unwrap(http.post<ApiResponse<{ id: number }>>('/product-standards', data)),
  updateProductStandard: (id: number, data: Partial<ProductStandard>) => unwrap(http.put<ApiResponse<{ id: number }>>(`/product-standards/${id}`, data)),
  productTempers: () => unwrap(http.get<ApiResponse<ProductTemper[]>>('/product-tempers')),
  createProductTemper: (data: Partial<ProductTemper>) => unwrap(http.post<ApiResponse<{ id: number }>>('/product-tempers', data)),
  updateProductTemper: (id: number, data: Partial<ProductTemper>) => unwrap(http.put<ApiResponse<{ id: number }>>(`/product-tempers/${id}`, data)),
  customFields: () => unwrap(http.get<ApiResponse<CustomField[]>>('/custom-fields')),
  customFieldUsages: (id: number) => unwrap(http.get<ApiResponse<CustomFieldUsage[]>>(`/custom-fields/${id}/usages`)),
  createCustomField: (data: Partial<CustomField>) => unwrap(http.post<ApiResponse<{ id: number }>>('/custom-fields', data)),
  updateCustomField: (id: number, data: Partial<CustomField>) => unwrap(http.put<ApiResponse<{ id: number }>>(`/custom-fields/${id}`, data)),
  seriesFields: (seriesId: number) => unwrap(http.get<ApiResponse<SeriesField[]>>(`/product-series/${seriesId}/fields`)),
  createSeriesField: (data: Partial<SeriesField>) => unwrap(http.post<ApiResponse<{ id: number }>>('/product-series-fields', data)),
  updateSeriesField: (id: number, data: Partial<SeriesField>) => unwrap(http.put<ApiResponse<{ id: number }>>(`/product-series-fields/${id}`, data)),
  specPresets: (seriesId?: number) => unwrap(http.get<ApiResponse<SpecPreset[]>>('/product-spec-presets', { params: { seriesId } })),
  createSpecPreset: (data: Partial<SpecPreset>) => unwrap(http.post<ApiResponse<{ id: number }>>('/product-spec-presets', data)),
  updateSpecPreset: (id: number, data: Partial<SpecPreset>) => unwrap(http.put<ApiResponse<{ id: number }>>(`/product-spec-presets/${id}`, data)),
  productSeries: () => unwrap(http.get<ApiResponse<ProductSeries[]>>('/product-series')),
  createProductSeries: (data: Partial<ProductSeries>) => unwrap(http.post<ApiResponse<{ id: number }>>('/product-series', data)),
  updateProductSeries: (id: number, data: Partial<ProductSeries>) => unwrap(http.put<ApiResponse<{ id: number }>>(`/product-series/${id}`, data)),
  productReferenceValues: (params?: { referenceType?: ProductReferenceType; keyword?: string; status?: string }) =>
    unwrap(http.get<ApiResponse<ProductReferenceValue[]>>('/product-reference-values', { params })),
  createProductReferenceValue: (data: ProductReferenceValueSave) =>
    unwrap(http.post<ApiResponse<ProductReferenceValue>>('/product-reference-values', data)),
  updateProductReferenceValue: (id: number, data: ProductReferenceValueSave) =>
    unwrap(http.put<ApiResponse<ProductReferenceValue>>(`/product-reference-values/${id}`, data)),
  resolveProductReferenceValue: (data: { referenceType: ProductReferenceType; sourceValue: string; allowUnresolved: boolean }) =>
    unwrap(http.post<ApiResponse<ProductReferenceResolution>>('/product-reference-values/resolve', data)),
  productSeriesAllowedUnits: (seriesId: number, purpose?: ProductUnitPurpose) =>
    unwrap(http.get<ApiResponse<ProductAllowedUnit[]>>(`/product-series/${seriesId}/allowed-units`, { params: { purpose } })),
  saveProductSeriesAllowedUnit: (seriesId: number, data: ProductAllowedUnitSave) =>
    unwrap(http.post<ApiResponse<ProductAllowedUnit>>(`/product-series/${seriesId}/allowed-units`, data)),
  deleteProductSeriesAllowedUnit: (seriesId: number, allowedUnitId: number) =>
    unwrap(http.delete<ApiResponse<void>>(`/product-series/${seriesId}/allowed-units/${allowedUnitId}`)),
  unitCategories: () => unwrap(http.get<ApiResponse<UnitCategory[]>>('/unit-categories')),
  createUnitCategory: (data: Partial<UnitCategory>) => unwrap(http.post<ApiResponse<{ id: number }>>('/unit-categories', data)),
  updateUnitCategory: (id: number, data: Partial<UnitCategory>) => unwrap(http.put<ApiResponse<{ id: number }>>(`/unit-categories/${id}`, data)),
  units: () => unwrap(http.get<ApiResponse<Unit[]>>('/units')),
  createUnit: (data: Partial<Unit>) => unwrap(http.post<ApiResponse<{ id: number }>>('/units', data)),
  updateUnit: (id: number, data: Partial<Unit>) => unwrap(http.put<ApiResponse<{ id: number }>>(`/units/${id}`, data)),
  warehouses: () => unwrap(http.get<ApiResponse<Warehouse[]>>('/warehouses')),
  createWarehouse: (data: Partial<Warehouse>) => unwrap(http.post<ApiResponse<{ id: number }>>('/warehouses', data)),
  updateWarehouse: (id: number, data: Partial<Warehouse>) => unwrap(http.put<ApiResponse<{ id: number }>>(`/warehouses/${id}`, data)),
  inventoryBatches: (params?: { productId?: number; warehouseId?: number; availableOnly?: boolean }) =>
    unwrap(http.get<ApiResponse<InventoryBatch[]>>('/inventory/batches', { params })),
  inventoryPackageInstances: (batchId: number) =>
    unwrap(http.get<ApiResponse<InventoryPackageInstance[]>>(`/inventory/batches/${batchId}/package-instances`)),
  inventoryCostPolicy: () => unwrap(http.get<ApiResponse<InventoryCostPolicy>>('/inventory/cost-policy')),
  inventoryOpeningCostAlignments: () => unwrap(http.get<ApiResponse<InventoryOpeningCostAlignment[]>>('/inventory/cost-opening-alignments')),
  inventoryMovements: (params?: { productId?: number; warehouseId?: number; batchId?: number }) =>
    unwrap(http.get<ApiResponse<InventoryMovement[]>>('/inventory/movements', { params })),
  inventoryReconciliation: (params?: { productId?: number; warehouseId?: number; mismatchesOnly?: boolean }) =>
    unwrap(http.get<ApiResponse<InventoryReconciliationRow[]>>('/inventory/reconciliation', { params })),
  inventoryAtp: (params?: { productId?: number; warehouseId?: number; asOfDate?: string }) =>
    unwrap(http.get<ApiResponse<AtpSummary[]>>('/inventory/atp', { params })),
  inventoryAtpTimeline: (params: { productId: number; warehouseId: number; fromDate?: string; toDate?: string }) =>
    unwrap(http.get<ApiResponse<AtpTimelineRow[]>>('/inventory/atp/timeline', { params })),
  inventoryTransfers: (status?: string) =>
    unwrap(http.get<ApiResponse<InventoryTransferRow[]>>('/inventory/transfers', { params: { status } })),
  inventoryTransfer: (id: number) => unwrap(http.get<ApiResponse<InventoryTransferDetail>>(`/inventory/transfers/${id}`)),
  createInventoryTransfer: (data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>('/inventory/transfers', data)),
  updateInventoryTransfer: (id: number, data: unknown) => unwrap(http.put<ApiResponse<{ id: number }>>(`/inventory/transfers/${id}`, data)),
  dispatchInventoryTransfer: (id: number) => unwrap(http.post<ApiResponse<{ id: number }>>(`/inventory/transfers/${id}/dispatch`)),
  receiveInventoryTransfer: (id: number) => unwrap(http.post<ApiResponse<{ id: number }>>(`/inventory/transfers/${id}/receive`)),
  cancelInventoryTransfer: (id: number, reason: string) => unwrap(http.post<ApiResponse<{ id: number }>>(`/inventory/transfers/${id}/cancel`, { reason })),
  reverseInventoryTransfer: (id: number, reason: string) => unwrap(http.post<ApiResponse<{ id: number }>>(`/inventory/transfers/${id}/reverse`, { reason })),
  inventoryStocktakes: (status?: string) =>
    unwrap(http.get<ApiResponse<InventoryStocktakeRow[]>>('/inventory/stocktakes', { params: { status } })),
  inventoryStocktake: (id: number) => unwrap(http.get<ApiResponse<InventoryStocktakeDetail>>(`/inventory/stocktakes/${id}`)),
  createInventoryStocktake: (data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>('/inventory/stocktakes', data)),
  updateInventoryStocktake: (id: number, data: unknown) => unwrap(http.put<ApiResponse<{ id: number }>>(`/inventory/stocktakes/${id}`, data)),
  confirmInventoryStocktake: (id: number, authorization?: { reason?: string; approvalId?: number }) => unwrap(http.post<ApiResponse<{ id: number }>>(`/inventory/stocktakes/${id}/confirm`, authorization)),
  cancelInventoryStocktake: (id: number, reason: string) => unwrap(http.post<ApiResponse<{ id: number }>>(`/inventory/stocktakes/${id}/cancel`, { reason })),
  reverseInventoryStocktake: (id: number, reason: string, approvalId?: number) => unwrap(http.post<ApiResponse<{ id: number }>>(`/inventory/stocktakes/${id}/reverse`, { reason, approvalId })),
  purchaseReturns: (params?: { status?: string; supplierId?: number }) =>
    unwrap(http.get<ApiResponse<PurchaseReturnRow[]>>('/inventory/purchase-returns', { params })),
  purchaseReturn: (id: number) => unwrap(http.get<ApiResponse<PurchaseReturnDetail>>(`/inventory/purchase-returns/${id}`)),
  createPurchaseReturn: (data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>('/inventory/purchase-returns', data)),
  updatePurchaseReturn: (id: number, data: unknown) => unwrap(http.put<ApiResponse<{ id: number }>>(`/inventory/purchase-returns/${id}`, data)),
  confirmPurchaseReturn: (id: number) => unwrap(http.post<ApiResponse<{ id: number }>>(`/inventory/purchase-returns/${id}/confirm`)),
  cancelPurchaseReturn: (id: number, reason: string) => unwrap(http.post<ApiResponse<{ id: number }>>(`/inventory/purchase-returns/${id}/cancel`, { reason })),
  reversePurchaseReturn: (id: number, reason: string) => unwrap(http.post<ApiResponse<{ id: number }>>(`/inventory/purchase-returns/${id}/reverse`, { reason })),
  inventoryCostAdjustments: (status?: string) =>
    unwrap(http.get<ApiResponse<InventoryCostAdjustmentRow[]>>('/inventory/cost-adjustments', { params: { status } })),
  inventoryCostAdjustment: (id: number) => unwrap(http.get<ApiResponse<InventoryCostAdjustmentDetail>>(`/inventory/cost-adjustments/${id}`)),
  createInventoryCostAdjustment: (data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>('/inventory/cost-adjustments', data)),
  updateInventoryCostAdjustment: (id: number, data: unknown) => unwrap(http.put<ApiResponse<{ id: number }>>(`/inventory/cost-adjustments/${id}`, data)),
  confirmInventoryCostAdjustment: (id: number, authorization?: { reason?: string; approvalId?: number }) => unwrap(http.post<ApiResponse<{ id: number }>>(`/inventory/cost-adjustments/${id}/confirm`, authorization)),
  cancelInventoryCostAdjustment: (id: number, reason: string) => unwrap(http.post<ApiResponse<{ id: number }>>(`/inventory/cost-adjustments/${id}/cancel`, { reason })),
  reverseInventoryCostAdjustment: (id: number, reason: string, approvalId?: number) => unwrap(http.post<ApiResponse<{ id: number }>>(`/inventory/cost-adjustments/${id}/reverse`, { reason, approvalId })),
  landedCostOptions: () => unwrap(http.get<ApiResponse<LandedCostOptions>>('/inventory/landed-cost-allocations/options')),
  landedCostAllocations: (status?: string) => unwrap(http.get<ApiResponse<LandedCostAllocationRow[]>>('/inventory/landed-cost-allocations', { params: { status } })),
  landedCostAllocation: (id: number) => unwrap(http.get<ApiResponse<LandedCostAllocationDetail>>(`/inventory/landed-cost-allocations/${id}`)),
  createLandedCostAllocation: (data: LandedCostAllocationCreate) => unwrap(http.post<ApiResponse<{ id: number }>>('/inventory/landed-cost-allocations', data)),
  updateLandedCostAllocation: (id: number, data: LandedCostAllocationCreate) => unwrap(http.put<ApiResponse<{ id: number }>>(`/inventory/landed-cost-allocations/${id}`, data)),
  confirmLandedCostAllocation: (id: number) => unwrap(http.post<ApiResponse<{ id: number }>>(`/inventory/landed-cost-allocations/${id}/confirm`)),
  cancelLandedCostAllocation: (id: number, reason: string) => unwrap(http.post<ApiResponse<{ id: number }>>(`/inventory/landed-cost-allocations/${id}/cancel`, { reason })),
  reverseLandedCostAllocation: (id: number, data: { reason: string; businessDate?: string }) => unwrap(http.post<ApiResponse<{ id: number }>>(`/inventory/landed-cost-allocations/${id}/reverse`, data)),
  inbounds: (params?: { supplierId?: number }) => unwrap(http.get<ApiResponse<InboundRow[]>>('/inbounds', { params })),
  inboundDetail: (id: number) => unwrap(http.get<ApiResponse<InboundDetail>>(`/inbounds/${id}`)),
  createInbound: (data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>('/inbounds', data)),
  updateInbound: (id: number, data: unknown) => unwrap(http.put<ApiResponse<{ id: number }>>(`/inbounds/${id}`, data)),
  submitInbound: (id: number, operatorName: string) => unwrap(http.post<ApiResponse<{ id: number }>>(`/inbounds/${id}/submit`, { operatorName })),
  withdrawInbound: (id: number, operatorName: string) => unwrap(http.post<ApiResponse<{ id: number }>>(`/inbounds/${id}/withdraw`, { operatorName })),
  inboundAttachments: (id: number) => unwrap(http.get<ApiResponse<InboundAttachment[]>>(`/inbounds/${id}/attachments`)),
  uploadInboundAttachment: (id: number, file: File) => {
    const data = new FormData()
    data.append('file', file)
    return unwrap(http.post<ApiResponse<InboundAttachment>>(`/inbounds/${id}/attachments`, data, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 30000,
    }))
  },
  inboundAttachmentUrl: (id: number, attachmentId: number) => `/api/inbounds/${id}/attachments/${attachmentId}/content`,
  auditInbound: (id: number, approved: boolean, reason: string, operatorName: string) => unwrap(http.post<ApiResponse<{ id: number }>>(`/inbounds/${id}/audit`, { approved, reason, operatorName })),
  reverseInbound: (id: number, data: { reason: string; businessDate?: string }) => unwrap(http.post<ApiResponse<{ id: number }>>(`/inbounds/${id}/reverse`, data)),
  inboundAuditLogs: (id: number) => unwrap(http.get<ApiResponse<InboundAuditLog[]>>(`/inbounds/${id}/audit-logs`)),
  inboundAuditLogDetail: (id: number, auditLogId: number) => unwrap(http.get<ApiResponse<InboundAuditLogDetail>>(`/inbounds/${id}/audit-logs/${auditLogId}`)),
  inboundReviewSnapshotAttachmentUrl: (id: number, snapshotId: number, attachmentId: number) =>
    `/api/inbounds/${id}/review-snapshots/${snapshotId}/attachments/${attachmentId}/content`,
  evidenceAttachments: (documentType: EvidenceDocumentType, documentId: number) =>
    unwrap(http.get<ApiResponse<EvidenceAttachment[]>>(`/evidence/${documentType}/${documentId}/attachments`)),
  uploadEvidenceAttachment: (documentType: EvidenceDocumentType, documentId: number, file: File,
    evidenceType: EvidenceAttachment['evidenceType'], sourceChannel: EvidenceAttachment['sourceChannel'], note = '') => {
    const data = new FormData()
    data.append('file', file)
    data.append('evidenceType', evidenceType)
    data.append('sourceChannel', sourceChannel)
    data.append('note', note)
    return unwrap(http.post<ApiResponse<EvidenceAttachment>>(`/evidence/${documentType}/${documentId}/attachments`, data, {
      headers: { 'Content-Type': 'multipart/form-data' }, timeout: 60000,
    }))
  },
  evidenceAttachmentUrl: (documentType: EvidenceDocumentType, documentId: number, attachmentId: number) =>
    `/api/evidence/${documentType}/${documentId}/attachments/${attachmentId}/content`,
  evidenceAttachmentPreviewUrl: (documentType: EvidenceDocumentType, documentId: number, attachmentId: number) =>
    `/api/evidence/${documentType}/${documentId}/attachments/${attachmentId}/content?inline=true`,
  deleteEvidenceAttachment: (documentType: EvidenceDocumentType, documentId: number, attachmentId: number) =>
    unwrap(http.delete<ApiResponse<{ id: number }>>(`/evidence/${documentType}/${documentId}/attachments/${attachmentId}`)),
  voidEvidenceAttachment: (documentType: EvidenceDocumentType, documentId: number, attachmentId: number, reason: string) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/evidence/${documentType}/${documentId}/attachments/${attachmentId}/void`, { reason })),
  evidenceOutputRecords: (documentType: EvidenceDocumentType, documentId: number) =>
    unwrap(http.get<ApiResponse<EvidenceOutputRecord[]>>(`/evidence/${documentType}/${documentId}/outputs`)),
  recordEvidenceOutput: (documentType: EvidenceDocumentType, documentId: number, data: {
    outputType: 'PRINT_OR_PDF'; templateVersion: string; outputFileName: string; snapshotHash: string
  }) => unwrap(http.post<ApiResponse<EvidenceOutputRecord>>(`/evidence/${documentType}/${documentId}/outputs`, data)),
  purchaseOrders: () => unwrap(http.get<ApiResponse<PurchaseOrderRow[]>>('/purchase-orders')),
  purchaseOrderDetail: (id: number) => unwrap(http.get<ApiResponse<PurchaseOrderDetail>>(`/purchase-orders/${id}`)),
  purchaseOrderRevisions: (id: number) => unwrap(http.get<ApiResponse<BusinessOrderRevision[]>>(`/purchase-orders/${id}/revisions`)),
  createPurchaseOrder: (data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>('/purchase-orders', data)),
  updatePurchaseOrder: (id: number, data: unknown) => unwrap(http.put<ApiResponse<{ id: number }>>(`/purchase-orders/${id}`, data)),
  confirmPurchaseOrder: (id: number) => unwrap(http.post<ApiResponse<{ id: number }>>(`/purchase-orders/${id}/confirm`)),
  cancelPurchaseOrder: (id: number, reason: string) => unwrap(http.post<ApiResponse<{ id: number }>>(`/purchase-orders/${id}/cancel`, { reason })),
  amendPurchaseOrder: (id: number, reason: string, order: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>(`/purchase-orders/${id}/amendments`, { reason, order })),
  purchaseAmendmentPackagingPlan: (id: number, itemId: number, data: unknown) => unwrap(http.post<ApiResponse<PackagingPlan>>(`/purchase-orders/${id}/amendments/items/${itemId}/packaging-plan`, data)),
  salesOrders: () => unwrap(http.get<ApiResponse<SalesOrderRow[]>>('/sales-orders')),
  salesOrderDetail: (id: number) => unwrap(http.get<ApiResponse<SalesOrderDetail>>(`/sales-orders/${id}`)),
  salesOrderRevisions: (id: number) => unwrap(http.get<ApiResponse<BusinessOrderRevision[]>>(`/sales-orders/${id}/revisions`)),
  createSalesOrder: (data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>('/sales-orders', data)),
  updateSalesOrder: (id: number, data: unknown) => unwrap(http.put<ApiResponse<{ id: number }>>(`/sales-orders/${id}`, data)),
  salesOrderRisk: (id: number) => unwrap(http.get<ApiResponse<SalesRiskEvaluation>>(`/sales-orders/${id}/risk`)),
  salesOrderRiskHistory: (id: number) => unwrap(http.get<ApiResponse<SalesRiskSnapshot[]>>(`/sales-orders/${id}/risk-history`)),
  salesOrderAtp: (id: number) => unwrap(http.get<ApiResponse<AtpOrderEvaluation>>(`/sales-orders/${id}/atp`)),
  salesOrderAtpHistory: (id: number) => unwrap(http.get<ApiResponse<AtpSnapshot[]>>(`/sales-orders/${id}/atp-history`)),
  confirmSalesOrder: (id: number, reason?: string, approvalId?: number) => unwrap(http.post<ApiResponse<{ id: number }>>(
    `/sales-orders/${id}/confirm`, reason || approvalId ? { reason, approvalId } : undefined)),
  cancelSalesOrder: (id: number, reason: string) => unwrap(http.post<ApiResponse<{ id: number }>>(`/sales-orders/${id}/cancel`, { reason })),
  amendSalesOrder: (id: number, reason: string, order: unknown, riskOverrideReason?: string) => unwrap(http.post<ApiResponse<{ id: number }>>(`/sales-orders/${id}/amendments`, { reason, riskOverrideReason, order })),
  salesAmendmentPackagingPlan: (id: number, itemId: number, data: unknown) => unwrap(http.post<ApiResponse<PackagingPlan>>(`/sales-orders/${id}/amendments/items/${itemId}/packaging-plan`, data)),
  salesOrderPrepayments: (id: number) => unwrap(http.get<ApiResponse<SalesOrderPrepaymentSummary>>(`/sales-orders/${id}/prepayments`)),
  receiveSalesOrderPrepayment: (id: number, data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>(`/sales-orders/${id}/prepayments/receive`, data)),
  reserveSalesOrderPrepayment: (id: number, data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>(`/sales-orders/${id}/prepayments/reserve`, data)),
  releaseSalesOrderPrepayment: (id: number, allocationId: number) => unwrap(http.post<ApiResponse<{ id: number }>>(`/sales-orders/${id}/prepayments/${allocationId}/release`)),
  directTrades: () => unwrap(http.get<ApiResponse<DirectTradeRow[]>>('/direct-trades')),
  directTradeDetail: (id: number) => unwrap(http.get<ApiResponse<DirectTradeDetail>>(`/direct-trades/${id}`)),
  createDirectTrade: (data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>('/direct-trades', data)),
  confirmDirectTrade: (id: number, data: { riskOverrideReason?: string; riskApprovalId?: number } = {}) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/direct-trades/${id}/confirm`, data)),
  cancelDirectTrade: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/direct-trades/${id}/cancel`, { reason })),
  dispatchDirectTrade: (id: number, data: unknown) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/direct-trades/${id}/deliveries`, data)),
  acceptDirectTradeDelivery: (id: number, deliveryId: number, data: unknown) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/direct-trades/${id}/deliveries/${deliveryId}/accept`, data)),
  createDirectTradeReturn: (id: number, data: unknown) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/direct-trades/${id}/returns`, data)),
  reverseDirectTradeReturn: (id: number, returnId: number, reason: string) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/direct-trades/${id}/returns/${returnId}/reverse`, { reason })),
  outbounds: (params?: { customerId?: number; salesOrderId?: number }) => unwrap(http.get<ApiResponse<OutboundRow[]>>('/outbounds', { params })),
  outboundDetail: (id: number) => unwrap(http.get<ApiResponse<OutboundDetail>>(`/outbounds/${id}`)),
  createOutbound: (data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>('/outbounds', data)),
  updateOutbound: (id: number, data: unknown) => unwrap(http.put<ApiResponse<{ id: number }>>(`/outbounds/${id}`, data)),
  cancelOutbound: (id: number, reason: string) => unwrap(http.post<ApiResponse<{ id: number }>>(`/outbounds/${id}/cancel`, { reason })),
  completeOutboundInformation: (id: number, memo: string) => unwrap(http.post<ApiResponse<{ id: number }>>(`/outbounds/${id}/complete-information`, { memo })),
  outboundRisk: (id: number) => unwrap(http.get<ApiResponse<SalesRiskEvaluation>>(`/outbounds/${id}/risk`)),
  outboundRiskHistory: (id: number) => unwrap(http.get<ApiResponse<SalesRiskSnapshot[]>>(`/outbounds/${id}/risk-history`)),
  confirmOutbound: (id: number, reason?: string, approvalId?: number) => unwrap(http.post<ApiResponse<{ id: number }>>(
    `/outbounds/${id}/confirm`, reason || approvalId ? { reason, approvalId } : undefined)),
  settleOutbound: (id: number, data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>(`/outbounds/${id}/settle`, data)),
  reverseOutbound: (id: number, data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>(`/outbounds/${id}/reverse`, data)),
  salesReturns: (params?: { outboundId?: number }) => unwrap(http.get<ApiResponse<SalesReturnRow[]>>('/sales-returns', { params })),
  createSalesReturn: (data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>('/sales-returns', data)),
  reverseSalesReturn: (id: number, data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>(`/sales-returns/${id}/reverse`, data)),
  payments: (params?: { customerId?: number; salesOrderId?: number; outboundId?: number }) =>
    unwrap(http.get<ApiResponse<PaymentRow[]>>('/payments', { params })),
  receivables: (params?: { customerId?: number }) => unwrap(http.get<ApiResponse<ReceivableRow[]>>('/receivables', { params })),
  receivableReport: (params?: SettlementReportParams) =>
    unwrap(http.get<ApiResponse<SettlementReportPage>>('/settlement-reports/receivables', { params })),
  createPayment: (data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>('/payments', data)),
  paymentAllocationDetail: (id: number) => unwrap(http.get<ApiResponse<PaymentAllocationDetail>>(`/payments/${id}/allocation`)),
  changePaymentAllocation: (id: number, data: PaymentAllocationChange) =>
    unwrap(http.post<ApiResponse<unknown>>(`/payments/${id}/allocation`, data)),
  reversePayment: (id: number, data: { reason: string; businessDate?: string; approvalId?: number }) => unwrap(http.post<ApiResponse<{ id: number }>>(`/payments/${id}/reverse`, data)),
  receivableAdjustments: (params?: { customerId?: number; salesOrderId?: number }) =>
    unwrap(http.get<ApiResponse<ReceivableAdjustmentRow[]>>('/receivable-adjustments', { params })),
  createReceivableAdjustment: (data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>('/receivable-adjustments', data)),
  confirmReceivableAdjustment: (id: number) => unwrap(http.post<ApiResponse<{ id: number }>>(`/receivable-adjustments/${id}/confirm`)),
  cancelReceivableAdjustment: (id: number, data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>(`/receivable-adjustments/${id}/cancel`, data)),
  reverseReceivableAdjustment: (id: number, data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>(`/receivable-adjustments/${id}/reverse`, data)),
  acceptanceBills: (params?: { customerId?: number; salesOrderId?: number }) =>
    unwrap(http.get<ApiResponse<AcceptanceBillRow[]>>('/acceptance-bills', { params })),
  createAcceptanceBill: (data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>('/acceptance-bills', data)),
  acceptanceBillEvents: (id: number) => unwrap(http.get<ApiResponse<AcceptanceEventRow[]>>(`/acceptance-bills/${id}/events`)),
  discountAcceptanceBill: (id: number, data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>(`/acceptance-bills/${id}/discount`, data)),
  matureAcceptanceBill: (id: number, data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>(`/acceptance-bills/${id}/mature`, data)),
  endorseAcceptanceBill: (id: number, data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>(`/acceptance-bills/${id}/endorse`, data)),
  bounceAcceptanceBill: (id: number, data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>(`/acceptance-bills/${id}/bounce`, data)),
  supplierPayments: (params?: { supplierId?: number; purchaseOrderId?: number; inboundId?: number }) =>
    unwrap(http.get<ApiResponse<SupplierPaymentRow[]>>('/supplier-payments', { params })),
  createSupplierPayment: (data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>('/supplier-payments', data)),
  supplierPaymentAllocationDetail: (id: number) => unwrap(http.get<ApiResponse<PaymentAllocationDetail>>(`/supplier-payments/${id}/allocation`)),
  changeSupplierPaymentAllocation: (id: number, data: PaymentAllocationChange) =>
    unwrap(http.post<ApiResponse<unknown>>(`/supplier-payments/${id}/allocation`, data)),
  reverseSupplierPayment: (id: number, data: { reason: string; businessDate?: string; approvalId?: number }) => unwrap(http.post<ApiResponse<{ id: number }>>(`/supplier-payments/${id}/reverse`, data)),
  businessPostingRules: () => unwrap(http.get<ApiResponse<BusinessPostingRule[]>>('/business-postings/rules')),
  businessPostingBatches: (params?: { sourceType?: string; status?: string }) =>
    unwrap(http.get<ApiResponse<BusinessPostingBatch[]>>('/business-postings/batches', { params })),
  businessPostingDetail: (id: number) => unwrap(http.get<ApiResponse<BusinessPostingDetail>>(`/business-postings/batches/${id}`)),
  businessPostingReconciliation: () => unwrap(http.get<ApiResponse<BusinessPostingReconciliation[]>>('/business-postings/reconciliation')),
  businessOrderRevisions: (orderType: 'PURCHASE_ORDER' | 'SALES_ORDER', orderId: number) =>
    unwrap(http.get<ApiResponse<BusinessOrderRevision[]>>(`/business-postings/order-revisions/${orderType}/${orderId}`)),
  accountingDashboard: () => unwrap(http.get<ApiResponse<AccountingDashboard>>('/accounting/dashboard')),
  accountingAccounts: (includeDisabled = false) =>
    unwrap(http.get<ApiResponse<AccountingAccount[]>>('/accounting/accounts', { params: { includeDisabled } })),
  createAccountingAccount: (data: AccountingAccountSave) =>
    unwrap(http.post<ApiResponse<number>>('/accounting/accounts', data)),
  updateAccountingAccount: (id: number, data: AccountingAccountSave) =>
    unwrap(http.put<ApiResponse<number>>(`/accounting/accounts/${id}`, data)),
  accountingPeriods: (fiscalYear?: number) =>
    unwrap(http.get<ApiResponse<AccountingPeriod[]>>('/accounting/periods', { params: { fiscalYear } })),
  initializeAccountingYear: (fiscalYear: number) =>
    unwrap(http.post<ApiResponse<number>>('/accounting/periods/initialize', { fiscalYear })),
  closeAccountingPeriod: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<number>>(`/accounting/periods/${id}/close`, { reason })),
  reopenAccountingPeriod: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<number>>(`/accounting/periods/${id}/reopen`, { reason })),
  accountingRules: () => unwrap(http.get<ApiResponse<AccountingRule[]>>('/accounting/rules')),
  accountingRuleCoverage: (asOf?: string) =>
    unwrap(http.get<ApiResponse<AccountingRuleCoverageMatrix>>('/accounting/rule-coverage', { params: { asOf } })),
  createAccountingRule: (data: AccountingRuleSave) =>
    unwrap(http.post<ApiResponse<number>>('/accounting/rules', data)),
  publishAccountingRule: (id: number) =>
    unwrap(http.post<ApiResponse<number>>(`/accounting/rules/${id}/publish`)),
  accountingVouchers: (params?: { status?: string; from?: string; to?: string }) =>
    unwrap(http.get<ApiResponse<AccountingVoucher[]>>('/accounting/vouchers', { params })),
  accountingVoucher: (id: number) =>
    unwrap(http.get<ApiResponse<AccountingVoucherDetail>>(`/accounting/vouchers/${id}`)),
  accountingArchives: () => unwrap(http.get<ApiResponse<AccountingArchive[]>>('/accounting/archives')),
  createAccountingArchive: (periodId: number, finalArchive: boolean) =>
    unwrap(http.post<ApiResponse<AccountingArchive>>('/accounting/archives', { periodId, finalArchive })),
  downloadAccountingArchive: async (id: number, fallbackName: string) => {
    const response = await http.get<Blob>(`/accounting/archives/${id}/download`, { responseType: 'blob' })
    const disposition = String(response.headers['content-disposition'] || '')
    const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
    const plain = disposition.match(/filename="?([^";]+)"?/i)?.[1]
    let fileName = encoded ? decodeURIComponent(encoded) : plain || fallbackName
    fileName = fileName.replace(/\\/g, '/').split('/').pop() || fallbackName
    return { blob: response.data, fileName }
  },
  createAccountingVoucher: (data: AccountingVoucherSave) =>
    unwrap(http.post<ApiResponse<number>>('/accounting/vouchers', data)),
  updateAccountingVoucher: (id: number, data: AccountingVoucherSave) =>
    unwrap(http.put<ApiResponse<number>>(`/accounting/vouchers/${id}`, data)),
  reviewAccountingVoucher: (id: number, reason = '') =>
    unwrap(http.post<ApiResponse<number>>(`/accounting/vouchers/${id}/review`, { reason })),
  rejectAccountingVoucher: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<number>>(`/accounting/vouchers/${id}/reject`, { reason })),
  postAccountingVoucher: (id: number) =>
    unwrap(http.post<ApiResponse<number>>(`/accounting/vouchers/${id}/post`)),
  cancelAccountingVoucher: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<number>>(`/accounting/vouchers/${id}/cancel`, { reason })),
  reverseAccountingVoucher: (id: number, reason: string, voucherDate: string) =>
    unwrap(http.post<ApiResponse<number>>(`/accounting/vouchers/${id}/reverse`, { reason, voucherDate })),
  generateMissingAccountingVouchers: () =>
    unwrap(http.post<ApiResponse<{ generatedCount: number; cancelledCount: number; issueCount: number; voucherIds: number[] }>>('/accounting/vouchers/generate-missing')),
  accountingGenerationIssues: (status?: string) =>
    unwrap(http.get<ApiResponse<AccountingGenerationIssue[]>>('/accounting/generation-issues', { params: { status } })),
  retryAccountingGeneration: (id: number) =>
    unwrap(http.post<ApiResponse<number>>(`/accounting/generation-issues/${id}/retry`)),
  accountingTrialBalance: (params?: { from?: string; to?: string }) =>
    unwrap(http.get<ApiResponse<AccountingTrialBalance>>('/accounting/trial-balance', { params })),
  accountingLedger: (params: { accountId: number; from?: string; to?: string; partnerId?: number; productId?: number; warehouseId?: number }) =>
    unwrap(http.get<ApiResponse<AccountingLedgerEntry[]>>('/accounting/ledger', { params })),
  balanceSheet: (asOf?: string) =>
    unwrap(http.get<ApiResponse<FinancialReport>>('/financial/reports/balance-sheet', { params: { asOf } })),
  incomeStatement: (params?: { from?: string; to?: string }) =>
    unwrap(http.get<ApiResponse<FinancialReport>>('/financial/reports/income-statement', { params })),
  cashFlowStatement: (params?: { from?: string; to?: string }) =>
    unwrap(http.get<ApiResponse<CashFlowReport>>('/financial/reports/cash-flow', { params })),
  businessBankAccounts: (includeDisabled = false) =>
    unwrap(http.get<ApiResponse<BusinessBankAccount[]>>('/financial/bank-accounts', { params: { includeDisabled } })),
  createBusinessBankAccount: (data: BusinessBankAccountSave) =>
    unwrap(http.post<ApiResponse<number>>('/financial/bank-accounts', data)),
  updateBusinessBankAccount: (id: number, data: BusinessBankAccountSave) =>
    unwrap(http.put<ApiResponse<number>>(`/financial/bank-accounts/${id}`, data)),
  bankReconciliations: (periodId?: number) =>
    unwrap(http.get<ApiResponse<BankReconciliationSession[]>>('/financial/bank-reconciliations', { params: { periodId } })),
  createBankReconciliation: (data: { bankAccountId: number; periodId: number; openingBalance: number; statementClosingBalance: number }) =>
    unwrap(http.post<ApiResponse<number>>('/financial/bank-reconciliations', data)),
  bankReconciliation: (id: number) =>
    unwrap(http.get<ApiResponse<BankReconciliationDetail>>(`/financial/bank-reconciliations/${id}`)),
  downloadBankStatementImportTemplate: async (format: 'csv' | 'xlsx') => {
    const response = await http.get<Blob>('/financial/bank-reconciliations/import-template', {
      params: { format }, responseType: 'blob',
    })
    return { blob: response.data, fileName: `copper-bank-statement-template-v1.${format}` }
  },
  previewBankStatementImport: (id: number, file: File) => {
    const data = new FormData()
    data.append('file', file)
    return unwrap(http.post<ApiResponse<BankStatementImportPreview>>(
      `/financial/bank-reconciliations/${id}/statement-imports/preview`, data,
      { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 60000 },
    ))
  },
  bankStatementImportBatches: (id: number) =>
    unwrap(http.get<ApiResponse<BankStatementImportBatch[]>>(
      `/financial/bank-reconciliations/${id}/statement-imports`,
    )),
  bankStatementImport: (id: number, batchId: number) =>
    unwrap(http.get<ApiResponse<BankStatementImportPreview>>(
      `/financial/bank-reconciliations/${id}/statement-imports/${batchId}`,
    )),
  commitBankStatementImport: (id: number, batchId: number, applyAutoMatches: boolean) =>
    unwrap(http.post<ApiResponse<BankStatementImportResult>>(
      `/financial/bank-reconciliations/${id}/statement-imports/${batchId}/commit`,
      { applyAutoMatches },
    )),
  importBankStatementLines: (id: number, lines: Array<{ transactionDate: string; valueDate?: string; bankSerialNo?: string; direction: 'DEBIT' | 'CREDIT'; amount: number; balance: number; counterpartyName?: string; counterpartyAccount?: string; purpose?: string }>) =>
    unwrap(http.post<ApiResponse<number>>(`/financial/bank-reconciliations/${id}/statement-lines`, { lines })),
  matchBankStatement: (id: number, data: { statementLineId: number; sourceType: string; sourceId: number; matchedAmount: number; cashFlowCategory: string; reason?: string }) =>
    unwrap(http.post<ApiResponse<number>>(`/financial/bank-reconciliations/${id}/matches`, data)),
  reverseBankMatch: (matchId: number, reason: string) =>
    unwrap(http.post<ApiResponse<number>>(`/financial/bank-reconciliations/matches/${matchId}/reverse`, { reason })),
  voidBankStatementLine: (lineId: number, reason: string) =>
    unwrap(http.post<ApiResponse<number>>(`/financial/bank-reconciliations/statement-lines/${lineId}/void`, { reason })),
  createBankDifference: (id: number, data: { statementLineId: number; differenceType: string; direction: 'DEBIT' | 'CREDIT'; amount: number; cashFlowCategory: string; reason: string; createVoucher: boolean }) =>
    unwrap(http.post<ApiResponse<number>>(`/financial/bank-reconciliations/${id}/differences`, data)),
  reverseBankDifference: (differenceId: number, reason: string) =>
    unwrap(http.post<ApiResponse<number>>(`/financial/bank-reconciliations/differences/${differenceId}/reverse`, { reason })),
  createBankPendingSystem: (id: number, data: { sourceType: string; sourceId: number; cashFlowCategory: string; reason: string }) =>
    unwrap(http.post<ApiResponse<number>>(`/financial/bank-reconciliations/${id}/pending-system`, data)),
  reverseBankPendingSystem: (pendingId: number, reason: string) =>
    unwrap(http.post<ApiResponse<number>>(`/financial/bank-reconciliations/pending-system/${pendingId}/reverse`, { reason })),
  confirmBankReconciliation: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<number>>(`/financial/bank-reconciliations/${id}/confirm`, { reason })),
  reopenBankReconciliation: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<number>>(`/financial/bank-reconciliations/${id}/reopen`, { reason })),
  periodClosePreview: (periodId: number) =>
    unwrap(http.get<ApiResponse<PeriodClosePreview>>(`/financial/period-close/${periodId}/preview`)),
  generatePeriodProfitVoucher: (periodId: number) =>
    unwrap(http.post<ApiResponse<number>>(`/financial/period-close/${periodId}/profit-voucher`)),
  formalClosePeriod: (periodId: number, reason: string) =>
    unwrap(http.post<ApiResponse<PeriodCloseResult>>(`/financial/period-close/${periodId}/close`, { reason })),
  formalReopenPeriod: (periodId: number, reason: string) =>
    unwrap(http.post<ApiResponse<PeriodCloseResult>>(`/financial/period-close/${periodId}/reopen`, { reason })),
  taxInvoiceDashboard: () => unwrap(http.get<ApiResponse<TaxInvoiceDashboard>>('/tax-invoices/dashboard')),
  taxRates: (params?: { direction?: TaxInvoiceDirection; date?: string }) =>
    unwrap(http.get<ApiResponse<TaxRateRule[]>>('/tax-invoices/rates', { params })),
  taxInvoices: (params?: { direction?: TaxInvoiceDirection; status?: string; partnerId?: number; from?: string; to?: string; exceptionOnly?: boolean }) =>
    unwrap(http.get<ApiResponse<TaxInvoiceRow[]>>('/tax-invoices', { params })),
  taxInvoice: (id: number) => unwrap(http.get<ApiResponse<TaxInvoiceDetail>>(`/tax-invoices/${id}`)),
  downloadTaxInvoiceImportTemplate: async () => {
    const response = await http.get<Blob>('/tax-invoices/import-template', { responseType: 'blob' })
    return { blob: response.data, fileName: 'copper-tax-invoice-template-v1.csv' }
  },
  importTaxInvoices: (file: File) => {
    const data = new FormData()
    data.append('file', file)
    return unwrap(http.post<ApiResponse<TaxInvoiceImportResult>>('/tax-invoices/import', data, {
      headers: { 'Content-Type': 'multipart/form-data' }, timeout: 60000,
    }))
  },
  createTaxInvoice: (data: TaxInvoiceSave) => unwrap(http.post<ApiResponse<number>>('/tax-invoices', data)),
  updateTaxInvoice: (id: number, data: TaxInvoiceSave) => unwrap(http.put<ApiResponse<number>>(`/tax-invoices/${id}`, data)),
  confirmTaxInvoice: (id: number) => unwrap(http.post<ApiResponse<number>>(`/tax-invoices/${id}/confirm`)),
  voidTaxInvoice: (id: number, reason: string) => unwrap(http.post<ApiResponse<number>>(`/tax-invoices/${id}/void`, { reason })),
  verifyTaxInvoice: (id: number, data: { verificationStatus: 'VERIFIED' | 'FAILED'; verificationReference: string;
    verificationSource: TaxInvoiceVerificationRecord['verificationSource']; proofAttachmentId: number;
    checkedAt?: string; querySnapshot?: string; reason?: string }) =>
    unwrap(http.post<ApiResponse<number>>(`/tax-invoices/${id}/verify`, data)),
  confirmTaxInvoiceBooking: (id: number, data: { evidenceReference: string; reason?: string }) =>
    unwrap(http.post<ApiResponse<number>>(`/tax-invoices/${id}/booking-confirm`, data)),
  confirmTaxInvoiceUsage: (id: number, data: { usageStatus: 'DEDUCTION' | 'NON_DEDUCTION'; taxPeriod: string; evidenceReference: string; reason?: string }) =>
    unwrap(http.post<ApiResponse<number>>(`/tax-invoices/${id}/usage-confirm`, data)),
  transferOutTaxInvoice: (id: number, data: { taxPeriod: string; evidenceReference: string; reason: string }) =>
    unwrap(http.post<ApiResponse<number>>(`/tax-invoices/${id}/usage-transfer-out`, data)),
  updateRedTaxInvoiceConfirmation: (id: number, data: { confirmationStatus: 'PENDING' | 'CONFIRMED' | 'EXPIRED'; confirmationNo?: string; initiator: 'ISSUER' | 'RECIPIENT'; initiatedAt?: string; confirmedAt?: string; reason?: string }) =>
    unwrap(http.post<ApiResponse<number>>(`/tax-invoices/${id}/red-confirmation`, data)),
  taxInvoiceSourceCandidates: (params: { direction: TaxInvoiceDirection; partnerId: number; invoiceColor?: TaxInvoiceColor }) =>
    unwrap(http.get<ApiResponse<TaxInvoiceSourceCandidate[]>>('/tax-invoices/source-candidates', { params })),
  addTaxInvoiceRelation: (id: number, data: { sourceType: string; sourceId: number; amountExcludingTax: number; taxAmount: number; totalAmount: number; matchingBasis: string }) =>
    unwrap(http.post<ApiResponse<number>>(`/tax-invoices/${id}/relations`, data)),
  reverseTaxInvoiceRelation: (id: number, relationId: number, reason: string) =>
    unwrap(http.post<ApiResponse<number>>(`/tax-invoices/${id}/relations/${relationId}/reverse`, { reason })),
  taxComplianceDashboard: () =>
    unwrap(http.get<ApiResponse<TaxComplianceDashboard>>('/tax-compliance/dashboard')),
  electronicCredentials: (params?: { type?: ElectronicCredentialType; status?: string; exceptionOnly?: boolean }) =>
    unwrap(http.get<ApiResponse<ElectronicCredentialRow[]>>('/tax-compliance/credentials', { params })),
  electronicCredential: (id: number) =>
    unwrap(http.get<ApiResponse<ElectronicCredentialDetail>>(`/tax-compliance/credentials/${id}`)),
  importElectronicCredential: (file: File, declaredType: ElectronicCredentialType | 'AUTO') => {
    const data = new FormData()
    data.append('file', file)
    data.append('declaredType', declaredType)
    return unwrap(http.post<ApiResponse<ElectronicCredentialRow>>('/tax-compliance/credentials/import', data, {
      headers: { 'Content-Type': 'multipart/form-data' }, timeout: 60000,
    }))
  },
  downloadElectronicCredential: async (id: number) => {
    const response = await http.get<Blob>(`/tax-compliance/credentials/${id}/original`, { responseType: 'blob' })
    return response.data
  },
  verifyElectronicCredential: (id: number, data: {
    verificationKind: ElectronicCredentialVerification['verificationKind']
    verificationSource: ElectronicCredentialVerification['verificationSource']
    verificationResult: ElectronicCredentialVerification['verificationResult']
    verificationReference: string
    checkedAt?: string
    resultSnapshot?: string
    proof: File
  }) => {
    const form = new FormData()
    form.append('verificationKind', data.verificationKind)
    form.append('verificationSource', data.verificationSource)
    form.append('verificationResult', data.verificationResult)
    form.append('verificationReference', data.verificationReference)
    if (data.checkedAt) form.append('checkedAt', data.checkedAt)
    if (data.resultSnapshot) form.append('resultSnapshot', data.resultSnapshot)
    form.append('proof', data.proof)
    return unwrap(http.post<ApiResponse<number>>(`/tax-compliance/credentials/${id}/verifications`, form, {
      headers: { 'Content-Type': 'multipart/form-data' }, timeout: 60000,
    }))
  },
  electronicCredentialVerificationProof: async (id: number, verificationId: number) => {
    const response = await http.get<Blob>(`/tax-compliance/credentials/${id}/verifications/${verificationId}/proof`, { responseType: 'blob' })
    return response.data
  },
  electronicCredentialLinkCandidates: (id: number) =>
    unwrap(http.get<ApiResponse<ElectronicCredentialLinkCandidate[]>>(`/tax-compliance/credentials/${id}/link-candidates`)),
  linkElectronicCredential: (id: number, data: { relationType: string; documentType: string; documentId: number; note?: string }) =>
    unwrap(http.post<ApiResponse<number>>(`/tax-compliance/credentials/${id}/relations`, data)),
  reverseElectronicCredentialRelation: (id: number, relationId: number, reason: string) =>
    unwrap(http.post<ApiResponse<number>>(`/tax-compliance/credentials/${id}/relations/${relationId}/reverse`, { reason })),
  voidElectronicCredential: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<number>>(`/tax-compliance/credentials/${id}/void`, { reason })),
  taxWorkpapers: (period?: string) =>
    unwrap(http.get<ApiResponse<TaxWorkpaperRow[]>>('/tax-compliance/workpapers', { params: { period } })),
  taxWorkpaper: (id: number) =>
    unwrap(http.get<ApiResponse<TaxWorkpaperDetail>>(`/tax-compliance/workpapers/${id}`)),
  generateTaxWorkpaper: (period: string) =>
    unwrap(http.post<ApiResponse<TaxWorkpaperDetail>>('/tax-compliance/workpapers/generate', { period })),
  reviewTaxWorkpaper: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<number>>(`/tax-compliance/workpapers/${id}/review`, { reason })),
  exportTaxWorkpaper: async (id: number) => {
    const response = await http.get<Blob>(`/tax-compliance/workpapers/${id}/export`, { responseType: 'blob' })
    return response.data
  },
  payables: (params?: { supplierId?: number }) => unwrap(http.get<ApiResponse<PayableRow[]>>('/payables', { params })),
  payableReport: (params?: SettlementReportParams) =>
    unwrap(http.get<ApiResponse<SettlementReportPage>>('/settlement-reports/payables', { params })),
  partnerAccounts: (partnerId?: number) => unwrap(http.get<ApiResponse<PartnerAccountRow[]>>('/partner-accounts', { params: { partnerId } })),
  partnerAccountLogs: (partnerId?: number) => unwrap(http.get<ApiResponse<PartnerAccountLogRow[]>>('/partner-account-logs', { params: { partnerId } })),
  partnerFundAdjustments: (params?: { status?: PartnerFundAdjustmentStatus; partnerId?: number }) =>
    unwrap(http.get<ApiResponse<PartnerFundAdjustmentRow[]>>('/partner-fund-adjustments', { params })),
  partnerFundAdjustment: (id: number) =>
    unwrap(http.get<ApiResponse<PartnerFundAdjustmentDetail>>(`/partner-fund-adjustments/${id}`)),
  createPartnerFundAdjustment: (data: PartnerFundAdjustmentCreate) =>
    unwrap(http.post<ApiResponse<{ id: number }>>('/partner-fund-adjustments', data)),
  confirmPartnerFundAdjustment: (id: number, authorization?: { reason?: string; approvalId?: number }) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/partner-fund-adjustments/${id}/confirm`, authorization)),
  cancelPartnerFundAdjustment: (id: number, data: { reason: string }) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/partner-fund-adjustments/${id}/cancel`, data)),
  reversePartnerFundAdjustment: (id: number, data: { reason: string; businessDate?: string; approvalId?: number }) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/partner-fund-adjustments/${id}/reverse`, data)),
  treasuryDashboard: () => unwrap(http.get<ApiResponse<TreasuryDashboard>>('/treasury/dashboard')),
  treasuryDocuments: (params?: { status?: TreasuryDocumentStatus; documentType?: TreasuryDocumentType; partnerId?: number }) =>
    unwrap(http.get<ApiResponse<TreasuryDocumentRow[]>>('/treasury/documents', { params })),
  treasuryDocument: (id: number) =>
    unwrap(http.get<ApiResponse<TreasuryDocumentDetail>>(`/treasury/documents/${id}`)),
  createTreasuryDocument: (data: { documentType: TreasuryDocumentType; partnerId: number; businessDate: string;
    dueDate: string; basisNo: string; summary: string; offsetAccountId: number; priceIncludesTax: boolean;
    taxRate: number; amount: number; memo?: string; inputTaxTreatment: 'DEDUCTIBLE' | 'INCLUDED_IN_COST' }) =>
    unwrap(http.post<ApiResponse<{ id: number }>>('/treasury/documents', data)),
  confirmTreasuryDocument: (id: number) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/treasury/documents/${id}/confirm`)),
  cancelTreasuryDocument: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/treasury/documents/${id}/cancel`, { reason })),
  reverseTreasuryDocument: (id: number, data: { reason: string; businessDate?: string; approvalId?: number }) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/treasury/documents/${id}/reverse`, data)),
  settleTreasuryDocument: (id: number, data: { paymentRequestId?: number; amount: number; transactionDate: string;
    settlementMethod: 'CASH' | 'BANK'; businessBankAccountId?: number; cashFlowCategory: string; purpose: string }) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/treasury/documents/${id}/settlements`, data)),
  treasuryPaymentRequests: (params?: { status?: TreasuryPaymentRequestStatus; documentId?: number }) =>
    unwrap(http.get<ApiResponse<TreasuryPaymentRequestRow[]>>('/treasury/payment-requests', { params })),
  createTreasuryPaymentRequest: (data: { documentId: number; requestedAmount: number; requestDate: string;
    expectedPaymentDate: string; purpose: string }) =>
    unwrap(http.post<ApiResponse<{ id: number }>>('/treasury/payment-requests', data)),
  approveTreasuryPaymentRequest: (id: number, authorization?: { reason?: string; approvalId?: number }) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/treasury/payment-requests/${id}/approve`, authorization)),
  rejectTreasuryPaymentRequest: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/treasury/payment-requests/${id}/reject`, { reason })),
  cancelTreasuryPaymentRequest: (id: number, reason: string) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/treasury/payment-requests/${id}/cancel`, { reason })),
  createTreasuryTransfer: (data: { sourceBankAccountId: number; destinationBankAccountId: number;
    amount: number; transactionDate: string; purpose: string }) =>
    unwrap(http.post<ApiResponse<{ id: number }>>('/treasury/transfers', data)),
  reverseTreasuryFund: (id: number, data: { reason: string; businessDate?: string; approvalId?: number }) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/treasury/funds/${id}/reverse`, data)),
  treasuryJournal: (params?: { from?: string; to?: string; bankAccountId?: number; direction?: 'CREDIT' | 'DEBIT' }) =>
    unwrap(http.get<ApiResponse<TreasuryJournalRow[]>>('/treasury/journal', { params })),
  customerAdvances: () => unwrap(http.get<ApiResponse<CustomerAdvanceRow[]>>('/customer-advances')),
  customerAdvanceRefunds: (customerId?: number) => unwrap(http.get<ApiResponse<CustomerAdvanceRefundRow[]>>('/customer-advance-refunds', { params: { customerId } })),
  refundCustomerAdvance: (data: unknown) => unwrap(http.post<ApiResponse<{ id: number }>>('/customer-advance-refunds', data)),
  reverseCustomerAdvanceRefund: (id: number, data: { reason: string; businessDate?: string; approvalId?: number }) =>
    unwrap(http.post<ApiResponse<{ id: number }>>(`/customer-advance-refunds/${id}/reverse`, data)),
}
