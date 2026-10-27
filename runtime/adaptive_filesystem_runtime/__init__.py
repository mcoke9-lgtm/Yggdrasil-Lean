"""Reference implementation for Adaptive Filesystem review-history resolution."""
from .resolver import Record, ResolutionError, ResolutionResult, resolve_history
__all__ = ["Record", "ResolutionError", "ResolutionResult", "resolve_history"]
