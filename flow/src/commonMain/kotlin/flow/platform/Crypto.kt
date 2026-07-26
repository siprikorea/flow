package flow.platform

// Compute a message digest of `data` using the named algorithm (e.g. "SHA-256").
// Returns null for an unknown algorithm.
expect fun digest(algorithm: String, data: ByteArray): ByteArray?
