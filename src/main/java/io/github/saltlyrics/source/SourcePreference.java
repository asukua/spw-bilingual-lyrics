package io.github.saltlyrics.source;

/** The order in which local lyric sources are offered to the caller. */
public enum SourcePreference {
    EMBEDDED_FIRST,
    SIDECAR_FIRST,
    EMBEDDED_ONLY,
    SIDECAR_ONLY
}
