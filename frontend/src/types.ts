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

export type ReconLayerKind = "LISTING" | "FOLLOWED_DETAIL_LINK" | "REVEALED_BY_CLICK";

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

export type PlanAction = "NAVIGATE_VIA_DETAIL_LINK" | "CLICK_TO_REVEAL" | "FINISH";
export type PlanOutcome = "SUCCESS" | "FAILED" | "SKIPPED";

export interface PlanStep {
  stepIndex: number;
  action: PlanAction;
  reasoning: string;
  outcome: PlanOutcome;
  detailMessage: string | null;
  actionData: Record<string, unknown> | null;
  createdAt: string;
}

export type ScriptStatus = "RUNNING" | "SUCCEEDED" | "FAILED";

export type ExtractionStep =
  | { "@type": "Navigate"; url: string; description: string }
  | { "@type": "DismissConsent"; description: string }
  | { "@type": "WaitForSelector"; selector: string; timeoutMs: number; description: string }
  | { "@type": "ResolveAndNavigate"; detailLinkSelector: string; nth?: number | null; description: string }
  | { "@type": "Click"; selector: string; text?: string | null; nth?: number | null; description: string }
  | {
      "@type": "ExtractRows";
      rowSelector: string;
      fields: FieldSelector[];
      attachAs: string;
      description: string;
      limit?: number | null;
    }
  | {
      "@type": "ForEachRow";
      rowSelector: string;
      fields: FieldSelector[];
      attachAs: string;
      perRowSteps: ExtractionStep[];
      description: string;
      limit?: number | null;
    };

export interface ExtractionPlan {
  version: number;
  targetUrl: string;
  userRequest: string;
  description: string;
  output: { format: string; rootKey: string };
  steps: ExtractionStep[];
}

export interface LogEvent {
  jobId: string;
  seq: number;
  timestamp: string;
  level: "TRACE" | "DEBUG" | "INFO" | "WARN" | "ERROR";
  logger: string;
  message: string;
  throwable: string | null;
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
  extractionPlan: ExtractionPlan | null;
  hasGeneratedScript: boolean;
  scriptStatus: ScriptStatus | null;
  scriptError: string | null;
  scriptResult: unknown | null;
  createdAt: string;
  updatedAt: string;
}
