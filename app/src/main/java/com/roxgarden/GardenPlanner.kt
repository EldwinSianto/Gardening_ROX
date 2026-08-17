package com.roxgarden

import android.util.Log

/**
 * Pilih (tanaman, kota) untuk digarap berdasarkan PRIORITAS pilihan user + weather boost.
 *
 * Aturan: iterasi tanaman dari prioritas tertinggi; untuk tiap tanaman, cek kota-kotanya
 * (urut sesuai daftar di PlantDef.cities); kembalikan pasangan pertama yang cuacanya
 * SEDANG boost menurut jam sekarang (WeatherScanner.currentSlots — bukan slot pertama scan,
 * yang basi begitu 20 menit lewat).
 */
object GardenPlanner {
    private const val TAG = "GardenPlanner"

    data class Choice(val plant: PlantDef, val city: String)

    fun choose(selected: List<PlantDef>, currentSlots: Map<String, WeatherSlot?>): Choice? {
        for (plant in selected) {
            for (city in plant.cities) {
                if (currentSlots[city]?.isBoost == true) {
                    Log.d(TAG, "pilih '${plant.displayName}' @ '$city' (prioritas tertinggi yg boost sekarang)")
                    return Choice(plant, city)
                }
            }
        }
        Log.d(TAG, "tidak ada tanaman terpilih yang punya boost saat ini")
        return null
    }
}
