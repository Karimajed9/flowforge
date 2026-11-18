import { renderToString } from 'react-dom/server'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { expect, test } from 'vitest'
import { appRoutes } from './appRoutes.tsx'

test('overview route renders the dashboard shell', () => {
  const html = renderToString(
    <RouterProvider router={createMemoryRouter(appRoutes, { initialEntries: ['/'] })} />,
  )

  expect(html).toContain('FLOWFORGE')
  expect(html).toContain('Overview')
  expect(html).toContain('href="/workflows"')
  expect(html).toContain('href="/executions"')
})

test('unknown routes render the not found page', () => {
  const html = renderToString(
    <RouterProvider
      router={createMemoryRouter(appRoutes, { initialEntries: ['/missing'] })}
    />,
  )

  expect(html).toContain('Page not found')
  expect(html).toContain('href="/"')
})
