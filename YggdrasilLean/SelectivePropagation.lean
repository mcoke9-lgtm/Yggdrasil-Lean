/-!
SelectivePropagation.lean
Status: initial formal model; dependency-impact semantics are explicit and provisional.
This module plans which dependency links need review; it does not mutate project
states, execute downstream updates, or provide durable event delivery.
-/

import YggdrasilLean.BranchDivergence

namespace YggdrasilLean

/-- Project-state fields whose changes may affect dependent work. -/
inductive StateField where
  | agreements
  | evidence
  | dependencies
  | unresolved
  | stateVersion
  deriving DecidableEq, Repr

/-- A recorded change names the fields it modifies. -/
structure ChangeSet where
  changedFields : List StateField
  deriving DecidableEq, Repr

/-- A dependency declares which upstream fields it relies on. -/
structure DependencyRoute where
  dependencyId : String
  watches : List StateField
  deriving DecidableEq, Repr

/-- A route is affected only when at least one watched field changed. -/
def routeAffected (change : ChangeSet) (route : DependencyRoute) : Bool :=
  route.watches.any fun field => change.changedFields.contains field

/-- Produce a review list containing only routes affected by this change. -/
def planSelectivePropagation
    (change : ChangeSet) (routes : List DependencyRoute) : List DependencyRoute :=
  routes.filter (routeAffected change)

/-- Every route in the plan satisfies the declared impact predicate. -/
theorem plannedRoute_isAffected
    (change : ChangeSet) (routes : List DependencyRoute)
    (route : DependencyRoute)
    (h : route ∈ planSelectivePropagation change routes) :
    routeAffected change route = true := by
  exact (List.mem_filter.mp h).2

/-- A route watching no fields is not selected for propagation. -/
theorem emptyWatchlist_notAffected
    (change : ChangeSet) (route : DependencyRoute)
    (h : route.watches = []) :
    routeAffected change route = false := by
  simp [routeAffected, h]

/-- An empty change set selects no routes. -/
theorem emptyChange_selectsNoRoutes
    (routes : List DependencyRoute) :
    planSelectivePropagation ⟨[]⟩ routes = [] := by
  simp [planSelectivePropagation, routeAffected]

end YggdrasilLean
