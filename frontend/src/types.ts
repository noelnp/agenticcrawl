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
export type ActionType = "CLICK" | "FILL";

export interface TargetField {
  name: string;
  text: string;
}

export interface DataTarget {
  kind: "DATA";
  type: TargetType;
  fields: TargetField[];
}

export interface ActionTarget {
  kind: "ACTION";
  verb: ActionType;
  text: string;
}

export type Target = DataTarget | ActionTarget;

export interface Job {
  id: string;
  description: string;
  url: string;
  status: JobStatus;
  validation: Validation | null;
  target: Target | null;
  containerHtml: string | null;
  errorMessage: string | null;
  hasScreenshot: boolean;
  createdAt: string;
  updatedAt: string;
}
