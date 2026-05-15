import { useEffect, useState } from "react";
import { confirmJob, createJob, fetchJob, screenshotUrl } from "./api";
import type { ExtractedStructure, Job, Target } from "./types";
import "./App.css";

const TERMINAL_STATUSES = new Set(["SUCCEEDED", "FAILED", "EXPIRED"]);

export function App() {
  const [description, setDescription] = useState("");
  const [url, setUrl] = useState("");
  const [job, setJob] = useState<Job | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const isTerminal = job ? TERMINAL_STATUSES.has(job.status) : false;

  useEffect(() => {
    if (!job?.id || isTerminal) return;
    const interval = setInterval(async () => {
      try {
        const fresh = await fetchJob(job.id);
        setJob(fresh);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    }, 1000);
    return () => clearInterval(interval);
  }, [job?.id, isTerminal]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    setJob(null);
    try {
      const created = await createJob(description, url);
      setJob(created);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setSubmitting(false);
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

  function handleReset() {
    setJob(null);
    setError(null);
  }

  return (
    <div className="container">
      <header>
        <h1>AgenticCrawl</h1>
        <p className="tagline">
          Describe what you want and the URL where to find it. The agent will
          look at the page and tell you whether it's there.
        </p>
      </header>

      <form onSubmit={handleSubmit} className="job-form">
        <label>
          What are you looking for?
          <textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={3}
            placeholder="e.g. a list of upcoming football matches with kickoff times"
            required
            disabled={submitting}
          />
        </label>
        <label>
          Page URL
          <input
            type="url"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            placeholder="https://example.com"
            required
            disabled={submitting}
          />
        </label>
        <div className="actions">
          <button type="submit" disabled={submitting || !description || !url}>
            {submitting ? "Submitting…" : "Submit"}
          </button>
          {job && (
            <button type="button" onClick={handleReset} className="secondary">
              New job
            </button>
          )}
        </div>
      </form>

      {error && <div className="error">{error}</div>}

      {job && (
        <JobView
          job={job}
          confirming={confirming}
          onConfirm={handleConfirm}
        />
      )}
    </div>
  );
}

function JobView({
  job,
  confirming,
  onConfirm,
}: {
  job: Job;
  confirming: boolean;
  onConfirm: () => void;
}) {
  const isAbsent = job.validation?.verdict === "ABSENT";
  const awaitingConfirmation = job.status === "AWAITING_CONFIRMATION";

  return (
    <section className="job-view">
      <div className="job-header">
        <span className={`status status-${job.status.toLowerCase()}`}>
          {job.status === "AWAITING_CONFIRMATION" ? "AWAITING CONFIRMATION" : job.status}
        </span>
        <span className="job-id">{job.id}</span>
      </div>

      {job.status === "FAILED" && job.errorMessage && (
        <div className="error">Error: {job.errorMessage}</div>
      )}

      {job.status === "EXPIRED" && (
        <div className="error">
          Session expired before confirmation. Submit the job again to retry.
        </div>
      )}

      {job.validation && (
        <div className={`verdict verdict-${job.validation.verdict.toLowerCase()}`}>
          <h3>Verdict: {job.validation.verdict}</h3>
          <p>{job.validation.reasoning}</p>
          {isAbsent && (
            <p className="muted">
              No target was extracted because the page does not contain what
              was requested.
            </p>
          )}
        </div>
      )}

      {job.target && (
        <TargetBlock
          target={job.target}
          awaitingConfirmation={awaitingConfirmation}
          confirming={confirming}
          onConfirm={onConfirm}
        />
      )}

      {job.extractedStructure && (
        <StructureBlock structure={job.extractedStructure} />
      )}

      {job.hasScreenshot && (
        <div className="screenshot">
          <h3>Screenshot</h3>
          <img src={screenshotUrl(job.id)} alt="Captured page" />
        </div>
      )}
    </section>
  );
}

function TargetBlock({
  target,
  awaitingConfirmation,
  confirming,
  onConfirm,
}: {
  target: Target;
  awaitingConfirmation: boolean;
  confirming: boolean;
  onConfirm: () => void;
}) {
  const heading =
    target.type === "MULTI" ? "Row target (MULTI)" : "Value target (SINGLE)";

  const confirmPrompt =
    target.type === "MULTI"
      ? "Does this look like the row structure you want extracted?"
      : "Is this the value you want extracted?";

  return (
    <div className="example">
      <h3>{heading}</h3>
      <div className="target-discriminator muted">type={target.type}</div>

      <table className="example-fields">
        <tbody>
          {target.fields.map((f) => (
            <tr key={f.name}>
              <th>{f.name}</th>
              <td>{f.text}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {awaitingConfirmation && (
        <div className="confirm-prompt">
          <p className="muted">
            {confirmPrompt} Confirm within 3 minutes to continue. The browser
            session will close automatically after that.
          </p>
          <button type="button" onClick={onConfirm} disabled={confirming}>
            {confirming ? "Confirming…" : "Confirm"}
          </button>
        </div>
      )}

    </div>
  );
}

function StructureBlock({ structure }: { structure: ExtractedStructure }) {
  return (
    <div className="example">
      <h3>Inferred structure</h3>
      <div className="target-discriminator muted">
        rowSelector: <code>{structure.rowSelector}</code>
      </div>
      <table className="example-fields">
        <tbody>
          {structure.fields.map((f) => (
            <tr key={f.name}>
              <th>{f.name}</th>
              <td>
                <code>{f.selector}</code>{" "}
                <span className="muted">
                  ({[
                    f.nth != null ? `nth: ${f.nth}` : null,
                    f.source.from === "TEXT" ? "text" : `attr:${f.source.name}`,
                  ]
                    .filter(Boolean)
                    .join(" · ")})
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
