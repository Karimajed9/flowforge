const tones: Record<string, string> = {
  SUCCEEDED: 'bg-green-500/15 text-green-300 ring-green-400/40',
  FAILED: 'bg-red-500/15 text-red-200 ring-red-400/40',
  RUNNING: 'bg-amber-400/15 text-amber-100 ring-amber-300/40',
  QUEUED: 'bg-blue-400/15 text-blue-100 ring-blue-300/40',
  RECEIVED: 'bg-blue-400/15 text-blue-100 ring-blue-300/40',
  NOT_STARTED: 'bg-slate-400/10 text-slate-300 ring-slate-400/30',
  SKIPPED: 'bg-violet-400/15 text-violet-100 ring-violet-300/40',
}

export function StatusBadge({ status }: { status: string }) {
  const tone = tones[status] ?? 'bg-navy-800 text-muted ring-line'

  return (
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-1 text-xs font-semibold tracking-wide ring-1 ${tone}`}
    >
      {status}
    </span>
  )
}
