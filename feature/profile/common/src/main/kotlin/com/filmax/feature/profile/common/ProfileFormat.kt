
package com.filmax.feature.profile.common

import com.filmax.core.domain.user.model.Subscription
import com.filmax.core.domain.user.model.UserProfile
import com.filmax.core.domain.user.model.initials

fun UserProfile?.initialsOrFallback(): String = this?.initials()?.ifEmpty { "?" } ?: "?"

fun Subscription?.label(): String = when {
    this?.active == true && daysLeft != null -> "Filmax Premium · ещё $daysLeft дн."
    this?.active == true -> "Filmax Premium"
    else -> "Бесплатный аккаунт"
}
