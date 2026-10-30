package com.syseo.lucyoverlay

import androidx.annotation.DrawableRes

data class PetInfo(
    val id: String,
    val label: String,
    @DrawableRes val thumbRes: Int
)

object PetCatalog {
    val all = listOf(
        PetInfo("chani", "챠니", R.drawable.thumb_chani),
        PetInfo("yeobi", "여비", R.drawable.thumb_yeobi),
        PetInfo("wonni", "워니", R.drawable.thumb_wonni),
        PetInfo("iri", "이리", R.drawable.thumb_iri)
    )

    fun byId(id: String): PetInfo? = all.find { it.id == id }
}
