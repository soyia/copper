export type PackageLabelProductMatchStatus =
  | 'MATCHED'
  | 'MANUALLY_MATCHED'
  | 'MISMATCH'
  | 'INCOMPLETE'
  | 'AMBIGUOUS'
  | 'NOT_FOUND'

export interface PackageLabelOcrResult {
  intakeId: number
  originalFileName: string
  packageCode: string
  actualWeightKg: number
  productionDate: string
  labelProductName: string
  labelSpecification: string
  matchedProductId?: number
  matchedProductSku: string
  productMatchStatus: PackageLabelProductMatchStatus
  productMatchReason: string
  confidence: number
}
