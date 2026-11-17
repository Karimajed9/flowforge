import { useLayoutEffect, useRef, type ReactNode } from 'react'

const regionClass =
  'min-h-[max(14rem,calc(100svh-12rem))] [overflow-anchor:none]'

export function StableRegion({
  busy = false,
  testId,
  className = '',
  children,
}: {
  busy?: boolean
  testId?: string
  className?: string
  children: ReactNode
}) {
  const ref = useRef<HTMLDivElement>(null)
  const reserved = useRef(0)

  useLayoutEffect(() => {
    const node = ref.current
    if (!node) {
      return
    }
    const height = node.offsetHeight
    if (height > reserved.current) {
      reserved.current = height
      node.style.minHeight = `${height}px`
    }
  })

  return (
    <div
      ref={ref}
      data-testid={testId}
      aria-busy={busy || undefined}
      className={className === '' ? regionClass : `${regionClass} ${className}`}
    >
      {children}
    </div>
  )
}
