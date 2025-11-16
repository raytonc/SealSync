package com.junkfood.seal.ui.common

object Route {
    const val HOME = "home"
    const val DOWNLOADS = "download_history"
    const val SETTINGS = "settings"
    const val SETTINGS_PAGE = "settings_page"
    const val CREDITS = "credits"
}

infix fun String.arg(arg: String) = "$this/{$arg}"
infix fun String.id(id: Int) = "$this/$id"
