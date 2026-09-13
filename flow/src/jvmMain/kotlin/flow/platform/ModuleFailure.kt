package flow.platform

/**
 * A failure that happened inside an module, with the kind of failure it was.
 *
 * An module runs in another process, so what comes back is text — and text is not enough to
 * decide anything. "Tag mismatch" is the whole of what a GCM authentication failure says for
 * itself, and a caller reading that cannot tell it apart from something the server broke; it was
 * reported as an internal error, when it is the one thing GCM exists to report. So the worker
 * sends the exception's class name alongside its message, and it survives the crossing here.
 *
 * [type] is a fully-qualified class name, or empty when it came from somewhere that did not say.
 */
class ModuleFailure(message: String, val type: String) : RuntimeException(message)
