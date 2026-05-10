import type { Job } from "./types";

const BASE = import.meta.env.VITE_API_BASE_URL ?? "/api";

export async function createJob(description: string, url: string): Promise<Job> {
  const res = await fetch(`${BASE}/jobs`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ description, url }),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`Failed to create job (${res.status}): ${text}`);
  }
  return res.json();
}

export async function fetchJob(id: string): Promise<Job> {
  const res = await fetch(`${BASE}/jobs/${id}`);
  if (!res.ok) throw new Error(`Failed to fetch job (${res.status})`);
  return res.json();
}

export async function confirmJob(id: string): Promise<Job> {
  const res = await fetch(`${BASE}/jobs/${id}/confirm`, { method: "POST" });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`Failed to confirm job (${res.status}): ${text}`);
  }
  return res.json();
}

export function screenshotUrl(id: string): string {
  return `${BASE}/jobs/${id}/screenshot`;
}
