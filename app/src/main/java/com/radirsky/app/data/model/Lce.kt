package com.radirsky.app.data.model

sealed class Lce<out T> {
    object Loading : Lce<Nothing>()
    data class Content<out T>(val data: T) : Lce<T>()
    data class Error(val message: String, val isRateLimit: Boolean = false) : Lce<Nothing>()
}
