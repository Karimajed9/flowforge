import { createBrowserRouter, RouterProvider } from 'react-router'
import { appRoutes } from './appRoutes.tsx'

const router = createBrowserRouter(appRoutes)

export default function App() {
  return <RouterProvider router={router} />
}
