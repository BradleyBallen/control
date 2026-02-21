package com.EvolCorp.control_parental

import android.content.Context

object BlockedAppsManager {
    fun block(context: Context, packageName: String) {
        ParentalPolicyStore.block(context, packageName)
    }

    fun unblock(context: Context, packageName: String) {
        ParentalPolicyStore.unblock(context, packageName)
    }

    fun setBlockedApps(context: Context, packageNames: Collection<String>) {
        ParentalPolicyStore.setAlwaysBlocked(context, packageNames)
    }

    fun getBlockedApps(context: Context): Set<String> {
        return ParentalPolicyStore.getAlwaysBlocked(context)
    }

    fun isBlocked(context: Context, packageName: String): Boolean {
        return ParentalPolicyStore.isAlwaysBlocked(context, packageName)
    }
}
