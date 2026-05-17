import { useEffect, useState } from "react";
import {
  confirmJob,
  createJob,
  fetchJob,
  layerScreenshotUrl,
  runScript,
  scriptDownloadUrl,
} from "./api";
import type {
  ExtractedStructure,
  ExtractionPlan,
  ExtractionStep,
  FieldSelector,
  Job,
  PlanStep,
  ReconLayer,
  Target,
} from "./types";
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
  const scriptRunning = job?.scriptStatus === "RUNNING";
  const shouldPoll = job ? !isTerminal || scriptRunning : false;

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
          onRunScript={handleRunScript}
        />
      )}
    </div>
  );
}

function JobView({
  job,
  confirming,
  onConfirm,
  onRunScript,
}: {
  job: Job;
  confirming: boolean;
  onConfirm: () => void;
  onRunScript: () => void;
}) {
  const awaitingConfirmation = job.status === "AWAITING_CONFIRMATION";

  return (
    <section className="job-view">
      <div className="job-header">
        <span className={`status status-${job.status.toLowerCase()}`}>
          {job.status.replace(/_/g, " ")}
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

      {job.layers.map((layer) => (
        <LayerCard
          key={layer.layerIndex}
          job={job}
          layer={layer}
          awaitingConfirmation={
            awaitingConfirmation && layer.layerIndex === 0
          }
          confirming={confirming}
          onConfirm={onConfirm}
        />
      ))}

      {job.planSteps.length > 0 && <PlanTrajectory steps={job.planSteps} />}

      {job.extractionPlan && (
        <ExtractionPlanBlock
          jobId={job.id}
          plan={job.extractionPlan}
          scriptStatus={job.scriptStatus}
          scriptError={job.scriptError}
          scriptResult={job.scriptResult}
          onRun={onRunScript}
        />
      )}
    </section>
  );
}

function ExtractionPlanBlock({
  jobId,
  plan,
  scriptStatus,
  scriptError,
  scriptResult,
  onRun,
}: {
  jobId: string;
  plan: ExtractionPlan;
  scriptStatus: Job["scriptStatus"];
  scriptError: string | null;
  scriptResult: unknown;
  onRun: () => void;
}) {
  const isRunning = scriptStatus === "RUNNING";
  const runLabel = isRunning
    ? "Running…"
    : scriptStatus === "SUCCEEDED" || scriptStatus === "FAILED"
    ? "Run again"
    : "Run scraper";

  return (
    <div className="example extraction-plan">
      <h2>Extraction plan</h2>
      <p className="muted">{plan.description}</p>
      <p className="muted">
        Target: <code>{plan.targetUrl}</code> · output rootKey:{" "}
        <code>{plan.output.rootKey}</code> · format: {plan.output.format}
      </p>

      <div className="actions">
        <button type="button" onClick={onRun} disabled={isRunning}>
          {runLabel}
        </button>
        <a
          href={scriptDownloadUrl(jobId)}
          className="secondary download-link"
          download={`scraper-${jobId}.main.kts`}
        >
          Download script
        </a>
      </div>

      {isRunning && (
        <p className="muted">
          Scraping in progress — this can take a few minutes depending on how
          many rows are on the listing.
        </p>
      )}
      {scriptStatus === "FAILED" && scriptError && (
        <div className="error">Run failed: {scriptError}</div>
      )}

      <ExtractionStepList steps={plan.steps} depth={0} />

      {scriptStatus === "SUCCEEDED" && scriptResult != null && (
        <div className="script-result">
          <h3>Result</h3>
          <pre>{JSON.stringify(scriptResult, null, 2)}</pre>
        </div>
      )}
    </div>
  );
}

function ExtractionStepList({
  steps,
  depth,
}: {
  steps: ExtractionStep[];
  depth: number;
}) {
  return (
    <ol className={`plan-steps depth-${depth}`}>
      {steps.map((step, i) => (
        <li key={i} className={`plan-step step-${step["@type"]}`}>
          <StepRow step={step} />
          {step["@type"] === "ForEachRow" && (
            <ExtractionStepList steps={step.perRowSteps} depth={depth + 1} />
          )}
        </li>
      ))}
    </ol>
  );
}

