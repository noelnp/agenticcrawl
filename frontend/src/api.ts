import type { Job, LogEvent } from "./types";

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

export function layerScreenshotUrl(id: string, layerIndex: number): string {
  return `${BASE}/jobs/${id}/layers/${layerIndex}/screenshot`;
}

export function scriptDownloadUrl(id: string): string {
  return `${BASE}/jobs/${id}/script`;
}

export async function runScript(id: string): Promise<Job> {
  const res = await fetch(`${BASE}/jobs/${id}/run-script`, { method: "POST" });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`Failed to start script run (${res.status}): ${text}`);
  }
  return res.json();
}

export function openLogStream(
  id: string,
  onEvent: (event: LogEvent) => void,
  onError?: (e: Event) => void,
): () => void {
  const source = new EventSource(`${BASE}/jobs/${id}/logs/stream`);
  source.addEventListener("log", (msg) => {
    try {
      const data: LogEvent = JSON.parse((msg as MessageEvent).data);
      onEvent(data);
    } catch {
      // ignore malformed events; the stream stays open
    }
  });
  if (onError) source.onerror = onError;
  return () => source.close();
}
