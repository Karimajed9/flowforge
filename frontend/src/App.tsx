import { BrowserRouter, Route, Routes } from 'react-router'
import { AppLayout } from './components/AppLayout.tsx'
import { ExecutionDetailPage } from './pages/ExecutionDetailPage.tsx'
import { ExecutionsPage } from './pages/ExecutionsPage.tsx'
import { NotFoundPage } from './pages/NotFoundPage.tsx'
import { OverviewPage } from './pages/OverviewPage.tsx'
import { WorkflowDetailPage } from './pages/WorkflowDetailPage.tsx'
import { WorkflowsPage } from './pages/WorkflowsPage.tsx'

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<OverviewPage />} />
        <Route path="workflows" element={<WorkflowsPage />} />
        <Route path="workflows/:id" element={<WorkflowDetailPage />} />
        <Route path="executions" element={<ExecutionsPage />} />
        <Route path="executions/:id" element={<ExecutionDetailPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}

export default function App() {
  return (
    <BrowserRouter>
      <AppRoutes />
    </BrowserRouter>
  )
}
