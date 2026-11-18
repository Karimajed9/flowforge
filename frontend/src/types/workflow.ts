export interface Workflow {
  id: number
  name: string
  description: string | null
  active: boolean
  createdAt: string
  stepRevision: number
}

export interface WorkflowStep {
  id: number
  name: string
  stepType: 'VALIDATE_FIELDS' | 'HTTP_REQUEST'
  position: number
  configuration: Record<string, unknown>
}
