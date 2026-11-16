export function pageTitle(pathname: string): string {
  if (pathname === '/') {
    return 'Overview'
  }
  if (pathname === '/workflows') {
    return 'Workflows'
  }
  if (pathname === '/executions') {
    return 'Executions'
  }
  if (/^\/executions\/[^/]+$/.test(pathname)) {
    return 'Execution'
  }
  return 'Not found'
}
