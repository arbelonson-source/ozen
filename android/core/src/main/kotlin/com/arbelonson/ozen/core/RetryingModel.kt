package com.arbelonson.ozen.core

/**
 * Holds a model that is loaded on first use. A failed load is not final:
 * it is tried again after [ModelLoadRetry]'s gaps (not on every call), and
 * the journal gets one line per failure and one when it comes back.
 */
class RetryingModel<T : Any>(
    private val name: String,
    private val load: () -> T,
    private val nowMillis: () -> Long,
    private val note: (String) -> Unit = {},
) {
    private val retry = ModelLoadRetry()
    private var model: T? = null

    fun get(): T? {
        model?.let { return it }
        val now = nowMillis()
        if (!retry.shouldTry(now)) return null
        return try {
            load().also {
                model = it
                if (retry.failureCount > 0) note("$name loaded again after ${retry.failureCount} failed tries")
                retry.succeeded()
            }
        } catch (error: Exception) {
            failed(now)
        } catch (error: OutOfMemoryError) {
            failed(now)
        } catch (error: LinkageError) {
            failed(now)
        }
    }

    private fun failed(now: Long): T? {
        retry.failed(now)
        val gap = retry.nextGapSeconds
        note(
            if (gap != null) "$name failed to load (try ${retry.failureCount}), trying again in $gap s"
            else "$name failed to load (try ${retry.failureCount}), giving up until the app restarts",
        )
        return null
    }
}
