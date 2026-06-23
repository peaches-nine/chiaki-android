package com.metallic.chiaki.lib

enum class VideoUpscaler(val key: String, val label: String) {
    OFF("off", "关闭"),
    FSR1("fsr1", "FSR 1.0"),
    NIS("nis", "NIS (NVIDIA)");

    companion object {
        fun fromKey(key: String): VideoUpscaler =
            values().firstOrNull { it.key == key } ?: OFF
    }
}
