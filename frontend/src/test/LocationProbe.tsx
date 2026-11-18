import { useLocation } from 'react-router'

export function LocationProbe() {
  const location = useLocation()
  return (
    <p data-testid="route-location">{`${location.pathname}${location.search}`}</p>
  )
}
