package com.caller

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.telecom.TelecomManager

fun resolveDefaultDialerPackage(context: Context): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_DIALER) == true &&
                roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
            ) {
                return context.packageName
            }
        }
    }

    return runCatching {
        (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)
            ?.defaultDialerPackage
            .orEmpty()
    }.getOrDefault("")
}
