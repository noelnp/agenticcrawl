export type JobStatus =
  | "PENDING"
  | "RUNNING_LISTING_RECON"
  | "AWAITING_CONFIRMATION"
  | "RUNNING_PLAN"
  | "SUCCEEDED"
  | "FAILED"
  | "EXPIRED";

export type ValidationVerdict = "PRESENT" | "PARTIAL" | "ABSENT";

export interface Validation {
  verdict: ValidationVerdict;
  reasoning: string;
}

export type TargetType = "SINGLE" | "MULTI";

export interface TargetField {
  name: string;
  text: string;
}

export interface Target {
  type: TargetType;
  fields: TargetField[];
}

export type ValueSource = { from: "TEXT" } | { from: "ATTRIBUTE"; name: string };

export interface FieldSelector {
  name: string;
  selector: string;
  source: ValueSource;
  nth?: number | null;
}

export interface DetailLinkSelector {
  selector: string;
  nth?: number | null;
}

export interface ExtractedStructure {
  rowSelector: string;
  fields: FieldSelector[];
  detailLink?: DetailLinkSelector | null;
}

export type ReconLayerKind = "LISTING" | "FOLLOWED_DETAIL_LINK";

export interface ReconLayer {
  layerIndex: number;
  atUrl: string;
  layerKind: ReconLayerKind;
  validation: Validation | null;
  target: Target | null;
  containerHtml: string | null;
  extractedStructure: ExtractedStructure | null;
  hasScreenshot: boolean;
  createdAt: string;
}

export type PlanAction = "NAVIGATE_VIA_DETAIL_LINK" | "FINISH";
export type PlanOutcome = "SUCCESS" | "FAILED" | "SKIPPED";

export interface PlanStep {
  stepIndex: number;
  action: PlanAction;
  reasoning: string;
  outcome: PlanOutcome;
  detailMessage: string | null;
  createdAt: string;
}

export interface Job {
  id: string;
  description: string;
  url: string;
  status: JobStatus;
  goalSatisfied: boolean | null;
  errorMessage: string | null;
  layers: ReconLayer[];
  planSteps: PlanStep[];
  createdAt: string;
  updatedAt: string;
}
