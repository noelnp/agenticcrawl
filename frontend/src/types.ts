export type JobStatus =
  | "PENDING"
  | "RUNNING"
  | "AWAITING_CONFIRMATION"
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

export interface Job {
  id: string;
  description: string;
  url: string;
  status: JobStatus;
  validation: Validation | null;
  target: Target | null;
  containerHtml: string | null;
  extractedStructure: ExtractedStructure | null;
  errorMessage: string | null;
  hasScreenshot: boolean;
  createdAt: string;
  updatedAt: string;
}
