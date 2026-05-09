export type JobStatus = "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED";

export type ValidationVerdict = "PRESENT" | "PARTIAL" | "ABSENT";

export interface Validation {
  verdict: ValidationVerdict;
  reasoning: string;
}

export interface Example {
  containerType: string;
  fields: Record<string, string>;
}

export interface Job {
  id: string;
  description: string;
  url: string;
  status: JobStatus;
  validation: Validation | null;
  example: Example | null;
  errorMessage: string | null;
  hasScreenshot: boolean;
  createdAt: string;
  updatedAt: string;
}
