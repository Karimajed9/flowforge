export function formatTimestamp(value: string | null | undefined): string {
  if (value == null || value === '') {
    return '—'
  }

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }

  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date)
}

export function pageIndex(value: string | null): number {
  if (value == null || value === '') {
    return 0
  }
  if (!/^(0|[1-9]\d*)$/.test(value)) {
    return 0
  }
  const page = Number(value)
  return Number.isSafeInteger(page) ? page : 0
}

export function positiveId(value: string | null): number | null {
  if (value == null || !/^[1-9]\d*$/.test(value)) {
    return null
  }
  const id = Number(value)
  return Number.isSafeInteger(id) ? id : null
}
