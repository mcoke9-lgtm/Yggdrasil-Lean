package org.yggdrasil.memorygate;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** A narrow, time-bounded authorization for an exact set of record identifiers. */
final class MemoryAccessGrant {
    final String requestId;
    final String objective;
    final String purpose;
    final Set<String> permittedRecordIds;
    final long expiresAtMillis;
    final long stopAtMillis;

    MemoryAccessGrant(
            String requestId,
            String objective,
            String purpose,
            Set<String> permittedRecordIds,
            long expiresAtMillis,
            long stopAtMillis) {
        this.requestId = requestId;
        this.objective = objective;
        this.purpose = purpose;
        this.permittedRecordIds = permittedRecordIds == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(permittedRecordIds));
        this.expiresAtMillis = expiresAtMillis;
        this.stopAtMillis = stopAtMillis;
    }
}
