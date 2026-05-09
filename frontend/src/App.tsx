import { useEffect, useState } from "react";
import { createJob, fetchJob, screenshotUrl } from "./api";
import type { Job } from "./types";
import "./App.css";

export function App() {
  const [description, setDescription] = useState("");
  const [url, setUrl] = useState("");
  const [job, setJob] = useState<Job | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const isTerminal = job?.status === "SUCCEEDED" || job?.status === "FAILED";

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

      {job && <JobView job={job} />}
    </div>
  );
}

function JobView({ job }: { job: Job }) {
  const isAbsent = job.validation?.verdict === "ABSENT";

  return (
    <section className="job-view">
      <div className="job-header">
        <span className={`status status-${job.status.toLowerCase()}`}>
          {job.status}
        </span>
        <span className="job-id">{job.id}</span>
      </div>

      {job.status === "FAILED" && job.errorMessage && (
        <div className="error">Error: {job.errorMessage}</div>
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

      {job.example && <ExampleBlock example={job.example} />}

      {job.hasScreenshot && (
        <div className="screenshot">
          <h3>Screenshot</h3>
          <img src={screenshotUrl(job.id)} alt="Captured page" />
        </div>
      )}
    </section>
  );
}

function ExampleBlock({ example }: { example: NonNullable<Job["example"]> }) {
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
    </div>
  );
}
