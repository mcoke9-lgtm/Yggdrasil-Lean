/-!
SelectivePropagation.lean
Status: formal review-planning model; dependency-impact semantics are explicit and provisional.
This module plans and records which dependency links need review. It does not mutate
project states, execute downstream updates, or provide durable event delivery.
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

/-- The watched fields that intersect the recorded change set. -/
def changedWatchedFields (change : ChangeSet) (route : DependencyRoute) : List StateField :=
  route.watches.filter fun field => change.changedFields.contains field

/-- A route is affected only when at least one watched field changed. -/
def routeAffected (change : ChangeSet) (route : DependencyRoute) : Bool :=
  !(changedWatchedFields change route).isEmpty

/-- Produce a review list containing only routes affected by this change. -/
def planSelectivePropagation
    (change : ChangeSet) (routes : List DependencyRoute) : List DependencyRoute :=
  routes.filter (routeAffected change)

/-- An auditable decision records the route, its triggering fields, and selection result. -/
structure RouteDecision where
  route : DependencyRoute
  triggeringFields : List StateField
  selectedForReview : Bool
  deriving DecidableEq, Repr

/-- Explain a route decision from the change set and route's declared watchlist. -/
def assessRoute (change : ChangeSet) (route : DependencyRoute) : RouteDecision :=
  let triggers := changedWatchedFields change route
  ⟨route, triggers, !triggers.isEmpty⟩

/-- A propagation assessment retains both inputs and the resulting explanations. -/
structure PropagationAssessment where
  change : ChangeSet
  routes : List DependencyRoute
  decisions : List RouteDecision
  deriving DecidableEq, Repr

/-- Create an in-memory assessment record; this function does not persist it externally. -/
def recordPropagationAssessment
    (change : ChangeSet) (routes : List DependencyRoute) : PropagationAssessment :=
  ⟨change, routes, routes.map (assessRoute change)⟩

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
  simp [routeAffected, changedWatchedFields, h]

/-- An empty change set selects no routes. -/
theorem emptyChange_selectsNoRoutes
    (routes : List DependencyRoute) :
    planSelectivePropagation ⟨[]⟩ routes = [] := by
  simp [planSelectivePropagation, routeAffected, changedWatchedFields]

/-- A recorded route decision preserves the route being assessed. -/
theorem assessRoute_retainsRoute
    (change : ChangeSet) (route : DependencyRoute) :
    (assessRoute change route).route = route := by
  rfl

/-- The explanation lists exactly the route's watched fields present in the change set. -/
theorem assessRoute_recordsTriggers
    (change : ChangeSet) (route : DependencyRoute) :
    (assessRoute change route).triggeringFields = changedWatchedFields change route := by
  rfl

/-- Selection is defined by whether the recorded trigger list is nonempty. -/
theorem assessRoute_selection_matchesTriggers
    (change : ChangeSet) (route : DependencyRoute) :
    (assessRoute change route).selectedForReview =
      !(assessRoute change route).triggeringFields.isEmpty := by
  rfl

/-- The assessment record retains the exact change set and candidate routes. -/
theorem recordAssessment_retainsInputs
    (change : ChangeSet) (routes : List DependencyRoute) :
    (recordPropagationAssessment change routes).change = change ∧
    (recordPropagationAssessment change routes).routes = routes := by
  constructor <;> rfl

/-! Concrete routing scenarios: these are compile-time checks of the model. -/

/-- Example routes: two evidence consumers and one agreement consumer. -/
def exampleRoutes : List DependencyRoute :=
  [ ⟨"evidence-review", [.evidence]⟩
  , ⟨"agreement-review", [.agreements]⟩
  , ⟨"evidence-and-agreement-review", [.evidence, .agreements]⟩
  ]

/-- An evidence-only change selects exactly the evidence-dependent routes. -/
example :
    planSelectivePropagation ⟨[.evidence]⟩ exampleRoutes =
      [ ⟨"evidence-review", [.evidence]⟩
      , ⟨"evidence-and-agreement-review", [.evidence, .agreements]⟩
      ] := by
  decide

/-- A change to both watched fields selects all three routes. -/
example :
    planSelectivePropagation ⟨[.evidence, .agreements]⟩ exampleRoutes =
      exampleRoutes := by
  decide

/-- An unrelated field change selects none of these routes. -/
example :
    planSelectivePropagation ⟨[.dependencies]⟩ exampleRoutes = [] := by
  decide

/-- An evidence-only assessment records the evidence trigger for the first route. -/
example :
    (recordPropagationAssessment ⟨[.evidence]⟩ exampleRoutes).decisions =
      [ ⟨⟨"evidence-review", [.evidence]⟩, [.evidence], true⟩
      , ⟨⟨"agreement-review", [.agreements]⟩, [], false⟩
      , ⟨⟨"evidence-and-agreement-review", [.evidence, .agreements]⟩,
          [.evidence], true⟩
      ] := by
  decide

end YggdrasilLean
