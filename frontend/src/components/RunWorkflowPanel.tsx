import { useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Play } from 'lucide-react'
import { useNavigate } from 'react-router'
import { ApiError } from '../api/errors.ts'
import { runWorkflow } from '../api/client.ts'
import { parsePayload, runFailure, type RunNotice } from './runFeedback.ts'

const runButtonClass =
  'inline-flex items-center justify-center gap-2 rounded-lg bg-teal-500 px-4 py-2.5 text-sm font-semibold text-navy-950 shadow-sm hover:bg-teal-400 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-teal-300 disabled:opacity-40'

export function RunWorkflowPanel({
  workflowId,
  active,
}: {
  workflowId: number
  active: boolean
}) {
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState('{\n  \n}')
  const [notice, setNotice] = useState<RunNotice | null>(null)
  const [running, setRunning] = useState(false)
  const submission = useRef<{ text: string; key: string } | null>(null)

  function submissionKey(text: string) {
    if (submission.current?.text === text) {
      return submission.current.key
    }
    const key = crypto.randomUUID()
    submission.current = { text, key }
    return key
  }

  async function start() {
    if (running) {
      return
    }
    const parsed = parsePayload(draft)
    if (!parsed.ok) {
      setNotice(parsed)
      return
    }
    setRunning(true)
    setNotice(null)
    try {
      const execution = await runWorkflow(workflowId, draft, submissionKey(draft))
      navigate(`/executions/${execution.id}`)
    } catch (caught) {
      setNotice(
        caught instanceof ApiError
          ? runFailure(caught, active)
          : { message: 'The workflow could not be started.', retry: false },
      )
      setRunning(false)
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    void start()
  }

  if (!open) {
    return (
      <button type="button" onClick={() => setOpen(true)} className={runButtonClass}>
        <Play className="size-4" aria-hidden="true" />
        Run workflow
      </button>
    )
  }

  return (
    <form
      onSubmit={submit}
      aria-busy={running}
      className="rounded-2xl border border-teal-400/40 bg-navy-900 p-5 shadow-sm sm:p-6"
    >
      <h2 className="text-lg font-semibold">Run workflow</h2>
      <p className="mt-2 text-sm leading-6 text-muted">
        This starts a real execution. It is not a dry run.
      </p>
      <label className="mt-4 block text-sm font-medium" htmlFor="run-payload">
        Payload
      </label>
      <textarea
        id="run-payload"
        value={draft}
        spellCheck={false}
        rows={8}
        aria-invalid={notice ? true : undefined}
        aria-describedby={notice ? 'run-payload-error' : undefined}
        onChange={(event) => {
          setDraft(event.target.value)
          setNotice(null)
        }}
        className="mt-2 w-full rounded-lg border border-line bg-navy-800 px-3 py-2 font-mono text-sm text-ink"
      />
      {notice ? (
        <p id="run-payload-error" className="mt-3 text-sm text-red-200" role="alert">
          {notice.message}
        </p>
      ) : null}
      {running ? (
        <p className="mt-3 text-sm text-teal-300" role="status">
          Starting the execution.
        </p>
      ) : null}
      <div className="mt-4 flex flex-wrap gap-3">
        <button type="submit" disabled={running} className={runButtonClass}>
          {running ? 'Starting execution' : 'Run workflow'}
        </button>
        {notice?.retry ? (
          <button
            type="button"
            disabled={running}
            onClick={() => void start()}
            className="rounded-lg border border-line px-4 py-2 text-sm font-semibold text-ink hover:bg-navy-800 disabled:opacity-40"
          >
            Try again
          </button>
        ) : null}
        <button
          type="button"
          disabled={running}
          onClick={() => {
            setOpen(false)
            setNotice(null)
          }}
          className="rounded-lg border border-line px-4 py-2 text-sm font-semibold text-ink hover:bg-navy-800 disabled:opacity-40"
        >
          Cancel
        </button>
      </div>
    </form>
  )
}
