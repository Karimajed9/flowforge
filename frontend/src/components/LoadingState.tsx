export function LoadingState({ label = 'Loading' }: { label?: string }) {
  return (
    <div
      className="rounded-2xl border border-line bg-navy-900 px-6 py-14 text-center"
      role="status"
    >
      <p className="text-sm font-medium text-muted">{label}</p>
    </div>
  )
}
