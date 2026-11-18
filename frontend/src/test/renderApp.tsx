import { render } from '@testing-library/react'
import type { RenderResult } from '@testing-library/react'
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router'
import type { RouteObject } from 'react-router'
import { appRoutes } from '../appRoutes.tsx'
import { LocationProbe } from './LocationProbe.tsx'

export function memoryRoutes(probe: boolean): RouteObject[] {
  if (!probe) {
    return appRoutes
  }
  return [
    {
      element: (
        <>
          <Outlet />
          <LocationProbe />
        </>
      ),
      children: appRoutes,
    },
  ]
}

export function renderApp(
  path: string,
  options?: { probe?: boolean },
): RenderResult {
  const router = createMemoryRouter(memoryRoutes(options?.probe ?? false), {
    initialEntries: [path],
  })
  return render(<RouterProvider router={router} />)
}

export function renderRouter(
  entries: string[],
  options?: { index?: number; probe?: boolean },
) {
  const router = createMemoryRouter(memoryRoutes(options?.probe ?? false), {
    initialEntries: entries,
    initialIndex: options?.index ?? entries.length - 1,
  })
  return { router, ...render(<RouterProvider router={router} />) }
}
