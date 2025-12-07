package com.example.melvis
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class RiwayatItem(
    val filename: String? = null,
    val label: String? = null,
    val waktu: String? = null,
    val suhu: String? = null,
    val kelembapan: String? = null,
    val image_url: String? = null
): Parcelable
