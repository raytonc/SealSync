package com.junkfood.seal.ui.common

object Route {

    const val HOME = "home"
    const val DOWNLOADS = "download_history"
    const val PLAYLIST = "playlist"
    const val SETTINGS = "settings"
    const val FORMAT_SELECTION = "format"
    const val TASK_LIST = "task_list"
    const val TASK_LOG = "task_log"

    const val SETTINGS_PAGE = "settings_page"

    const val GENERAL_DOWNLOAD_PREFERENCES = "general_download_preferences"
    const val ABOUT = "about"
    const val CREDITS = "credits"
    const val TEMPLATE = "template"
    const val TEMPLATE_EDIT = "template_edit"
    const val DOWNLOAD_QUEUE = "queue"
    const val COOKIE_PROFILE = "cookie_profile"
    const val COOKIE_GENERATOR_WEBVIEW = "cookie_webview"
    const val AUTO_UPDATE = "auto_update"
    const val DONATE = "donate"

    const val TASK_HASHCODE = "task_hashcode"
    const val TEMPLATE_ID = "template_id"
}

infix fun String.arg(arg: String) = "$this/{$arg}"
infix fun String.id(id: Int) = "$this/$id"
