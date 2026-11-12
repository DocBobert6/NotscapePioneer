package net.nspioneer.browser

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Cast probe only: Default Media Receiver, so MediaRouter discovery can
 * answer whether Cast routes are visible from a GeckoView app. The spike
 * never launches a Cast session.
 */
class PioneerCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId("CC1AD845")
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
