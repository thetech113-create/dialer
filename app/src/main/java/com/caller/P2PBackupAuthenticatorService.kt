package com.caller

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/**
 * Registers the "com.caller.backup" account type. The app creates the one account itself
 * (addAccountExplicitly); this authenticator only exists so the system accepts that type.
 */
class P2PBackupAuthenticatorService : Service() {
    private val authenticator by lazy { Authenticator(this) }

    override fun onBind(intent: Intent?): IBinder? = authenticator.iBinder

    private class Authenticator(context: Context) : AbstractAccountAuthenticator(context) {
        override fun editProperties(r: AccountAuthenticatorResponse?, t: String?): Bundle? = null
        override fun addAccount(
            r: AccountAuthenticatorResponse?, t: String?, a: String?, f: Array<out String>?, o: Bundle?
        ): Bundle? = null
        override fun confirmCredentials(r: AccountAuthenticatorResponse?, a: Account?, o: Bundle?): Bundle? = null
        override fun getAuthToken(r: AccountAuthenticatorResponse?, a: Account?, t: String?, o: Bundle?): Bundle? = null
        override fun getAuthTokenLabel(t: String?): String? = null
        override fun updateCredentials(r: AccountAuthenticatorResponse?, a: Account?, t: String?, o: Bundle?): Bundle? = null
        override fun hasFeatures(r: AccountAuthenticatorResponse?, a: Account?, f: Array<out String>?): Bundle =
            Bundle().apply { putBoolean(android.accounts.AccountManager.KEY_BOOLEAN_RESULT, false) }
    }
}
