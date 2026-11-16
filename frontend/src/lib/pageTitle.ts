export function pageTitle(pathname: string): string {
  if (pathname === '/') {
    return 'Overview'
  }
  if (pathname === '/workflows') {
    return 'Workflows'
  }
  if (/^\/workflows\/[^/]+$/.test(pathname)) {
    return 'Workflow'
  }
  if (pathname === '/executions') {
    return 'Executions'
  }
  if (/^\/executions\/[^/]+$/.test(pathname)) {
    return 'Execution'
  }
  return 'Not found'
}