function StepRow({ step }: { step: ExtractionStep }) {
  switch (step["@type"]) {
    case "Navigate":
      return (
        <StepHeader title="Navigate" description={step.description}>
          <code>{step.url}</code>
        </StepHeader>
      );
    case "DismissConsent":
      return <StepHeader title="Dismiss consent" description={step.description} />;
    case "WaitForSelector":
      return (
        <StepHeader title="Wait for selector" description={step.description}>
          <code>{step.selector}</code>{" "}
          <span className="muted">({step.timeoutMs}ms timeout)</span>
        </StepHeader>
      );
    case "ResolveAndNavigate":
      return (
        <StepHeader title="Follow detail link" description={step.description}>
          <code>{step.detailLinkSelector}</code>
          {step.nth != null && <span className="muted"> · nth {step.nth}</span>}
        </StepHeader>
      );
    case "Click":
      return (
        <StepHeader title="Click" description={step.description}>
          <code>{step.selector}</code>
          {step.text && <span className="muted"> · text "{step.text}"</span>}
          {step.nth != null && <span className="muted"> · nth {step.nth}</span>}
        </StepHeader>
      );
    case "ExtractRows":
      return (
        <StepHeader
          title={`Extract rows → "${step.attachAs}"${limitSuffix(step.limit)}`}
          description={step.description}
        >
          <code>{step.rowSelector}</code>
          <FieldTable fields={step.fields} />
        </StepHeader>
      );
    case "ForEachRow":
      return (
        <StepHeader
          title={`For each row → "${step.attachAs}"${limitSuffix(step.limit)}`}
          description={step.description}
        >
          <code>{step.rowSelector}</code>
          <FieldTable fields={step.fields} />
        </StepHeader>
      );
  }
}

function limitSuffix(limit: number | null | undefined): string {
  return limit != null ? ` (first ${limit})` : "";
}

function StepHeader({
  title,
  description,
  children,
}: {
  title: string;
  description: string;
  children?: React.ReactNode;
}) {
  return (
    <div>
      <div>
        <strong>{title}</strong>
      </div>
      <p className="muted">{description}</p>
      {children}
    </div>
  );
}

function FieldTable({ fields }: { fields: FieldSelector[] }) {
  return (
    <table className="example-fields field-table">
      <tbody>
        {fields.map((f) => (
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
  );
}

function LayerCard({
  job,
  layer,
  awaitingConfirmation,
  confirming,
  onConfirm,
}: {
  job: Job;
  layer: ReconLayer;
  awaitingConfirmation: boolean;
  confirming: boolean;
  onConfirm: () => void;
}) {
  const isAbsent = layer.validation?.verdict === "ABSENT";

  return (
    <div className="layer-card">
      <div className="layer-header">
        <h2>
          Layer {layer.layerIndex} — {layer.layerKind.replace(/_/g, " ")}
        </h2>
        <span className="muted layer-url">{layer.atUrl}</span>
      </div>

      {layer.validation && (
        <div
          className={`verdict verdict-${layer.validation.verdict.toLowerCase()}`}
        >
          <h3>Verdict: {layer.validation.verdict}</h3>
          <p>{layer.validation.reasoning}</p>
          {isAbsent && (
            <p className="muted">
              No target was extracted because the page does not contain what
              was requested.
            </p>
          )}
        </div>
      )}

      {layer.target && (
        <TargetBlock
          target={layer.target}
          awaitingConfirmation={awaitingConfirmation}
          confirming={confirming}
          onConfirm={onConfirm}
        />
      )}

      {layer.extractedStructure && (
        <StructureBlock structure={layer.extractedStructure} />
      )}

      {layer.hasScreenshot && (
        <div className="screenshot">
          <h3>Screenshot</h3>
          <img
            src={layerScreenshotUrl(job.id, layer.layerIndex)}
            alt={`Capture of layer ${layer.layerIndex}`}
          />
        </div>
      )}
    </div>
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
          {structure.detailLink && (
            <tr>
              <th>detailLink</th>
              <td>
                <code>{structure.detailLink.selector}</code>{" "}
                <span className="muted">
                  ({[
                    structure.detailLink.nth != null
                      ? `nth: ${structure.detailLink.nth}`
                      : null,
                    "attr:href",
                  ]
                    .filter(Boolean)
                    .join(" · ")})
                </span>
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

function PlanTrajectory({ steps }: { steps: PlanStep[] }) {
  return (
    <div className="example">
      <h3>Plan trajectory</h3>
      <ol className="plan-steps">
        {steps.map((step) => (
          <li key={step.stepIndex} className={`plan-step plan-${step.outcome.toLowerCase()}`}>
            <div>
              <strong>{step.action.replace(/_/g, " ")}</strong>
              <span className="muted"> — {step.outcome}</span>
            </div>
            <p>{step.reasoning}</p>
            {step.detailMessage && (
              <p className="muted">{step.detailMessage}</p>
            )}
            {step.actionData && Object.keys(step.actionData).length > 0 && (
              <pre className="plan-action-data">
                {JSON.stringify(step.actionData, null, 2)}
              </pre>
            )}
          </li>
        ))}
      </ol>
    </div>
  );
}
