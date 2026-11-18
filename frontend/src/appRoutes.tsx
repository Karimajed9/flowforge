import type { RouteObject } from 'react-router'
import { AppLayout } from './components/AppLayout.tsx'
import { ExecutionDetailPage } from './pages/ExecutionDetailPage.tsx'
import { ExecutionsPage } from './pages/ExecutionsPage.tsx'
import { NotFoundPage } from './pages/NotFoundPage.tsx'
import { OverviewPage } from './pages/OverviewPage.tsx'
import { WorkflowDetailPage } from './pages/WorkflowDetailPage.tsx'
import { WorkflowsPage } from './pages/WorkflowsPage.tsx'

export const appRoutes: RouteObject[] = [
  {
    element: <AppLayout />,
    children: [
      { index: true, element: <OverviewPage /> },
      { path: 'workflows', element: <WorkflowsPage /> },
      { path: 'workflows/:id', element: <WorkflowDetailPage /> },
      { path: 'executions', element: <ExecutionsPage /> },
      { path: 'executions/:id', element: <ExecutionDetailPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]
