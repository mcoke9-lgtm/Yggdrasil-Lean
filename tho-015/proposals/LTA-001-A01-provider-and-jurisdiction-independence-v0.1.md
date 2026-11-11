# LTA-001-A01 — Provider and Jurisdiction Independence
**Version:** 0.1  
**Status:** Adopted design requirement; architecture and implementation not yet specified  
**Parent requirement:** LTA-001 — Organizational Independence  
**Decision date:** 2026-11-11  
**Decision scope:** The Living Tree: Adaptive Filesystem

## 1. Requirement

The Living Tree MUST NOT become institutionally, technically, or operationally dependent on any single device manufacturer, operating-system provider, cloud provider, or geopolitical jurisdiction.

The Living Tree MAY cooperate with, interoperate with, and make use of external providers and jurisdictions. Such cooperation MUST NOT remove the practical ability to change providers, migrate the system and its information, use viable alternatives, or recover without the continued cooperation of any one provider or jurisdiction.

Independence means retaining meaningful choice and the practical ability to exit; it does not mean isolation or refusal to cooperate.

## 2. Design objectives

1. **Provider independence:** No single external provider should be indispensable to the system's continued operation or access to its information.
2. **Platform and device portability:** The system's essential information and organizational relationships should be transferable across supported devices and operating systems.
3. **Jurisdictional resilience:** The system should avoid placing all essential operation, control, or recovery under the exclusive authority of one geopolitical jurisdiction.
4. **Data sovereignty:** The user should retain practical control over access to, export of, and recovery of their information.
5. **Interoperability without captivity:** External services may be used through documented interfaces and replaceable components, without making their proprietary formats or APIs the only route to essential data.
6. **Verified exit and recovery:** Portability and recovery claims must be supported by documented tests, not merely by the existence of export buttons, open formats, or theoretical alternatives.
7. **Supply-chain accountability:** Software origins, signing, updates, dependencies, and relevant ownership or jurisdictional risks should be inspectable to a degree appropriate to their role and impact.

## 3. Constraints and safeguards

- Do not assume that open-source licensing alone guarantees privacy, security, sovereignty, or geopolitical independence.
- Do not assume that using multiple providers automatically removes concentration risk; dependencies may share infrastructure, ownership, legal exposure, or supply chains.
- Identify critical dependencies and document their owners, controlling jurisdictions where reasonably ascertainable, interfaces, and available alternatives.
- Prefer open, documented, and exportable formats and protocols for essential information.
- Keep provider-specific components replaceable and avoid making a single account, application, device, cloud, or network service the sole recovery path.
- Do not introduce paid services, hosting, hardware, or other resource-consuming dependencies without explicit authorization.
- Assess the practical costs and limitations of alternatives; do not claim independence where an exit path is merely theoretical.
- Preserve the ability to cooperate with external systems where doing so serves the user's objectives and does not create an unacceptable single point of dependency.

## 4. Acceptance criteria for future designs

A proposed architecture can claim conformance to this addendum only when it demonstrates, to a degree appropriate to the system's maturity, that:

1. Essential data and organizational metadata can be exported in documented, usable formats without dependence on one provider's continued cooperation.
2. At least one credible alternative exists for each identified critical provider dependency, or the dependency is explicitly recorded as an unresolved risk with a mitigation plan.
3. Core information can be migrated to another supported device or operating-system environment without losing the relationships needed to interpret it.
4. Essential operation and recovery do not rely exclusively on one cloud account, provider-controlled identity, proprietary application, or jurisdiction.
5. Backup and restoration can be performed through a path independent of the primary operating environment and provider.
6. Provider replacement and recovery procedures are documented and tested where feasible; test results and remaining limitations are recorded.
7. Critical software and update provenance can be checked, including package identity and signing information where the platform makes these available.
8. The system communicates residual concentration, jurisdictional, supply-chain, and portability risks honestly.

These criteria are design targets. They have not yet been implemented or tested.

## 5. Relationship to LTA-001

This addendum extends LTA-001 without changing its original organizational-independence requirement. LTA-001 separates the organizational model from the underlying filesystem. This addendum extends the same independence principle to the providers, platforms, and jurisdictions on which the Living Tree may rely.

## 6. Non-goals at this stage

This addendum does not select particular hardware, operating systems, cloud providers, jurisdictions, software licenses, cryptographic systems, hosting arrangements, or migration tools. It does not require immediate replacement of existing devices or services, prohibit all external providers, or authorize purchases, account creation, migrations, repository restructuring, or changes to canonical storage.

## 7. Status and history

- **Decision:** Adopted by the user on 2026-11-11.
- **Specification:** This addendum records the adopted requirement and initial design criteria.
- **Architecture:** Not yet selected.
- **Implementation:** Not started.
- **Verification:** Not performed.
