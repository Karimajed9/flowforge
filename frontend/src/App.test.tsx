import { renderToString } from 'react-dom/server'
import { expect, test } from 'vitest'
import App from './App.tsx'

test('starter page renders the counter and docs links', () => {
  const html = renderToString(<App />)

  expect(html).toContain('Get started')
  expect(html).toMatch(/<button[^>]*class="counter"[^>]*>Count is\s*(<!-- -->)?0<\/button>/)
  expect(html).toContain('href="https://vite.dev/"')
  expect(html).toContain('href="https://react.dev/"')
})
