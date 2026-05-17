import { useCallback, useEffect, useMemo, useState } from "react";
import {
  confirmJob,
  createJob,
  fetchJob,
  layerScreenshotUrl,
  runScript,
  scriptDownloadUrl,
} from "./api";
import type {
  ExtractionPlan,
  ExtractionStep,
  Job,
  JobStatus,
  Target,
} from "./types";
import "./App.css";

const TERMINAL_STATUSES = new Set<JobStatus>(["SUCCEEDED", "FAILED", "EXPIRED"]);

interface HistoryEntry {
  id: string;
  url: string;
  description: string;
  createdAt: string;
}

const HISTORY_KEY = "agenticcrawl.history.v1";
const HISTORY_LIMIT = 30;

function loadHistory(): HistoryEntry[] {
  try {
    const raw = localStorage.getItem(HISTORY_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw) as HistoryEntry[];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function saveHistory(items: HistoryEntry[]) {
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(items.slice(0, HISTORY_LIMIT)));
  } catch {
    // localStorage may be disabled in private mode; ignore
  }
}

export function App() {
  const [description, setDescription] = useState("");
  const [url, setUrl] = useState("");
  const [job, setJob] = useState<Job | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [history, setHistory] = useState<HistoryEntry[]>(() => loadHistory());
  const [zoomedScreenshot, setZoomedScreenshot] = useState<string | null>(null);

  const isTerminal = job ? TERMINAL_STATUSES.has(job.status) : false;
  const scriptRunning = job?.scriptStatus === "RUNNING";
  const shouldPoll = job ? !isTerminal || scriptRunning : false;
  const scrapingStarted =
    job?.scriptStatus === "RUNNING" ||
    job?.scriptStatus === "SUCCEEDED" ||
    job?.scriptStatus === "FAILED";

  useEffect(() => {
    if (!job?.id || !shouldPoll) return;
    const interval = setInterval(async () => {
      try {
        const fresh = await fetchJob(job.id);
        setJob(fresh);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    }, 1000);
    return () => clearInterval(interval);
  }, [job?.id, shouldPoll]);

  const persistHistoryEntry = useCallback((entry: HistoryEntry) => {
    setHistory((prev) => {
      const filtered = prev.filter((h) => h.id !== entry.id);
      const next = [entry, ...filtered].slice(0, HISTORY_LIMIT);
      saveHistory(next);
      return next;
    });
  }, []);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const created = await createJob(description, url);
      setJob(created);
      persistHistoryEntry({
        id: created.id,
        url: created.url,
        description: created.description,
        createdAt: created.createdAt,
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setSubmitting(false);
    }
  }

  async function handleRunScript() {
    if (!job) return;
    setError(null);
    try {
      const updated = await runScript(job.id);
      setJob(updated);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }

  async function handleConfirm() {
    if (!job) return;
    setConfirming(true);
    setError(null);
    try {
      const updated = await confirmJob(job.id);
      setJob(updated);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setConfirming(false);
    }
  }

  function handleNew() {
    setJob(null);
    setError(null);
    setDescription("");
    setUrl("");
  }

  async function handleOpenHistory(entry: HistoryEntry) {
    setError(null);
    setDescription(entry.description);
    setUrl(entry.url);
    try {
      const fresh = await fetchJob(entry.id);
      setJob(fresh);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }

  function handleRemoveHistory(id: string) {
    setHistory((prev) => {
      const next = prev.filter((h) => h.id !== id);
      saveHistory(next);
      return next;
    });
  }

  return (
    <div className="app">
      <Sidebar
        history={history}
        activeId={job?.id ?? null}
        onNew={handleNew}
        onOpen={handleOpenHistory}
        onRemove={handleRemoveHistory}
      />

      {!job ? (
        <HeroView
          description={description}
          url={url}
          submitting={submitting}
          error={error}
          onDescription={setDescription}
          onUrl={setUrl}
          onSubmit={handleSubmit}
        />
      ) : (
        <JobView
          job={job}
          error={error}
          confirming={confirming}
          scrapingStarted={!!scrapingStarted}
          onNew={handleNew}
          onConfirm={handleConfirm}
          onRunScript={handleRunScript}
          onZoom={setZoomedScreenshot}
        />
      )}

      {zoomedScreenshot && (
        <div
          className="zoom-modal"
          onClick={() => setZoomedScreenshot(null)}
          role="dialog"
        >
          <img src={zoomedScreenshot} alt="Enlarged capture" />
        </div>
      )}
    </div>
  );
}

/* ============================== sidebar ============================== */

function Sidebar({
  history,
  activeId,
  onNew,
  onOpen,
  onRemove,
}: {
  history: HistoryEntry[];
  activeId: string | null;
  onNew: () => void;
  onOpen: (entry: HistoryEntry) => void;
  onRemove: (id: string) => void;
}) {
  return (
    <aside className="sidebar">
      <div className="sidebar-brand">
        <span className="logo" />
        <h1>AgenticCrawl</h1>
      </div>

      <div className="sidebar-section">
        <span className="sidebar-section-label">Recent</span>
        <button className="ghost" type="button" onClick={onNew}>
          + New
        </button>
      </div>

      <div className="sidebar-list">
        {history.map((entry) => (
          <div
            key={entry.id}
            className={`history-item ${entry.id === activeId ? "active" : ""}`}
            onClick={() => onOpen(entry)}
          >
            <div className="label">
              <div className="top">{entry.description || "(no description)"}</div>
              <div className="bottom">{shortUrl(entry.url)}</div>
            </div>
            <button
              type="button"
              className="remove"
              onClick={(e) => {
                e.stopPropagation();
                onRemove(entry.id);
              }}
              title="Remove from history"
            >
              ×
            </button>
          </div>
        ))}
      </div>

      <div className="sidebar-foot">Built for fast page scraping</div>
    </aside>
  );
}

function shortUrl(url: string): string {
  try {
    const u = new URL(url);
    return u.host + (u.pathname === "/" ? "" : u.pathname);
  } catch {
    return url;
  }
}

/* ============================== hero (empty state) ============================== */

function HeroView({
  description,
  url,
  submitting,
  error,
  onDescription,
  onUrl,
  onSubmit,
}: {
  description: string;
  url: string;
  submitting: boolean;
  error: string | null;
  onDescription: (s: string) => void;
  onUrl: (s: string) => void;
  onSubmit: (e: React.FormEvent) => void;
}) {
  return (
    <div className="hero">
      <div className="hero-glow" aria-hidden />
      <div className="hero-inner">
        <h1>Scrape anything.</h1>
        <p>Describe the data, paste the link. We'll do the rest.</p>
        <ChatComposer
          description={description}
          url={url}
          submitting={submitting}
          onDescription={onDescription}
          onUrl={onUrl}
          onSubmit={onSubmit}
        />
        {error && <div className="error-banner hero-error">{error}</div>}
      </div>
    </div>
  );
}

function ChatComposer({
  description,
  url,
  submitting,
  onDescription,
  onUrl,
  onSubmit,
}: {
  description: string;
  url: string;
  submitting: boolean;
  onDescription: (s: string) => void;
  onUrl: (s: string) => void;
  onSubmit: (e: React.FormEvent) => void;
}) {
  function handleKey(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
      e.preventDefault();
      if (description && url && !submitting) {
        onSubmit(e as unknown as React.FormEvent);
      }
    }
  }

  return (
    <form className="chat-composer" onSubmit={onSubmit}>
      <textarea
        value={description}
        onChange={(e) => onDescription(e.target.value)}
        onKeyDown={handleKey}
        placeholder="e.g. Pull the title, price, and rating from this listing page"
        rows={3}
        required
        disabled={submitting}
      />
      <div className="chat-composer-row">
        <span className="chat-link-icon" aria-hidden>
          ↗
        </span>
        <input
          type="url"
          value={url}
          onChange={(e) => onUrl(e.target.value)}
          placeholder="https://example.com/listing"
          required
          disabled={submitting}
        />
        <button
          type="submit"
          className="chat-send"
          disabled={submitting || !description || !url}
          title="Start"
        >
          {submitting ? <span className="chat-send-spinner" /> : "→"}
        </button>
      </div>
    </form>
  );
}

/* ============================== job view (split layout) ============================== */

function JobView({
  job,
  error,
  confirming,
  scrapingStarted,
  onNew,
  onConfirm,
  onRunScript,
  onZoom,
}: {
  job: Job;
  error: string | null;
  confirming: boolean;
  scrapingStarted: boolean;
  onNew: () => void;
  onConfirm: () => void;
  onRunScript: () => void;
  onZoom: (url: string) => void;
}) {
  return (
    <div className="job-view">
      <MessagePanel job={job} onNew={onNew} />
      <div className="process-panel">
        {error && <div className="error-banner">{error}</div>}

        {job.status === "FAILED" && job.errorMessage && (
          <SimpleErrorCard
            title="Something went wrong"
            message={job.errorMessage}
          />
        )}

        {job.status === "EXPIRED" && (
          <SimpleErrorCard
            title="Session expired"
            message="We waited too long for your confirmation, so the browser session closed. Start a new scrape to try again."
          />
        )}

        {!scrapingStarted && (
          <AnalysisSection
            job={job}
            confirming={confirming}
            onConfirm={onConfirm}
            onZoom={onZoom}
          />
        )}

        {job.extractionPlan && !scrapingStarted && (
          <ReadySection job={job} plan={job.extractionPlan} onRun={onRunScript} />
        )}

        {scrapingStarted && <DataSection job={job} onRun={onRunScript} />}
      </div>
    </div>
  );
}

function MessagePanel({ job, onNew }: { job: Job; onNew: () => void }) {
  return (
    <aside className="msg-panel">
      <div className="msg-bubble">
        <div className="msg-bubble-label">Your request</div>
        <div className="msg-bubble-body">{job.description}</div>
        <a
          className="msg-bubble-url"
          href={job.url}
          target="_blank"
          rel="noreferrer"
        >
          {job.url}
        </a>
      </div>
      <div className="msg-status">
        <FriendlyStatusPill job={job} />
      </div>
      <button type="button" className="ghost msg-new" onClick={onNew}>
        + New scrape
      </button>
    </aside>
  );
}

function FriendlyStatusPill({ job }: { job: Job }) {
  const { tone, label } = friendlyStatus(job);
  return (
    <span className={`status-pill ${tone}`}>
      <span className="indicator" />
      {label}
    </span>
  );
}

function friendlyStatus(job: Job): { tone: string; label: string } {
  if (job.scriptStatus === "RUNNING") return { tone: "running", label: "Scraping…" };
  if (job.scriptStatus === "SUCCEEDED") return { tone: "succeeded", label: "Done" };
  if (job.scriptStatus === "FAILED") return { tone: "failed", label: "Scrape failed" };

  switch (job.status) {
    case "PENDING":
      return { tone: "running", label: "Starting up" };
    case "RUNNING_LISTING_RECON":
      return { tone: "running", label: "Looking at the page" };
    case "AWAITING_CONFIRMATION":
      return { tone: "awaiting", label: "Needs your OK" };
    case "RUNNING_PLAN":
      return { tone: "running", label: "Mapping the data" };
    case "SUCCEEDED":
      return { tone: "succeeded", label: "Ready" };
    case "FAILED":
      return { tone: "failed", label: "Failed" };
    case "EXPIRED":
      return { tone: "failed", label: "Session expired" };
    default:
      return { tone: "idle", label: job.status };
  }
}

/* ============================== analysis section ============================== */

function AnalysisSection({
  job,
  confirming,
  onConfirm,
  onZoom,
}: {
  job: Job;
  confirming: boolean;
  onConfirm: () => void;
  onZoom: (url: string) => void;
}) {
  const initialLayer = job.layers.find((l) => l.layerIndex === 0) ?? null;
  const awaiting = job.status === "AWAITING_CONFIRMATION";
  const verdict = initialLayer?.validation?.verdict ?? null;

  return (
    <div className="section accent">
      <div className="section-head">
        <div className="left">
          <h3>What we found</h3>
        </div>
      </div>

      <div className="section-body">
        {!initialLayer && (
          <p className="muted">Opening the page and taking a look…</p>
        )}

        {verdict && initialLayer?.validation && (
          <div className={`verdict-banner ${verdict}`}>
            <span className="icon">{verdictGlyph(verdict)}</span>
            <div className="body">
              <h4>{verdictHeadline(verdict)}</h4>
              <p>{initialLayer.validation.reasoning}</p>
            </div>
          </div>
        )}

        {initialLayer?.target && (
          <div>
            <SectionSubhead
              label={initialLayer.target.type === "MULTI" ? "Sample row" : "Sample value"}
            />
            <TargetTable target={initialLayer.target} />
          </div>
        )}

        {awaiting && initialLayer?.target && (
          <div className="confirm-bar">
            <p>
              {initialLayer.target.type === "MULTI"
                ? "Does this look like the data you want?"
                : "Is this the value you wanted?"}
            </p>
            <button type="button" onClick={onConfirm} disabled={confirming}>
              {confirming ? "Confirming…" : "Yes, continue"}
            </button>
          </div>
        )}

        {initialLayer?.hasScreenshot && (
          <div>
            <SectionSubhead label="Page snapshot" />
            <img
              className="screenshot-thumb"
              src={layerScreenshotUrl(job.id, 0)}
              alt="Page snapshot"
              onClick={() => onZoom(layerScreenshotUrl(job.id, 0))}
            />
            <p className="screenshot-caption">Click to enlarge.</p>
          </div>
        )}
      </div>
    </div>
  );
}

function SectionSubhead({ label }: { label: string }) {
  return (
    <div style={subheadStyle}>
      <span style={subheadLabelStyle}>{label}</span>
    </div>
  );
}

const subheadStyle: React.CSSProperties = {
  display: "flex",
  alignItems: "center",
  gap: "0.5rem",
  margin: "0 0 0.5rem",
};

const subheadLabelStyle: React.CSSProperties = {
  fontSize: "0.72rem",
  fontWeight: 600,
  letterSpacing: "0.08em",
  textTransform: "uppercase",
  color: "var(--text-3)",
};

function verdictGlyph(v: "PRESENT" | "PARTIAL" | "ABSENT"): string {
  if (v === "PRESENT") return "✓";
  if (v === "PARTIAL") return "~";
  return "✕";
}

function verdictHeadline(v: "PRESENT" | "PARTIAL" | "ABSENT"): string {
  if (v === "PRESENT") return "Found what you asked for";
  if (v === "PARTIAL") return "Found some of it";
  return "Couldn't find this on the page";
}

function TargetTable({ target }: { target: Target }) {
  return (
    <table className="kv-table">
      <tbody>
        {target.fields.map((f) => (
          <tr key={f.name}>
            <th>{prettifyFieldName(f.name)}</th>
            <td>{f.text}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function prettifyFieldName(name: string): string {
  return name
    .replace(/[_-]+/g, " ")
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .replace(/\s+/g, " ")
    .trim()
    .replace(/^./, (c) => c.toUpperCase());
}

/* ============================== ready section ============================== */

function ReadySection({
  job,
  plan,
  onRun,
}: {
  job: Job;
  plan: ExtractionPlan;
  onRun: () => void;
}) {
  const isRunning = job.scriptStatus === "RUNNING";
  const isDone = job.scriptStatus === "SUCCEEDED";
  const runLabel = isRunning
    ? "Scraping…"
    : isDone || job.scriptStatus === "FAILED"
    ? "Scrape again"
    : "Start scraping";

  const fieldNames = collectFieldNames(plan.steps);
  const rowEstimate = countRowEstimate(plan.steps);

  return (
    <div className="section">
      <div className="section-head">
        <div className="left">
          <h3>Ready to scrape</h3>
        </div>
        <div className="right">
          <a
            href={scriptDownloadUrl(job.id)}
            className="secondary"
            style={downloadStyle}
            download={`scraper-${job.id}.main.kts`}
            title="Save the underlying scraper script"
          >
            Save script
          </a>
          <button type="button" onClick={onRun} disabled={isRunning}>
            {runLabel}
          </button>
        </div>
      </div>
      <div className="section-body">
        <p className="muted">
          {summarizePlan(plan, rowEstimate)}
        </p>
        {fieldNames.length > 0 && (
          <div>
            <SectionSubhead label="What we'll collect" />
            <div className="chip-row">
              {fieldNames.map((name) => (
                <span key={name} className="chip">
                  {prettifyFieldName(name)}
                </span>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function summarizePlan(plan: ExtractionPlan, rowEstimate: number | null): string {
  const target = (() => {
    try {
      return new URL(plan.targetUrl).host;
    } catch {
      return plan.targetUrl;
    }
  })();
  if (rowEstimate != null) {
    return `We're ready to pull up to ${rowEstimate} entries from ${target}.`;
  }
  return `We're ready to pull data from ${target}.`;
}

function collectFieldNames(steps: ExtractionStep[]): string[] {
  const names: string[] = [];
  const seen = new Set<string>();
  const walk = (xs: ExtractionStep[]) => {
    for (const s of xs) {
      if (s["@type"] === "ExtractRows" || s["@type"] === "ForEachRow") {
        for (const f of s.fields) {
          if (!seen.has(f.name)) {
            seen.add(f.name);
            names.push(f.name);
          }
        }
        if (s["@type"] === "ForEachRow") walk(s.perRowSteps);
      }
    }
  };
  walk(steps);
  return names;
}

function countRowEstimate(steps: ExtractionStep[]): number | null {
  for (const s of steps) {
    if (s["@type"] === "ExtractRows" || s["@type"] === "ForEachRow") {
      return s.limit ?? null;
    }
  }
  return null;
}

const downloadStyle: React.CSSProperties = {
  display: "inline-flex",
  alignItems: "center",
  padding: "0.5rem 0.85rem",
  borderRadius: "8px",
  border: "1px solid var(--border-strong)",
  background: "transparent",
  color: "var(--text-1)",
  fontSize: "0.84rem",
};

/* ============================== data (result) section ============================== */

function DataSection({ job, onRun }: { job: Job; onRun: () => void }) {
  const previewRows = extractPreviewRows(job.scriptResult);
  const isRunning = job.scriptStatus === "RUNNING";

  return (
    <div className="section">
      <div className="section-head">
        <div className="left">
          <h3>Your data</h3>
        </div>
        <div className="right">
          {!isRunning && (
            <button
              type="button"
              className="secondary"
              style={downloadStyle}
              onClick={onRun}
            >
              {job.scriptStatus === "FAILED" ? "Try again" : "Scrape again"}
            </button>
          )}
          {job.scriptStatus === "SUCCEEDED" && job.scriptResult != null && (
            <button type="button" onClick={() => downloadJson(job)}>
              Download
            </button>
          )}
        </div>
      </div>
      <div className="section-body">
        {isRunning && (
          <p className="muted">Working on it — this can take a minute or two…</p>
        )}
        {job.scriptStatus === "FAILED" && (
          <p className="muted" style={{ color: "var(--danger)" }}>
            We hit a snag while scraping. Try running it again, or describe the
            target differently if it keeps failing.
          </p>
        )}
        {job.scriptStatus === "SUCCEEDED" && previewRows && (
          <DataPreview rows={previewRows} />
        )}
        {job.scriptStatus === "SUCCEEDED" &&
          !previewRows &&
          job.scriptResult != null && (
            <pre className="json-block soft">
              {JSON.stringify(job.scriptResult, null, 2)}
            </pre>
          )}
      </div>
    </div>
  );
}

function downloadJson(job: Job) {
  const json = JSON.stringify(job.scriptResult, null, 2);
  const blob = new Blob([json], { type: "application/json" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = `${shortFilename(job.description)}.json`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(a.href);
}

function shortFilename(text: string): string {
  return (
    text
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "")
      .slice(0, 40) || "data"
  );
}

function extractPreviewRows(result: unknown): Record<string, unknown>[] | null {
  if (Array.isArray(result)) return rowsIfPlain(result);
  if (result && typeof result === "object") {
    const obj = result as Record<string, unknown>;
    const keys = Object.keys(obj);
    if (keys.length === 1 && Array.isArray(obj[keys[0]])) {
      return rowsIfPlain(obj[keys[0]] as unknown[]);
    }
  }
  return null;
}

function rowsIfPlain(arr: unknown[]): Record<string, unknown>[] | null {
  if (arr.length === 0) return [];
  if (arr.every((x) => x && typeof x === "object" && !Array.isArray(x))) {
    return arr as Record<string, unknown>[];
  }
  return null;
}

function DataPreview({ rows }: { rows: Record<string, unknown>[] }) {
  if (rows.length === 0) {
    return <p className="muted">We didn't find any data on this page.</p>;
  }

  const columns = useMemo(() => {
    const seen = new Set<string>();
    const cols: string[] = [];
    for (const row of rows.slice(0, 50)) {
      for (const k of Object.keys(row)) {
        if (!seen.has(k)) {
          seen.add(k);
          cols.push(k);
        }
      }
    }
    return cols;
  }, [rows]);

  const preview = rows.slice(0, 25);

  return (
    <div>
      <p className="muted">
        Pulled <strong style={{ color: "var(--text-0)" }}>{rows.length}</strong>
        {rows.length === 1 ? " entry" : " entries"}
        {rows.length > preview.length ? ` — showing the first ${preview.length}` : ""}
        .
      </p>
      <div className="data-preview-wrap">
        <table className="data-preview">
          <thead>
            <tr>
              {columns.map((c) => (
                <th key={c}>{prettifyFieldName(c)}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {preview.map((row, i) => (
              <tr key={i}>
                {columns.map((c) => (
                  <td key={c}>{formatCell(row[c])}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function formatCell(v: unknown): string {
  if (v == null) return "—";
  if (typeof v === "string") return v;
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  return JSON.stringify(v);
}

/* ============================== error card ============================== */

function SimpleErrorCard({ title, message }: { title: string; message: string }) {
  return (
    <div className="section">
      <div className="section-head">
        <div className="left">
          <h3 style={{ color: "var(--danger)" }}>{title}</h3>
        </div>
      </div>
      <div className="section-body">
        <p className="muted">{message}</p>
      </div>
    </div>
  );
}

