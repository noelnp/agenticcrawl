import { useEffect, useState } from "react";
import { confirmJob, createJob, fetchJob, screenshotUrl } from "./api";
import type { Job } from "./types";
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
              No example was extracted because the page does not contain the
              requested content.
            </p>
          )}
        </div>
      )}

      {job.example && (
        <ExampleBlock
          example={job.example}
          awaitingConfirmation={awaitingConfirmation}
          confirming={confirming}
          onConfirm={onConfirm}
        />
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

function ExampleBlock({
  example,
  awaitingConfirmation,
  confirming,
  onConfirm,
}: {
  example: NonNullable<Job["example"]>;
  awaitingConfirmation: boolean;
  confirming: boolean;
  onConfirm: () => void;
}) {
  const entries = Object.entries(example.fields);
  return (
    <div className="example">
      <h3>Example</h3>
      <div className="example-container-type">{example.containerType}</div>
      {entries.length > 0 && (
        <table className="example-fields">
          <tbody>
            {entries.map(([name, value]) => (
              <tr key={name}>
                <th>{name}</th>
                <td>{value}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {awaitingConfirmation && (
        <div className="confirm-prompt">
          <p className="muted">
            Does this look like the row structure you want extracted? Confirm
            within 3 minutes to continue. The browser session will close
            automatically after that.
          </p>
          <button type="button" onClick={onConfirm} disabled={confirming}>
            {confirming ? "Confirming…" : "Confirm structure"}
          </button>
        </div>
      )}

      {example.containerHtml && (
        <details className="container-html">
          <summary>Container HTML</summary>
          <pre>{example.containerHtml}</pre>
        </details>
      )}
    </div>
  );
}
