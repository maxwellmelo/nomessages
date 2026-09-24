package dev.mx3.nomessages.ui

/** Owns a worker-created object from creation until its UI request is disposed. */
internal class OwnedResource<T>(private val release: (T) -> Unit) {
    private val lock = Any()
    private var disposed = false
    private var value: T? = null

    fun install(newValue: T) {
        var oldValue: T? = null
        var releaseNew = false
        synchronized(lock) {
            if (disposed) {
                releaseNew = true
            } else {
                oldValue = value
                value = newValue
            }
        }
        oldValue?.let(release)
        if (releaseNew) release(newValue)
    }

    fun dispose() {
        val owned = synchronized(lock) {
            if (disposed) return
            disposed = true
            value.also { value = null }
        }
        owned?.let(release)
    }
}
