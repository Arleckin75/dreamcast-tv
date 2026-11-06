package com.dreamcast.nativeapp

data class OpenSourceLicense(val title: String, val summary: String, val assets: List<String>)

val applicationLicense = OpenSourceLicense(
    "GNU General Public License v3", "Лицензия Dream Cast TV", listOf("licenses/GPL-3.0.txt"),
)

fun playerLicenses(): List<OpenSourceLicense> = buildList {
    add(OpenSourceLicense(
        "Media3 / ExoPlayer 1.9.2", "Apache License 2.0 · The Android Open Source Project",
        listOf("licenses/media3-NOTICES.txt", "licenses/media3-APACHE-2.0.txt"),
    ))
    add(OpenSourceLicense(
        "dav1d 1.5.1", "BSD-2-Clause · VideoLAN и авторы dav1d",
        listOf("licenses/dav1d-BSD-2-Clause.txt", "licenses/dav1d-NOTICES.txt"),
    ))
    add(OpenSourceLicense(
        "cpu_features 0.10.1", "Apache License 2.0 · Google; BSD-уведомления сохранены в тексте",
        listOf("licenses/cpu_features-APACHE-2.0.txt", "licenses/cpu_features-NOTICES.txt"),
    ))
    add(OpenSourceLicense(
        "LLVM / Android NDK runtime", "Уведомления NDK 28.2.13676358, включая libc++ и libc++abi",
        listOf("licenses/ndk-LLVM-NOTICE.txt"),
    ))
    add(OpenSourceLicense(
        "Material Icons", "Apache License 2.0 · Google",
        listOf("licenses/material-icons.txt"),
    ))
}
