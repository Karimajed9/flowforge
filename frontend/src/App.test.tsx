import { renderToString } from 'react-dom/server'
import { MemoryRouter } from 'react-router'
import { expect, test } from 'vitest'
import { AppRoutes } from './App.tsx'

test('overview route renders the dashboard shell', () => {
  const html = renderToString(
    <MemoryRouter initialEntries={['/']}>
      <AppRoutes />
    </MemoryRouter>,
  )

  expect(html).toContain('FLOWFORGE')
  expect(html).toContain('Overview')
  expect(html).toContain('href="/workflows"')
  expect(html).toContain('href="/executions"')
})

test('unknown routes render the not found page', () => {
  const html = renderToString(
    <MemoryRouter initialEntries={['/missing']}>
      <AppRoutes />
    </MemoryRouter>,
  )

  expect(html).toContain('Page not found')
  expect(html).toContain('href="/"')
})
