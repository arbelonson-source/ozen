package com.arbelonson.ozen.core

import kotlin.coroutines.cancellation.CancellationException

enum class CloudKeyCheck {
    Accepted,
    Rejected,
    NoCredit,
    Unreachable;

    val message: String
        get() = when (this) {
            Accepted -> tr("המפתח נבדק והשירות קיבל אותו", "The key was checked and the service accepted it")
            Rejected -> tr("השירות לא קיבל את המפתח. בדקו שהעתקתם אותו במלואו.", "The service didn’t accept this key. Check that you copied all of it.")
            NoCredit -> tr("המפתח תקין, אבל נגמר הקרדיט. הוסיפו קרדיט באתר של השירות.", "The key is valid, but it has no credit left. Add credit at the service.")
            Unreachable -> tr("לא הצלחנו לבדוק את המפתח עכשיו. הוא נשמר, ונבדוק שוב כשהכתוביות יתחילו.", "Couldn’t check the key right now. It’s saved, and it’s checked again when captions start.")
        }

    companion object {
        suspend fun result(provider: CloudProvider, key: String, http: CloudHTTP): CloudKeyCheck {
            val response = try {
                http.send(provider.keyCheckRequest(key))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return Unreachable
            }
            if (provider.acceptsKeyCheck(response)) {
                return if (provider.hasCreditLeft(response)) Accepted else NoCredit
            }
            return when (provider.failure(response)) {
                CloudSpeechError.KeyRejected, CloudSpeechError.KeyMissing -> Rejected
                CloudSpeechError.OutOfCredit -> NoCredit
                CloudSpeechError.RateLimited, CloudSpeechError.Offline, is CloudSpeechError.ServerTrouble, CloudSpeechError.BadReply -> Unreachable
            }
        }
    }
}
